package com.yg.vodtv;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 HLS 代理。
 *
 * 为什么需要它：MediaPlayer 走的是 native 的 HTTP 栈，用不到我们在 Http 里装的
 * 全放行 SSLContext。而片源的 m3u8 / TS 都挂在 Android 5.1 不认识的证书链上，
 * 直接丢给 MediaPlayer 会握手失败。
 *
 * 做法：起一个只监听 127.0.0.1 的小 HTTP 服务，App 用自己的 SSLContext 把上游拉下来，
 * m3u8 里的分片地址全部改写成指向本机，再以明文 HTTP 喂给 MediaPlayer。
 *
 * 路由约定：
 *   /hls/{base64}.m3u8  —— 播放列表，会改写内部地址（后缀必须带 .m3u8，MediaPlayer 靠它判定 HLS）
 *   /ts/{base64}        —— 分片/密钥等二进制，原样转发
 */
public final class Proxy {

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private static ServerSocket server;
    private static int port;
    private static volatile boolean running;

    private Proxy() {
    }

    public static synchronized int start() {
        if (running) {
            return port;
        }
        for (int p = 18765; p <= 18800; p++) {
            try {
                server = new ServerSocket(p, 32, InetAddress.getByName("127.0.0.1"));
                port = p;
                break;
            } catch (Throwable t) {
                // 端口被占，试下一个
            }
        }
        if (server == null) {
            Trace.add("proxy", "18765-18800 全部被占用，本地代理启动失败");
            return 0;
        }
        running = true;
        Trace.add("proxy", "本地代理已启动 127.0.0.1:" + port);
        Thread th = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        Thread w = new Thread(new Runnable() {
                            public void run() {
                                serve(s);
                            }
                        });
                        w.setDaemon(true);
                        w.start();
                    } catch (Throwable t) {
                        if (!running) {
                            break;
                        }
                    }
                }
            }
        });
        th.setDaemon(true);
        th.start();
        return port;
    }

    /** 播放列表地址：改写成 /hls/xxx.m3u8 */
    public static String wrapPlaylist(String url) {
        int p = start();
        if (p <= 0) {
            return url;
        }
        return "http://127.0.0.1:" + p + "/hls/" + enc(url) + ".m3u8";
    }

    /** 分片地址：改写成 /ts/xxx */
    private static String wrapSegment(String url) {
        int p = start();
        if (p <= 0) {
            return url;
        }
        return "http://127.0.0.1:" + p + "/ts/" + enc(url);
    }

    /** ==================== 服务端 ==================== */

    private static void serve(Socket s) {
        try {
            s.setSoTimeout(25000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) {
                return;
            }
            String[] sp = line.split(" ");
            if (sp.length < 2) {
                return;
            }
            Map<String, String> hd = new HashMap<String, String>();
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                int c = h.indexOf(':');
                if (c > 0) {
                    hd.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
                }
            }
            String path = sp[1];
            // 少数播放器会发 absolute-form 请求行（GET http://host/path HTTP/1.1），先剥掉协议和主机
            int scheme = path.indexOf("://");
            if (scheme > 0 && scheme < 8) {
                int slash = path.indexOf('/', scheme + 3);
                path = slash > 0 ? path.substring(slash) : "/";
            }
            boolean head = "HEAD".equalsIgnoreCase(sp[0]);
            // v2.19：把内存日志挂到本地代理上，adb forward 之后 curl 就能读。
            // 这台电视的固件既不输出三方 App 的 logcat，shell 又进不去 /sdcard，
            // 验收期想量「起播几秒、自动换了几次源」没有任何旁路 —— 这条路是自己开的。
            if (path.startsWith("/trace")) {
                sendPlain(s, 200, Trace.tail(400));
                return;
            }
            boolean isPlaylist;
            String b64;
            if (path.startsWith("/hls/")) {
                isPlaylist = true;
                b64 = path.substring(5);
            } else if (path.startsWith("/ts/")) {
                isPlaylist = false;
                b64 = path.substring(4);
            } else {
                sendPlain(s, 404, "not found");
                return;
            }
            if (b64.endsWith(".m3u8")) {
                b64 = b64.substring(0, b64.length() - 5);
            }
            int q = b64.indexOf('?');
            if (q >= 0) {
                b64 = b64.substring(0, q);
            }
            String target = dec(b64);
            if (target == null || target.length() < 8) {
                sendPlain(s, 400, "bad url");
                return;
            }
            fetch(s, target, hd, isPlaylist, head);
        } catch (Throwable t) {
            // 单次请求失败不影响服务。
            // 但必须给播放器一个明确的失败响应：以前这里只关 socket，MediaPlayer
            // 拿不到 HTTP 状态就只能干等自己的 12 秒超时 —— 公共源里那些早已失效的
            // 地址就表现成「点了没反应」。502 能让 onError 立刻触发，马上换下一路。
            String m = String.valueOf(t.getMessage());
            if (m.contains("EPIPE") || m.contains("ECONNRESET") || m.contains("Socket closed")) {
                // 客户端主动断开（换台/换源），属正常事件，不写进「异常」
                Trace.add("proxy", "客户端断开，结束本次转发");
            } else {
                Trace.err("proxy", "请求处理异常", t);
                sendPlain(s, 502, "upstream failed");
            }
        } finally {
            try {
                s.close();
            } catch (Throwable t) {
            }
        }
    }

    private static void fetch(Socket s, String target, Map<String, String> hd, boolean expectPlaylist,
            boolean head) throws Exception {
        boolean guessPlaylist = expectPlaylist || target.toLowerCase(Locale.US).contains(".m3u8");
        // v2.10：播放列表只有一两百字节，建连超过 5 秒基本可以判定这路源不通。
        // 以前列表和分片共用 12s/20s，一次失败就要干等 12 秒 —— 用户看到的就是
        // 「点了半天还在转圈」。分片是几百 KB 的实体流，读超时保留 20 秒，
        // 否则正常码率也会被误杀。
        if (guessPlaylist) {
            String cached = plCacheGet(target);
            if (cached != null) {
                sendPlaylist(s, cached, head);
                return;
            }
        }
        HttpURLConnection c = guessPlaylist
                ? Http.open(target, Api.REFERER, 5000, 8000)
                : Http.open(target, Api.REFERER, 6000, 20000);
        String range = hd.get("range");
        if (range != null) {
            c.setRequestProperty("Range", range);
        }
        int code = c.getResponseCode();
        String ct = c.getContentType();
        boolean playlist = guessPlaylist
                || (ct != null && (ct.contains("mpegurl") || ct.contains("m3u")));

        InputStream src = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (src == null) {
            Trace.add("proxy", "上游无响应 " + code + " " + Http.shortUrl(target));
            sendPlain(s, 502, "upstream error");
            return;
        }
        if (code >= 400) {
            Trace.add("proxy", "上游 " + code + " " + Http.shortUrl(target));
        }

        if (playlist && code < 400) {
            byte[] raw = Http.readAll(src);
            if (raw == null) {
                sendPlain(s, 502, "upstream error");
                return;
            }
            String text = new String(raw, "UTF-8");
            if (text.indexOf("#EXTM3U") < 0) {
                Trace.add("proxy", "返回的不是 m3u8，头部=" + headOf(text));
            }
            // 关键加固：把 master 多码率列表在这里解开，直接把媒体列表喂给播放器。
            // Android 5.1 的 NuPlayer 对 #EXT-X-STREAM-INF 这类 master 列表兼容性不稳，
            // 少一层跳转就少一个失败点。
            String base = target;
            int depth = 0;
            while (text.contains("#EXT-X-STREAM-INF") && depth < 3) {
                // v2.10：以前只取一档，这一档拉不到就 break —— 整条线路直接作废。
                // 现在按码率接近度排出候选，逐个试。用 getQuick（单次、5s/8s）
                // 而不是 get（3 次重试、12s/20s）：直播场景等 36 秒等于卡死。
                ArrayList<String> cands = bestVariants(text);
                String next = null;
                String absOk = null;
                for (int i = 0; i < cands.size(); i++) {
                    String abs = resolve(base, cands.get(i));
                    String got = Http.getQuick(abs, Api.REFERER, 5000, 8000);
                    if (got != null && got.indexOf("#EXTM3U") >= 0) {
                        next = got;
                        absOk = abs;
                        break;
                    }
                    Trace.add("proxy", "档位拉取失败 " + Http.shortUrl(abs));
                }
                if (next == null) {
                    break;
                }
                text = next;
                base = absOk;
                depth++;
            }
            boolean master = text.contains("#EXT-X-STREAM-INF");
            String body = rewrite(text, base, master);
            plCachePut(target, body);
            sendPlaylist(s, body, head);
            return;
        }

        String cl = c.getHeaderField("Content-Length");
        String cr = c.getHeaderField("Content-Range");
        StringBuilder hb = new StringBuilder(256);
        hb.append("HTTP/1.1 ").append(code).append(" X\r\n");
        hb.append("Content-Type: ").append(ct == null ? "application/octet-stream" : ct).append("\r\n");
        if (cl != null) {
            hb.append("Content-Length: ").append(cl).append("\r\n");
        }
        if (cr != null) {
            hb.append("Content-Range: ").append(cr).append("\r\n");
        }
        hb.append("Accept-Ranges: bytes\r\n");
        hb.append("Connection: close\r\n\r\n");

        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32768);
        out.write(hb.toString().getBytes("UTF-8"));
        if (head) {
            out.flush();
            return;
        }
        byte[] buf = new byte[32768];
        int n;
        while ((n = src.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
        try {
            src.close();
        } catch (Throwable t) {
        }
        // 不调用 disconnect()，让 HttpURLConnection 的底层连接回到连接池复用
    }

    /* ==================== 播放列表缓存（v2.10） ==================== */

    /**
     * 播放列表极短缓存。
     *
     * 为什么要有：直播列表 2~6 秒才滚动一次，但播放器在起播、切台、重新准备时
     * 会短时间内重复请求同一个 m3u8。每次都实打实走一趟上游（境外源尤其慢），
     * 叠加起来就是「按键之后一直转圈」。
     *
     * TTL 只给 1.5 秒：够挡掉同一秒内的重复请求，又不会让播放器拿到过期的分片列表
     * （拿过期列表会表现为分片 404 / 画面倒退）。
     */
    private static final long PL_CACHE_MS = 1500L;
    private static final int PL_CACHE_MAX = 48;
    private static final Map<String, String[]> PL_CACHE = new HashMap<String, String[]>();

    private static String plCacheGet(String key) {
        synchronized (PL_CACHE) {
            String[] v = PL_CACHE.get(key);
            if (v == null) {
                return null;
            }
            long ts;
            try {
                ts = Long.parseLong(v[1]);
            } catch (Throwable t) {
                ts = 0L;
            }
            if (System.currentTimeMillis() - ts > PL_CACHE_MS) {
                PL_CACHE.remove(key);
                return null;
            }
            return v[0];
        }
    }

    private static void plCachePut(String key, String body) {
        synchronized (PL_CACHE) {
            if (PL_CACHE.size() >= PL_CACHE_MAX) {
                PL_CACHE.clear();
            }
            PL_CACHE.put(key, new String[]{body, String.valueOf(System.currentTimeMillis())});
        }
    }

    /** 把改写后的播放列表写给播放器（字节长度必须写对，否则 MediaPlayer 会截断）。 */
    private static void sendPlaylist(Socket s, String text, boolean head) throws Exception {
        byte[] body = text.getBytes("UTF-8");
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32768);
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/vnd.apple.mpegurl\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes("UTF-8"));
        if (!head) {
            out.write(body);
        }
        out.flush();
    }

    /**
     * 改写播放列表：master 里的子列表继续走 /hls/，媒体列表里的分片走 /ts/。
     * 手写相对路径解析，避免 java.net.URI 对 sign 参数里的特殊字符抛异常。
     */
    private static String rewrite(String playlist, String base, boolean master) {
        StringBuilder sb = new StringBuilder(playlist.length() + 4096);
        String[] lines = playlist.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#")) {
                sb.append(rewriteAttrs(line, base)).append('\n');
            } else if (master) {
                sb.append(wrapPlaylist(resolve(base, line))).append('\n');
            } else {
                sb.append(wrapSegment(resolve(base, line))).append('\n');
            }
        }
        return sb.toString();
    }

    /** 处理 #EXT-X-KEY / #EXT-X-MAP 里的 URI="..."，这些都是二进制资源，走 /ts/ */
    private static String rewriteAttrs(String line, String base) {
        if (line.indexOf("URI=") < 0) {
            return line;
        }
        Matcher m = URI_ATTR.matcher(line);
        StringBuffer sb = new StringBuffer(line.length() + 256);
        while (m.find()) {
            String abs = resolve(base, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + wrapSegment(abs) + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * v2.10：master 列表按「码率接近度」排出候选，最多 4 档。
     *
     * v2.8 定下的目标是「离 2.6Mbps 最近」：以前的策略是取「不超过 5Mbps 里最大的那一档」，
     * 只要有 4Mbps 就一定挑 4Mbps。这在电脑上没问题，在电视上是灾难 —— 盒子多半是
     * 802.11n 的 wifi、解码器也是老一代，4Mbps 能建连、能起播，然后每隔几秒 buffering 一次。
     * 现在 2.6Mbps 这个目标不变，变的是「不止挑一档」。
     *
     * 排序规则（数值越小越优先）：
     *  - 有 BANDWIDTH 且 ≥ 0.8Mbps：|bw - 2.6Mbps|，越接近目标越靠前
     *  - 有 BANDWIDTH 但 < 0.8Mbps：垫底（那是马赛克档），按 bw 越大越靠前
     *  - 没标 BANDWIDTH：最后考虑
     *
     * 以前只返回一档，那一档拉不到整条线路就废了。现在交给调用方逐个试。
     */
    private static ArrayList<String> bestVariants(String text) {
        ArrayList<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        String[] ls = text.replace("\r", "").split("\n");
        ArrayList<String> urls = new ArrayList<String>();
        ArrayList<Integer> bws = new ArrayList<Integer>();
        int lastBw = -1;
        for (int i = 0; i < ls.length; i++) {
            String s = ls[i].trim();
            if (s.length() == 0) {
                continue;
            }
            if (s.startsWith("#EXT-X-STREAM-INF")) {
                lastBw = -1;
                int k = s.toUpperCase(Locale.US).indexOf("BANDWIDTH=");
                if (k >= 0) {
                    int j = k + 10;
                    int e = j;
                    while (e < s.length() && Character.isDigit(s.charAt(e))) {
                        e++;
                    }
                    try {
                        lastBw = Integer.parseInt(s.substring(j, e));
                    } catch (Throwable t) {
                        lastBw = -1;
                    }
                }
                continue;
            }
            if (s.charAt(0) == '#') {
                continue;
            }
            urls.add(s);
            bws.add(Integer.valueOf(lastBw));
            lastBw = -1;
        }
        int n = urls.size();
        if (n == 0) {
            return out;
        }
        // 目标码率 2.6Mbps：1080p 直播在 10 尺视距下够清楚，老盒子也扛得住
        final int target = 2600000;
        long[] sc = new long[n];
        for (int i = 0; i < n; i++) {
            int bw = bws.get(i).intValue();
            if (bw <= 0) {
                sc[i] = 1L << 40;
            } else if (bw < 800000) {
                sc[i] = 100000000L + bw;
            } else {
                sc[i] = Math.abs(bw - target);
            }
        }
        boolean[] used = new boolean[n];
        int want = Math.min(4, n);
        for (int round = 0; round < want; round++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                if (best < 0 || sc[i] < sc[best]) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            used[best] = true;
            out.add(urls.get(best));
        }
        return out;
    }

    private static String bestVariant(String text) {
        ArrayList<String> v = bestVariants(text);
        return v.isEmpty() ? null : v.get(0);
    }

    /** 相对地址转绝对地址。 */
    private static String resolve(String base, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return ref;
        }
        if (ref.startsWith("//")) {
            int i = base.indexOf("://");
            return (i > 0 ? base.substring(0, i) : "https") + ":" + ref;
        }
        int hs = base.indexOf("://");
        if (hs < 0) {
            return ref;
        }
        int ps = base.indexOf('/', hs + 3);
        String origin = ps > 0 ? base.substring(0, ps) : base;
        String p = ps > 0 ? base.substring(ps) : "/";
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int sl = p.lastIndexOf('/');
        String dir = sl >= 0 ? p.substring(0, sl + 1) : "/";
        String full = ref.startsWith("/") ? ref : dir + ref;
        int rq = full.indexOf('?');
        String pathOnly = rq >= 0 ? full.substring(0, rq) : full;
        String query = rq >= 0 ? full.substring(rq) : "";
        return origin + normalize(pathOnly) + query;
    }

    private static String normalize(String path) {
        String[] seg = path.split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < seg.length; i++) {
            String x = seg[i];
            if (x.length() == 0 || x.equals(".")) {
                continue;
            }
            if (x.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(x);
        }
        StringBuilder sb = new StringBuilder(path.length() + 8);
        sb.append('/');
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    /** ==================== 工具 ==================== */

    /** 上游返回错误页时，把开头一小段记进日志，便于判断是 403 还是 Cloudflare 拦截。 */
    private static String headOf(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    private static String enc(String s) {
        try {
            return Base64.encodeToString(s.getBytes("UTF-8"),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            int pad = (4 - s.length() % 4) % 4;
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < pad; i++) {
                sb.append('=');
            }
            byte[] b = Base64.decode(sb.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
        }
        if (c < 0 && b.size() == 0) {
            return null;
        }
        return new String(b.toByteArray(), "UTF-8");
    }

    private static void sendPlain(Socket s, int code, String msg) {
        try {
            byte[] b = msg.getBytes("UTF-8");
            OutputStream out = s.getOutputStream();
            String h = "HTTP/1.1 " + code + " X\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(h.getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Throwable t) {
        }
    }
}

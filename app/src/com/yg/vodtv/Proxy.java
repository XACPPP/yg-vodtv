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
            // 单次请求失败不影响服务
            Trace.err("proxy", "请求处理异常", t);
        } finally {
            try {
                s.close();
            } catch (Throwable t) {
            }
        }
    }

    private static void fetch(Socket s, String target, Map<String, String> hd, boolean expectPlaylist,
            boolean head) throws Exception {
        HttpURLConnection c = Http.open(target, Api.REFERER);
        String range = hd.get("range");
        if (range != null) {
            c.setRequestProperty("Range", range);
        }
        int code = c.getResponseCode();
        String ct = c.getContentType();
        boolean playlist = expectPlaylist
                || (ct != null && (ct.contains("mpegurl") || ct.contains("m3u")))
                || target.toLowerCase(Locale.US).contains(".m3u8");

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
                String sub = bestVariant(text);
                if (sub == null) {
                    break;
                }
                String abs = resolve(base, sub);
                String next = Http.get(abs, Api.REFERER);
                if (next == null || next.indexOf("#EXTM3U") < 0) {
                    Trace.add("proxy", "子列表拉取失败 " + Http.shortUrl(abs));
                    break;
                }
                text = next;
                base = abs;
                depth++;
            }
            boolean master = text.contains("#EXT-X-STREAM-INF");
            byte[] body = rewrite(text, base, master).getBytes("UTF-8");
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
     * 从 master 里挑一个变体。
     *
     * v2.8 改：以前取的是「不超过 5Mbps 里最大的那一档」，只要有 4Mbps 就一定挑 4Mbps。
     * 这个策略在电脑上没问题，在电视上是灾难：盒子多半是 802.11n 的 wifi、解码器也是
     * 老一代，4Mbps 的档位能建连、能起播，然后每隔几秒就 buffering 一次 ——
     * 用户看到的就是「明明有源却一直在转圈」。现在改成取离 2.6Mbps 最近的一档：
     * 1080p 直播在 10 尺视距下这个码率完全够清楚，同时给 wifi 和解码器都留了余量。
     * 低于 0.8Mbps 的档不选（那是马赛克）；全都没有 BANDWIDTH 标注时退回带宽最大的。
     */
    private static String bestVariant(String text) {
        if (text == null) {
            return null;
        }
        String[] ls = text.replace("\r", "").split("\n");
        String first = null;
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
            if (first == null) {
                first = s;
            }
            urls.add(s);
            bws.add(Integer.valueOf(lastBw));
            lastBw = -1;
        }
        if (urls.isEmpty()) {
            return null;
        }
        // 目标码率 2.6Mbps：1080p 直播在 10 尺视距下够清楚，老盒子也扛得住
        final int target = 2600000;
        int bestIdx = -1;
        int bestScore = Integer.MAX_VALUE;
        int maxIdx = -1;
        int maxBw = -1;
        for (int i = 0; i < urls.size(); i++) {
            int bw = bws.get(i).intValue();
            if (bw <= 0) {
                continue;
            }
            if (bw <= 5000000 && bw > maxBw) {
                maxBw = bw;
                maxIdx = i;
            }
            if (bw < 800000) {
                continue;
            }
            int score = Math.abs(bw - target);
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            return urls.get(bestIdx);
        }
        if (maxIdx >= 0) {
            return urls.get(maxIdx);
        }
        return first;
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

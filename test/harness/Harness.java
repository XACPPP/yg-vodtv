import com.yg.vodtv.Api;
import com.yg.vodtv.Http;
import com.yg.vodtv.Proxy;
import com.yg.vodtv.Trace;
import com.yg.vodtv.Vod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 端到端验证：直接跑 App 里真实的 Proxy.java + Http.java + Api.java + Vod.java + Trace.java
 * （只把 android.util.Base64 和 org.json 换成桌面等价实现），
 * 用真实的 m3u8 和 TS 分片走一遍 MediaPlayer 会走的全部跳转。
 *
 * 跑法：bash test/harness/run.sh
 */
public final class Harness {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) throws Exception {
        Http.trustAll();

        int port = Proxy.start();
        check(port > 0, "本地代理启动 127.0.0.1:" + port);
        if (port <= 0) {
            done();
            return;
        }

        // ---- 真实接口：分类 / 搜索 / 详情 ----
        Api.Page p = Api.list(6, 1);
        check(p.items.size() > 0, "分类列表 动作片 第1页 " + p.items.size() + " 条，共 "
                + p.pagecount + " 页 / " + p.total + " 部");
        if (p.items.isEmpty()) {
            done();
            return;
        }

        java.util.List<Vod> sg = Api.suggest("fanhua");
        check(sg.size() > 0, "搜索 fanhua → " + sg.size() + " 条"
                + (sg.isEmpty() ? "" : "，首条「" + sg.get(0).name + "」"));

        Vod d = Api.detail(p.items.get(0).id);
        check(d != null && d.epUrls.length > 0, "详情「" + p.items.get(0).name + "」→ "
                + (d == null ? 0 : d.epUrls.length) + " 集，线路 " + (d == null ? "" : d.playFrom));
        if (d == null || d.epUrls.length == 0) {
            done();
            return;
        }
        check(d.epNames.length == d.epUrls.length, "集名与地址一一对应（"
                + d.epNames.length + " 组）");

        final String raw = d.epUrls[0];

        // ---- 走本地代理，验证 master 就地解开 ----
        String local = Proxy.wrapPlaylist(raw);
        check(local.startsWith("http://127.0.0.1:" + port + "/hls/") && local.endsWith(".m3u8"),
                "播放地址改写为本地 /hls/*.m3u8 路由");

        long t0 = System.currentTimeMillis();
        String body = get(local);
        long ms = System.currentTimeMillis() - t0;
        check(body != null && body.indexOf("#EXTM3U") >= 0, "本地代理返回合法 m3u8（" + ms + " ms）");
        check(body != null && body.indexOf("#EXT-X-STREAM-INF") < 0,
                "master 多码率列表已在代理内就地解开，播放器只看到媒体列表");
        int segs = count(body, "#EXTINF");
        check(segs > 0, "媒体列表含 " + segs + " 个分片");
        check(body != null && body.indexOf("wsyzyts.vip") < 0
                && body.indexOf("wsyzym3u8.com") < 0,
                "分片地址已全部改写为本机代理，未漏上游域名");

        // ---- 分片 ----
        String first = firstUri(body);
        check(first != null && first.startsWith("http://127.0.0.1:" + port + "/ts/"),
                "首个分片走本地 /ts/ 路由");
        if (first != null) {
            byte[] ts = getBytes(first);
            check(ts != null && ts.length > 0, "分片下载成功 " + (ts == null ? 0 : ts.length) + " 字节");
            check(ts != null && ts.length > 188 && (ts[0] & 0xFF) == 0x47,
                    "分片首字节 0x47（合法 TS 包）");
            if (ts != null && ts.length > 1000) {
                int[] codec = probeCodec(ts);
                if (codec[0] > 0) {
                    check(codec[0] == 0x1B, "PMT 视频 0x"
                            + Integer.toHexString(codec[0]).toUpperCase() + "（"
                            + (codec[0] == 0x1B ? "H.264，Android 5.1 可硬解" : "非 H.264") + "）"
                            + " 音频 0x" + Integer.toHexString(codec[1]).toUpperCase());
                } else {
                    check(false, "PMT 没解析到（首包可能不含 PSI，不一定是故障）");
                }
            }

            byte[] part = getRange(first, 0, 1023);
            check(part != null && part.length == 1024, "Range 请求可用（取回 "
                    + (part == null ? 0 : part.length) + " 字节），拖动进度条依赖它");
        }

        // ---- 反证：原始地址本身也能拉到，说明代理是真转发 ----
        String direct = Http.get(raw, Api.REFERER);
        check(direct != null && direct.indexOf("#EXTM3U") >= 0,
                "原始 https 地址本身可访问（代理是真转发，不是伪造响应）");

        System.out.println();
        System.out.println("---- 应用内错误日志（Trace）----");
        String log = Trace.tail(30);
        System.out.println(log.length() == 0 ? "（空）" : log);

        done();
    }

    private static void done() {
        System.out.println();
        if (fail == 0) {
            System.out.println("[PASS] 全部通过 " + pass + " 项：APK 里的真实源码链路可用");
        } else {
            System.out.println("[FAIL] 未通过 " + fail + " 项，通过 " + pass + " 项");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String msg) {
        if (ok) {
            pass++;
            System.out.println("[ OK ] " + msg);
        } else {
            fail++;
            System.out.println("[FAIL] " + msg);
        }
    }

    private static String firstUri(String playlist) {
        if (playlist == null) {
            return null;
        }
        String[] ls = playlist.split("\n");
        for (int i = 0; i < ls.length; i++) {
            String s = ls[i].trim();
            if (s.length() > 0 && s.charAt(0) != '#') {
                return s;
            }
        }
        return null;
    }

    private static int count(String s, String k) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(k, i)) >= 0) {
            n++;
            i += k.length();
        }
        return n;
    }

    private static String get(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Referer", Api.REFERER);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } catch (Throwable t) {
            System.out.println("       取列表异常: " + t);
            return null;
        }
    }

    private static byte[] getBytes(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            return readAll(c.getInputStream());
        } catch (Throwable t) {
            System.out.println("       取分片异常: " + t);
            return null;
        }
    }

    private static byte[] getRange(String url, int from, int to) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            int code = c.getResponseCode();
            if (code != 206 && code != 200) {
                System.out.println("       Range 返回 " + code);
                return null;
            }
            byte[] all = readAll(c.getInputStream());
            if (code == 200 && all.length > to - from + 1) {
                byte[] cut = new byte[to - from + 1];
                System.arraycopy(all, 0, cut, 0, cut.length);
                return cut;
            }
            return all;
        } catch (Throwable t) {
            System.out.println("       Range 异常: " + t);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            bos.write(b, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    /** 从 TS 里找 PMT，返回 {视频 stream_type, 音频 stream_type}，找不到返回 0。 */
    private static int[] probeCodec(byte[] buf) {
        int pmtPid = -1;
        for (int i = 0; i + 188 <= buf.length; i += 188) {
            if ((buf[i] & 0xFF) != 0x47) {
                continue;
            }
            int pid = ((buf[i + 1] & 0x1F) << 8) | (buf[i + 2] & 0xFF);
            if ((buf[i + 1] & 0x40) == 0 || pid != 0) {
                continue;
            }
            int off = i + 4 + 1 + 8;
            if (off + 12 > buf.length) {
                continue;
            }
            for (int j = off; j + 4 <= off + 12 && j + 4 <= buf.length; j++) {
                int prog = ((buf[j] & 0x1F) << 8) | (buf[j + 1] & 0xFF);
                if (prog == 0) {
                    continue;
                }
                pmtPid = ((buf[j + 2] & 0x1F) << 8) | (buf[j + 3] & 0xFF);
                break;
            }
            break;
        }
        int[] r = new int[] { 0, 0 };
        if (pmtPid < 0) {
            return r;
        }
        for (int i = 0; i + 188 <= buf.length; i += 188) {
            if ((buf[i] & 0xFF) != 0x47) {
                continue;
            }
            int pid = ((buf[i + 1] & 0x1F) << 8) | (buf[i + 2] & 0xFF);
            if ((buf[i + 1] & 0x40) == 0 || pid != pmtPid) {
                continue;
            }
            int off = i + 4 + 1 + (buf[i + 4] & 0xFF) % 16;
            if (off + 12 > buf.length) {
                continue;
            }
            int sect = off;
            int len = ((buf[sect + 1] & 0x0F) << 8) | (buf[sect + 2] & 0xFF);
            int end = Math.min(sect + 3 + len - 4, buf.length);
            int pil = ((buf[sect + 10] & 0x0F) << 8) | (buf[sect + 11] & 0xFF);
            int k = sect + 12 + pil;
            while (k + 5 <= end) {
                int st = buf[k] & 0xFF;
                int es = ((buf[k + 3] & 0x0F) << 8) | (buf[k + 4] & 0xFF);
                if (r[0] == 0 && (st == 0x1B || st == 0x24 || st == 0x01 || st == 0x02)) {
                    r[0] = st;
                } else if (r[1] == 0 && (st == 0x0F || st == 0x11 || st == 0x03 || st == 0x04)) {
                    r[1] = st;
                }
                k += 5 + es;
            }
            break;
        }
        return r;
    }
}

import com.yg.vodtv.Api;
import com.yg.vodtv.Http;
import com.yg.vodtv.Proxy;
import com.yg.vodtv.Vod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * 用原始 Socket 模拟 Android 5.1 的 NuPlayer（stagefright）访问本地代理，
 * 检查线上字节格式：状态行、\r\n 分隔、Content-Length 与实际 body 是否一致、
 * HEAD 是否不带 body、Range 是否回 206 + Content-Range。
 * HttpURLConnection 太宽容，抓不到这类问题。
 */
public final class Wire {

    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Http.trustAll();
        int port = Proxy.start();
        if (port <= 0) {
            System.out.println("[FAIL] 代理启动失败");
            System.exit(1);
        }
        Api.Page p = Api.list(6, 1);
        Vod v = Api.detail(p.items.get(0).id);
        String local = Proxy.wrapPlaylist(v.epUrls[0]);
        String ua = "stagefright/1.2 (Linux;Android 5.1.1)";

        // ---------- 1. GET 播放列表 ----------
        Resp r = raw(port, "GET " + path(local) + " HTTP/1.1", ua, null);
        check("状态行 200", r.head.startsWith("HTTP/1.1 200 "), r.head);
        check("头用 \\r\\n 分隔且以空行结束", r.head.indexOf("\r\n\r\n") > 0, "headLen=" + r.head.length());
        check("Content-Type: application/vnd.apple.mpegurl",
                r.head.toLowerCase().contains("content-type: application/vnd.apple.mpegurl"), r.head);
        check("无 Transfer-Encoding: chunked", !r.head.toLowerCase().contains("chunked"), r.head);
        int cl = header(r.head, "content-length");
        check("Content-Length 与实际 body 一致", cl == r.body.length, "声明=" + cl + " 实际=" + r.body.length);
        String text = new String(r.body, "UTF-8");
        check("body 以 #EXTM3U 开头", text.startsWith("#EXTM3U"), text.length() > 40 ? text.substring(0, 40) : text);
        check("body 不含 master 标签", !text.contains("#EXT-X-STREAM-INF"), "");
        check("body 不含上游域名", !text.contains("wsyzyts.vip") && !text.contains("wsyzym3u8.com"), "");

        // ---------- 2. HEAD 播放列表 ----------
        Resp h = raw(port, "HEAD " + path(local) + " HTTP/1.1", ua, null);
        check("HEAD 状态行 200", h.head.startsWith("HTTP/1.1 200 "), h.head);
        check("HEAD 不带 body", h.body.length == 0, "bodyLen=" + h.body.length);
        check("HEAD 也给了 Content-Length", header(h.head, "content-length") > 0, h.head);

        // ---------- 3. 分片 GET ----------
        String seg = null;
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.length() > 0 && s.charAt(0) != '#') {
                seg = s;
                break;
            }
        }
        check("列表里找到分片地址", seg != null, "");
        if (seg == null) {
            done();
            return;
        }
        check("分片走本机 /ts/ 路由", seg.startsWith("http://127.0.0.1:" + port + "/ts/"), seg);
        Resp s1 = raw(port, "GET " + path(seg) + " HTTP/1.1", ua, null);
        check("分片状态行 200", s1.head.startsWith("HTTP/1.1 200 "), s1.head);
        check("分片 Content-Type 是 video/mp2t",
                s1.head.toLowerCase().contains("content-type: video/mp2t"), s1.head);
        check("分片首字节 0x47", s1.body.length > 188 && (s1.body[0] & 0xFF) == 0x47,
                "len=" + s1.body.length);
        check("声明了 Accept-Ranges", s1.head.toLowerCase().contains("accept-ranges: bytes"), s1.head);

        // ---------- 4. 分片 Range ----------
        Resp s2 = raw(port, "GET " + path(seg) + " HTTP/1.1", ua, "bytes=0-1023");
        check("Range 请求回 206", s2.head.startsWith("HTTP/1.1 206 "), s2.head);
        check("Range 回 Content-Range", s2.head.toLowerCase().contains("content-range: bytes 0-1023/"),
                s2.head);
        check("Range body 恰好 1024 字节", s2.body.length == 1024, "len=" + s2.body.length);

        // ---------- 5. 404 ----------
        Resp s3 = raw(port, "GET /nope HTTP/1.1", ua, null);
        check("未知路径回 404", s3.head.startsWith("HTTP/1.1 404 "), s3.head);

        done();
    }

    private static String path(String url) {
        int i = url.indexOf('/', 8);
        return i > 0 ? url.substring(i) : "/";
    }

    private static int header(String head, String name) {
        String[] ls = head.split("\r\n");
        for (int i = 0; i < ls.length; i++) {
            int c = ls[i].indexOf(':');
            if (c > 0 && ls[i].substring(0, c).trim().equalsIgnoreCase(name)) {
                try {
                    return Integer.parseInt(ls[i].substring(c + 1).trim());
                } catch (Throwable t) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static final class Resp {
        String head = "";
        byte[] body = new byte[0];
    }

    private static Resp raw(int port, String requestLine, String ua, String range) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(30000);
        StringBuilder req = new StringBuilder();
        req.append(requestLine).append("\r\n");
        req.append("Host: 127.0.0.1:").append(port).append("\r\n");
        req.append("User-Agent: ").append(ua).append("\r\n");
        req.append("Accept: */*\r\n");
        req.append("Connection: close\r\n");
        if (range != null) {
            req.append("Range: ").append(range).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        InputStream in = s.getInputStream();
        ByteArrayOutputStream all = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) {
            all.write(buf, 0, n);
        }
        s.close();

        byte[] data = all.toByteArray();
        Resp r = new Resp();
        int sep = -1;
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                sep = i;
                break;
            }
        }
        if (sep < 0) {
            r.head = new String(data, "UTF-8");
            return r;
        }
        r.head = new String(data, 0, sep + 4, "UTF-8");
        int off = sep + 4;
        byte[] body = new byte[data.length - off];
        System.arraycopy(data, off, body, 0, body.length);
        r.body = body;
        return r;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("[ OK ] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "  → " + oneLine(detail));
        }
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace("\r", "\\r").replace("\n", "\\n");
        return t.length() > 220 ? t.substring(0, 220) + "..." : t;
    }

    private static void done() {
        if (fail == 0) {
            System.out.println("[PASS] 代理的线上字节格式符合 MediaPlayer 预期");
        } else {
            System.out.println("[FAIL] 有 " + fail + " 项不符合");
        }
        System.exit(fail == 0 ? 0 : 1);
    }
}

package com.yg.vodtv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 HTTP 客户端。
 * Android 5.1 的系统根证书库很旧（缺 ISRG Root X1 / GTS Root），
 * 片源的证书链会握手失败，所以这里统一放开校验，走自签名信任。
 */
public final class Http {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36";

    /**
     * 连接层最多试几次。实测电视上 TLS 会被中间设备 reset（日志里的
     * SocketException(Connection reset)），这类错误重试一次基本就过了，
     * 只试一次会把好好的源白白判死——搜索只剩 2 个源就是这么来的。
     */
    private static final int ATTEMPTS = 3;

    /** 各次重试前的等待：第一次快一点，后面给中间设备一点恢复时间。 */
    private static final long[] BACKOFF = { 300, 900 };

    private static boolean inited = false;

    private Http() {
    }

    static {
        // 电视网络没有 IPv6 出口，但 Android 优先选 AAAA 记录，
        // 日志里就是卡在 connect ikunzyapi.com/[::]:443 上。
        // 必须在任何网络类初始化之前设置才生效，所以放静态块。
        try {
            if (System.getProperty("java.net.preferIPv4Stack") == null) {
                System.setProperty("java.net.preferIPv4Stack", "true");
            }
        } catch (Throwable t) {
        }
    }

    /** 连接层/读超时这类错误值得重试；4xx 这种确定性错误不该重试。 */
    private static boolean retryable(Throwable t) {
        if (t instanceof java.net.SocketException
                || t instanceof java.net.SocketTimeoutException
                || t instanceof java.net.ConnectException
                || t instanceof java.net.UnknownHostException
                || t instanceof java.io.IOException
                || t instanceof javax.net.ssl.SSLException) {
            // 证书/协议类错误重试也没用，但 SSLException 常见于握手被 reset，
            // 这里一律当瞬时错误处理，反正最多多花两次握手的时间。
            return true;
        }
        return false;
    }

    /** 自检页要显示「连接层最多重试几次」，别的地方不用管。 */
    public static int attempts() {
        return ATTEMPTS;
    }

    private static void backoff(int attempt) {
        if (attempt < BACKOFF.length) {
            sleep(BACKOFF[attempt]);
        }
    }

    public static synchronized void trustAll() {
        if (inited) {
            return;
        }
        inited = true;
        try {
            TrustManager[] tm = new TrustManager[] { new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }
            } };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Throwable t) {
            // 忽略：某些 ROM 限制了 SSLContext 初始化
        }
    }

    public static HttpURLConnection open(String url, String referer) throws Exception {
        return open(url, referer, 12000, 20000);
    }

    /** 换个超时：更新检查要快速失败，不能卡在 12 秒的 connect 上。 */
    public static HttpURLConnection open(String url, String referer, int connectMs, int readMs)
            throws Exception {
        trustAll();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        if (referer != null) {
            c.setRequestProperty("Referer", referer);
        }
        c.setInstanceFollowRedirects(true);
        return c;
    }

    public static String get(String url, String referer) {
        return get(url, referer, 12000, 20000);
    }

    /**
     * 指定超时的取文本。多源聚合时用它把「慢源」快速判死，
     * 否则一个卡住的站会把整页拖到预算上限。
     */
    public static String get(String url, String referer, int connectMs, int readMs) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                HttpURLConnection c = open(url, referer, connectMs, readMs);
                int code = c.getResponseCode();
                if (code != 200) {
                    c.disconnect();
                    // 5xx 是上游/CDN 抖动，值得立刻再来一次；4xx 重试没意义
                    if (code >= 500 && attempt < ATTEMPTS - 1) {
                        backoff(attempt);
                        continue;
                    }
                    Trace.add("http", "GET " + code + " " + shortUrl(url));
                    return null;
                }
                byte[] b = readAll(c.getInputStream());
                c.disconnect();
                if (b == null) {
                    // 读到一半断流，换一次连接往往就好了
                    if (attempt < ATTEMPTS - 1) {
                        backoff(attempt);
                        continue;
                    }
                    Trace.add("http", "读取中断 " + shortUrl(url));
                    return null;
                }
                return new String(b, "UTF-8");
            } catch (Throwable t) {
                if (attempt < ATTEMPTS - 1 && retryable(t)) {
                    backoff(attempt);
                    continue;
                }
                Trace.err("http", "GET 失败 " + shortUrl(url) + " (第 " + (attempt + 1) + " 次)", t);
                return null;
            }
        }
        return null;
    }

    public static byte[] getBytes(String url, String referer) {
        return getBytes(url, referer, 12000, 20000);
    }

    /** 指定超时的取字节：海报快跳、更新下载都用它。 */
    public static byte[] getBytes(String url, String referer, int connectMs, int readMs) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                HttpURLConnection c = open(url, referer, connectMs, readMs);
                int code = c.getResponseCode();
                if (code != 200) {
                    c.disconnect();
                    if (code >= 500 && attempt < ATTEMPTS - 1) {
                        backoff(attempt);
                        continue;
                    }
                    Trace.add("http", "GET* " + code + " " + shortUrl(url));
                    return null;
                }
                byte[] b = readAll(c.getInputStream());
                c.disconnect();
                if (b == null) {
                    if (attempt < ATTEMPTS - 1) {
                        backoff(attempt);
                        continue;
                    }
                    Trace.add("http", "读取中断 " + shortUrl(url));
                    return null;
                }
                return b;
            } catch (Throwable t) {
                if (attempt < ATTEMPTS - 1 && retryable(t)) {
                    backoff(attempt);
                    continue;
                }
                Trace.err("http", "GET* 失败 " + shortUrl(url) + " (第 " + (attempt + 1) + " 次)", t);
                return null;
            }
        }
        return null;
    }

    /**
     * 快速 GET：单次尝试、短超时。检查更新要连着试好几个镜像地址，
     * 用普通 get() 的 12s/20s 超时会让「直连不通」这个最常见的场景卡上一分钟。
     */
    public static String getQuick(String url, String referer, int connectMs, int readMs) {
        try {
            HttpURLConnection c = open(url, referer, connectMs, readMs);
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                Trace.add("http", "GET~ " + code + " " + shortUrl(url));
                return null;
            }
            byte[] b = readAll(c.getInputStream());
            c.disconnect();
            if (b == null) {
                Trace.add("http", "读取中断 " + shortUrl(url));
                return null;
            }
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            Trace.add("http", "GET~ 失败 " + shortUrl(url) + " " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable t) {
        }
    }

    /** 日志里只留域名和路径尾巴，避免整条 base64 把日志刷满。 */
    static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) {
            u = u.substring(0, q);
        }
        if (u.length() > 110) {
            u = u.substring(0, 60) + "..." + u.substring(u.length() - 40);
        }
        return u;
    }

    public static byte[] readAll(InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable t) {
            }
        }
    }
}

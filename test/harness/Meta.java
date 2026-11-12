import com.yg.vodtv.Api;
import com.yg.vodtv.Http;
import com.yg.vodtv.Vod;

import java.util.List;

/**
 * 验证两件事（对应老板反馈的「全是黑框、没有刮削」）：
 * 1) 刮削字段（导演/主演/地区/语言/简介）真的从接口读出来了，HTML 也被清干净；
 * 2) 海报地址在 https 与 http 两条通道下都能取到真图片字节。
 *
 * 注意：这里不 import Ui —— Ui 依赖 android.graphics，桌面 JVM 编不过，
 * 所以 toHttps/httpize 在下面按同一规则复刻一份。
 */
public final class Meta {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) {
        Http.trustAll();
        System.out.println("-- 刮削字段 --");

        Api.Page page = Api.list(13, 1);
        List<Vod> list = page.items;
        ok("国产剧第1页有数据 " + list.size() + " 条（共 " + page.pagecount + " 页）", list.size() > 0);

        int withIntro = 0;
        int withActor = 0;
        int withDirector = 0;
        for (int i = 0; i < list.size(); i++) {
            Vod v = list.get(i);
            String in = v.intro();
            if (in != null && in.length() > 20) withIntro++;
            if (v.actor != null && v.actor.length() > 0) withActor++;
            if (v.director != null && v.director.length() > 0) withDirector++;
        }
        ok("有简介的 " + withIntro + "/" + list.size(), withIntro > list.size() / 2);
        ok("有主演的 " + withActor + "/" + list.size(), withActor > 0);
        ok("有导演的 " + withDirector + "/" + list.size(), withDirector > 0);

        Vod first = null;
        for (int i = 0; i < list.size(); i++) {
            String in = list.get(i).intro();
            if (in != null && in.length() > 60) {
                first = list.get(i);
                break;
            }
        }
        ok("找到一条有长简介的样本", first != null);
        if (first != null) {
            String in = first.intro();
            System.out.println("  片名: " + first.name);
            System.out.println("  导演: " + first.director);
            System.out.println("  主演: " + cut(first.actor, 70));
            System.out.println("  地区: " + first.area + "   语言: " + first.lang + "   年份: " + first.year);
            System.out.println("  简介: " + cut(in, 160).replace("\n", " / "));
            ok("简介里没有 HTML 标签", in.indexOf('<') < 0 && in.indexOf('>') < 0);
            ok("简介里没有残留实体",
                    in.indexOf("&nbsp;") < 0 && in.indexOf("&amp;") < 0 && in.indexOf("&lt;") < 0);
        }

        System.out.println();
        System.out.println("-- 海报双通道 --");
        int picOk = 0;
        int picTry = 0;
        for (int i = 0; i < list.size() && picTry < 6; i++) {
            String pic = list.get(i).pic;
            if (pic == null || pic.length() < 10) continue;
            picTry++;
            String up = toHttps(pic);
            byte[] b1 = Http.getBytes(up, Api.REFERER);
            boolean isJpg = isJpeg(b1);
            if (isJpg) picOk++;
            System.out.println("  " + (isJpg ? "[OK ]" : "[BAD]") + " https "
                    + (b1 == null ? "null" : b1.length + "B") + "  " + shortUrl(up));
        }
        ok("https 通道海报可解码 " + picOk + "/" + picTry, picTry > 0 && picOk == picTry);

        // http 兜底通道也抽一条试，万一电视网络必须走 http
        String sample = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).pic != null && list.get(i).pic.length() > 10) {
                sample = list.get(i).pic;
                break;
            }
        }
        if (sample != null) {
            byte[] b2 = Http.getBytes(httpize(sample), Api.REFERER);
            ok("http 兜底通道也能取到 JPEG " + (b2 == null ? "null" : b2.length + "B"), isJpeg(b2));
        }

        System.out.println();
        if (fail == 0) {
            System.out.println("[PASS] 全部通过 " + pass + " 项：刮削字段与海报链路都正常");
        } else {
            System.out.println("[FAIL] 有 " + fail + " 项没通过");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static boolean isJpeg(byte[] b) {
        return b != null && b.length > 1000
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    /** 与 Ui.toHttps 同一规则。 */
    private static String toHttps(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    /** 与 Ui.httpize 同一规则。 */
    private static String httpize(String url) {
        return url.startsWith("https://") ? "http://" + url.substring(8) : url;
    }

    /** 与 Http.shortUrl 同一规则（那边是包内可见，桌面侧只能复刻）。 */
    private static String shortUrl(String url) {
        if (url == null) return "null";
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        if (u.length() <= 90) return u;
        return u.substring(0, 55) + "..." + u.substring(u.length() - 30);
    }

    private static String cut(String s, int n) {
        if (s == null) return "(空)";
        s = s.trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static void ok(String what, boolean good) {
        if (good) {
            pass++;
            System.out.println("[ OK ] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what);
        }
    }
}

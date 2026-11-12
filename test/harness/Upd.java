import com.yg.vodtv.Http;
import com.yg.vodtv.Update;
import com.yg.vodtv.Ver;

import java.lang.reflect.Method;

/**
 * 验证应用内升级的两个关键逻辑：
 * 1) tag 清洗：v1.4 / 1.4 / release-1.4 → 1.4
 * 2) 版本比较：1.10 > 1.9（分段比，不是字符串比）
 * 3) 线上：真实请求 GitHub Release API，看能不能拿到 apk 下载地址。
 *
 * clean/compare/checkSync 是包内的，这里用反射调，避免为了测试改生产代码可见性。
 */
public final class Upd {

    private static int pass = 0;
    private static int fail = 0;
    private static Method clean;
    private static Method compare;
    private static Method checkSync;

    public static void main(String[] a) throws Exception {
        Http.trustAll();

        clean = Update.class.getDeclaredMethod("clean", String.class);
        clean.setAccessible(true);
        compare = Update.class.getDeclaredMethod("compare", String.class, String.class);
        compare.setAccessible(true);
        checkSync = Update.class.getDeclaredMethod("checkSync");
        checkSync.setAccessible(true);

        System.out.println("-- 版本号解析 --");
        eq("v1.4 → 1.4", call(clean, "v1.4"), "1.4");
        eq("V1.4 → 1.4", call(clean, "V1.4"), "1.4");
        eq("1.4 → 1.4", call(clean, "1.4"), "1.4");
        eq("release-1.4 → (空)", call(clean, "release-1.4"), "");
        eq("v1.4-beta → 1.4", call(clean, "v1.4-beta"), "1.4");

        System.out.println();
        System.out.println("-- 版本比较 --");
        ok("1.4 > 1.3", cmp("1.4", "1.3") > 0);
        ok("1.10 > 1.9（分段比）", cmp("1.10", "1.9") > 0);
        ok("1.3 < 1.4", cmp("1.3", "1.4") < 0);
        ok("1.4 == 1.4", cmp("1.4", "1.4") == 0);
        ok("1.4.1 > 1.4", cmp("1.4.1", "1.4") > 0);

        System.out.println();
        System.out.println("-- 线上检查 GitHub Release --");
        System.out.println("  当前版本 Ver.NAME = " + Ver.NAME
                + "   仓库 " + Update.OWNER + "/" + Update.REPO);
        Object info = checkSync.invoke(null);
        boolean okFlag = (Boolean) field(info, "ok");
        String ver = (String) field(info, "version");
        String apkUrl = (String) field(info, "apkUrl");
        long size = (Long) field(info, "size");
        String err = (String) field(info, "error");
        if (okFlag) {
            System.out.println("  最新 tag = " + (ver.length() > 0 ? ver : "（与本地同版或更旧）"));
            System.out.println("  apk = " + apkUrl);
            System.out.println("  大小 = " + size + "B");
        } else {
            System.out.println("  检查失败：" + err);
        }
        ok("能连上 GitHub 并解析 Release（仓库未建时会失败，属预期）", okFlag);

        System.out.println();
        if (fail == 0) {
            System.out.println("[PASS] 全部通过 " + pass + " 项");
        } else {
            System.out.println("[FAIL] 有 " + fail + " 项没通过");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static Object call(Method m, String s) throws Exception {
        return m.invoke(null, s);
    }

    private static int cmp(String x, String y) throws Exception {
        return (Integer) compare.invoke(null, x, y);
    }

    private static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void eq(String what, Object got, Object want) {
        boolean good = want.equals(got);
        if (good) {
            pass++;
            System.out.println("[ OK ] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + "  实际=" + got);
        }
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

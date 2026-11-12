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
        eq("release-1.4 → 1.4（跳过前缀）", call(clean, "release-1.4"), "1.4");
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
        ok("挑的是纯 ASCII 那个 asset（/YGTV.apk）", apkUrl.endsWith("/YGTV.apk"));

        System.out.println();
        System.out.println("-- 镜像兜底（国内直连 api.github.com 经常不通）--");
        java.lang.reflect.Field pf = Update.class.getDeclaredField("API_PROXIES");
        pf.setAccessible(true);
        String[] api = (String[]) pf.get(null);
        java.lang.reflect.Field df = Update.class.getDeclaredField("DL_PROXIES");
        df.setAccessible(true);
        String[] dl = (String[]) df.get(null);
        System.out.println("  元信息：" + api[0] + " → 直连");
        System.out.println("  下载  ：" + dl[0] + " → " + dl[1] + " → 直连");
        // 镜像必须排在直连前面：国内电视上 api.github.com 直连是 SSL 握手就失败，
        // 先试直连等于白等一个超时。
        ok("元信息镜像优先、直连垫底", api.length == 2 && api[0].contains("gh-proxy.com")
                && api[1].length() == 0);
        ok("下载三条通道（gh-proxy → ghfast → 直连）", dl.length == 3
                && dl[0].contains("gh-proxy.com") && dl[1].contains("ghfast.top")
                && dl[2].length() == 0);
        String latest = "https://api.github.com/repos/" + Update.OWNER + "/" + Update.REPO
                + "/releases/latest";
        for (int i = 0; i < api.length; i++) {
            if (api[i].length() == 0) {
                System.out.println("  直连 取 Release 元信息 → 跳过（国内基本不通，App 里也只当兜底）");
                continue;
            }
            String via = Http.getQuick(api[i] + latest, null, 8000, 10000);
            boolean has = via != null && via.indexOf("\"tag_name\"") >= 0;
            System.out.println("  " + api[i] + " 取 Release 元信息 → "
                    + (has ? "成功 " + via.length() + "B" : "失败"));
            ok("镜像 " + api[i] + " 能取到 Release 元信息（镜像挂了这条会失败）", has);
        }

        System.out.println();
        System.out.println("-- 真下载一次（和 App 里点「下载并安装」同一条路径）--");
        android.content.Context ctx = new android.content.Context();
        java.io.File got = Update.download(ctx, cast(info), null);
        ok("下载成功", got != null);
        if (got != null) {
            System.out.println("  文件 = " + got.getAbsolutePath());
            System.out.println("  大小 = " + got.length() + "B");
            ok("文件大小与 Release 声明一致", got.length() == size);
            ok("落在外部存储（系统安装器读得到）",
                    got.getAbsolutePath().replace('\\', '/').contains("/build/sdcard/"));
            ok("是合法 zip（APK 本质是 zip）", isZip(got));
        }

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

    private static Update.Info cast(Object o) {
        return (Update.Info) o;
    }

    private static boolean isZip(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[4];
            if (in.read(h) != 4) {
                return false;
            }
            return (h[0] & 0xFF) == 'P' && (h[1] & 0xFF) == 'K';
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable t) {
            }
        }
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

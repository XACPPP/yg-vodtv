import com.yg.vodtv.Src;

import java.util.List;

/**
 * 源表热更（v2.4）的离线验证：`Src.load(jsonText)` 的六层容错和频道映射。
 *
 * 这个套件不联网 —— 它喂的是手写 JSON，专测「远端表把 App 弄坏」的每一种写法。
 * 真正的联网打分由 `test/source_scan.py` 负责，两者分工不重叠。
 *
 * 跑法：bash test/harness/run.sh srctab
 */
public final class SrcTab {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("  [OK] " + what);
        } else {
            fail++;
            System.out.println("  [!!] " + what);
        }
    }

    private static String key(int ch, List<Src> list, String key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key.equals(key)) {
                return String.valueOf(list.get(i).type(ch));
            }
        }
        return "missing";
    }

    public static void main(String[] a) {
        int builtin = Src.builtinCount();
        System.out.println("[srctab] 内置源 " + builtin + " 个");
        check(builtin == 14, "内置表 14 个源（BUILTIN 没被改坏）");
        check(Src.count() == builtin, "启动时当前生效表 = 内置表");

        // ---- 1. 正常替换
        String one = "{\"version\":1,\"sources\":["
                + "{\"key\":\"ts1\",\"api\":\"https://a.example/api.php/provide/vod/\","
                + "\"referer\":\"https://a.example/\",\"types\":\"11,12,13\"}]}";
        Src.load(one);
        List<Src> L = Src.all();
        check(Src.count() == 1 && L.get(0).key.equals("ts1"), "单源表替换成功");
        check("11".equals(key(Src.CH_MOVIE, L, "ts1")), "types 逗号串 → CH_MOVIE=11");
        check("12".equals(key(Src.CH_TV, L, "ts1")), "types 逗号串 → CH_TV=12");
        check("13".equals(key(Src.CH_VARIETY, L, "ts1")), "types 逗号串 → CH_VARIETY=13");
        check(L.get(0).type(Src.CH_SUSPENSE) == 0, "没写到的频道 = 0");
        check(L.get(0).has(Src.CH_REC), "推荐频道任何源都支持");

        // ---- 2. types 写成 JSON 数组也要认
        Src.load("{\"sources\":[{\"key\":\"ts2\",\"api\":\"https://b.example/v/\","
                + "\"types\":[21,22,23]}]}");
        L = Src.all();
        check(Src.count() == 1 && "21".equals(key(Src.CH_MOVIE, L, "ts2")),
                "types JSON 数组形态也能解析");

        // ---- 3. types 缺失 → 补 0，源不能整条消失
        Src.load("{\"sources\":[{\"key\":\"ts3\",\"api\":\"https://c.example/v/\"}]}");
        L = Src.all();
        check(Src.count() == 1 && L.get(0).key.equals("ts3"), "types 缺失的源仍然保留");
        check(L.get(0).type(Src.CH_MOVIE) == 0 && L.get(0).has(Src.CH_REC),
                "types 缺失 → 只有推荐频道");

        // ---- 4. 坏项只丢这一条
        Src.load("{\"sources\":["
                + "{\"key\":\"good\",\"api\":\"https://d.example/v/\",\"types\":\"31\"},"
                + "{\"key\":\"\",\"api\":\"https://e.example/v/\"},"
                + "{\"key\":\"noscheme\",\"api\":\"e.example/v/\"}]}");
        L = Src.all();
        check(Src.count() == 1, "两个坏项被丢掉，只剩 1 个");
        if (Src.count() == 1) {
            check(L.get(0).key.equals("good"), "留下的是那条合法的");
        }

        // ---- 5. 六层容错：每一种「远端出错」都不能把表弄空
        int before = Src.count();
        Src.load(null);
        check(Src.count() == before, "load(null) 不改变源表");
        Src.load("   ");
        check(Src.count() == before, "load(空白) 不改变源表");
        Src.load("{\"sources\":");
        check(Src.count() == before, "半截 JSON 不改变源表");
        Src.load("[]");
        check(Src.count() == before, "根是数组不是对象，不改变源表");
        Src.load("{\"sources\":[]}");
        check(Src.count() == before, "空数组不改变源表");
        Src.load("{\"sources\":[{\"key\":\"x\",\"api\":\"\"}]}");
        check(Src.count() == before, "全是坏项 → 整体放弃，保留上一版");

        // ---- 6. 远端表能真的回到内置（拿内置表内容重喂一遍）
        StringBuilder sb = new StringBuilder("{\"sources\":[");
        List<Src> cur = Src.all();
        for (int i = 0; i < cur.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"key\":\"").append(cur.get(i).key)
              .append("\",\"api\":\"").append(cur.get(i).api)
              .append("\",\"referer\":\"").append(cur.get(i).referer)
              .append("\"}");
        }
        sb.append("]}");
        long seq = Src.loadSeq();
        Src.load(sb.toString());
        check(Src.loadSeq() == seq + 1, "loadSeq 自增（App 靠它判断源表换过没）");
        check(Src.count() == before, "回写自身不会丢源");

        // ---- 7. all() 必须是副本，否则 Multi 边遍历边换表会炸
        List<Src> s1 = Src.all();
        List<Src> s2 = Src.all();
        check(s1 != s2, "all() 每次返回新副本（并发安全）");
        check(s1.size() == s2.size(), "两次快照内容一致");

        System.out.println();
        if (fail == 0) {
            System.out.println("[PASS] 全部通过 " + pass + " 项：源表热更的容错与频道映射都正常");
        } else {
            System.out.println("[FAIL] 未通过 " + fail + " 项，通过 " + pass + " 项");
        }
        if (fail > 0) {
            System.exit(1);
        }
    }
}

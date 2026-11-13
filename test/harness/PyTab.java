import com.yg.vodtv.PyIndex;

import java.util.List;

/**
 * 拼音首字母索引的离线断言（不联网、不读盘）。
 *
 * 只测 {@link PyIndex#pick(String, String, int)} 这一个纯函数 —— 桶的下载与缓存
 * 是 IO，真机才能验；而「给定一份桶内容，能不能挑出正确的中文片名」是纯逻辑，
 * 也是整条首字母搜索最容易出错的地方（前缀误匹配、制表符解析、去重、上限）。
 */
public final class PyTab {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // 真实的桶片段（w.txt 里 wddcz 那两条，顺序就是文件里的行序）
        String w = "w\t3000万\n"
                + "w\t吻\n"
                + "wd\t我的战争\n"
                + "wddcz\t伟大的长征\n"
                + "wddcz\t我的独裁者\n"
                + "wdz\t我的团长我的团\n";

        List<String> a = PyIndex.pick(w, "wddcz", 8);
        eq("wddcz 命中两条", 2, a.size());
        eq("wddcz 第一条是正主", "伟大的长征", a.get(0));
        eq("wddcz 第二条", "我的独裁者", a.get(1));

        // 前缀匹配天然包含更长的 key：wd 会把 wddcz / wdz 都捞进来（这是对的，
        // 用户少打几个字母时指望的是「所有以这串开头的都算」）
        List<String> b = PyIndex.pick(w, "wd", 8);
        eq("wd 命中四行（wd / wdz / 两条 wddcz）", 4, b.size());
        eq("wd 按文件行序返回", "我的战争", b.get(0));

        // 只输入一个字母：先出 key 恰好是「w」的，再出更长的（两轮扫描的意义所在）
        List<String> c = PyIndex.pick(w, "w", 8);
        eq("w 命中 6 行", 6, c.size());
        eq("w 的 head 是精确 key", "3000万", c.get(0));

        // 上限生效
        List<String> d = PyIndex.pick(w, "w", 3);
        eq("w 限 3 条", 3, d.size());

        // 大小写与空白
        eq("大写输入也能命中", 2, PyIndex.pick(w, " WDDCZ ", 8).size());

        // 精确 key 优先的两轮扫描：打全缩写时，wddcz 的两条必须先于更长的 key 出来。
        List<String> h = PyIndex.pick(w, "wdd", 8);
        eq("wdd 命中 wddcz 那两条", 2, h.size());
        eq("wdd 首要命中是正主", "伟大的长征", h.get(0));

        // 脏行不炸：没有制表符 / 制表符在末尾 / 空行 / null
        String dirty = "wddcz\n"
                + "wddcz\t\n"
                + "\n"
                + "wddcz\t伟大的长征\n"
                + "xddcz\t不该出现\n";
        List<String> e = PyIndex.pick(dirty, "wddcz", 8);
        eq("脏行被跳过，只留合法那条", 1, e.size());
        eq("脏行里的正主仍在", "伟大的长征", e.get(0));

        // 同名片名只留一条（索引理论上已去重，这里防双重保险）
        List<String> f = PyIndex.pick("wddcz\t伟大的长征\nwddcz\t伟大的长征\n", "wddcz", 8);
        eq("重复片名去重", 1, f.size());

        // 边界输入
        eq("null 内容返回空", 0, PyIndex.pick(null, "wddcz", 8).size());
        eq("null 前缀返回空", 0, PyIndex.pick(w, null, 8).size());
        eq("空前缀返回空", 0, PyIndex.pick(w, "", 8).size());
        eq("纯空白前缀返回空", 0, PyIndex.pick(w, "   ", 8).size());
        eq("查不到返回空", 0, PyIndex.pick(w, "zzzz", 8).size());
        eq("空内容返回空", 0, PyIndex.pick("", "wddcz", 8).size());
        eq("cap=0 用默认上限", 2, PyIndex.pick(w, "wddcz", 0).size());

        // 文件末尾没有换行也要能读到最后一行
        eq("无结尾换行", 1, PyIndex.pick("wddcz\t伟大的长征", "wddcz", 8).size());

        // CRLF（万一手滑用 Windows 编辑器改了索引文件）
        List<String> g = PyIndex.pick("wddcz\t伟大的长征\r\nwd\t我的战争\r\n", "wddcz", 8);
        eq("CRLF 行尾仍能命中", 1, g.size());
        // 名字末尾带 \r 会让「名字 == 预期」永远匹配不上，这里允许被 trim 掉
        eq("CRLF 片名已 trim", "伟大的长征", g.get(0));

        System.out.println(fail == 0
                ? "[PASS] 全部通过 " + pass + " 项：首字母索引的匹配规则正确"
                : "[FAIL] " + fail + " 项不通过（通过 " + pass + "）");
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void eq(String what, int want, int got) {
        if (want == got) {
            pass++;
        } else {
            fail++;
            System.out.println("  [X] " + what + "：期望 " + want + "，实际 " + got);
        }
    }

    private static void eq(String what, String want, String got) {
        if (want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("  [X] " + what + "：期望「" + want + "」，实际「" + got + "」");
        }
    }
}

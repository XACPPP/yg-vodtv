package com.yg.vodtv;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集源定义：每个站一套苹果CMS接口 + 「统一频道 → 该站 type_id」映射表。
 *
 * 各站的 type_id 编号天差地别（电影在 wsyzy 是 1，在 bfzy 是 20），
 * 所以App 内部只认下面的 CH_xxx 频道号，取数据时按源查表；查不到（=0）就跳过这个源。
 */
public final class Src {

    /* ===== 统一频道（App 内部唯一口径） ===== */
    public static final int CH_REC = 0;       // 推荐：不带 t，全站最新
    public static final int CH_MOVIE = 1;
    public static final int CH_TV = 2;
    public static final int CH_VARIETY = 3;
    public static final int CH_ANIME = 4;
    public static final int CH_CNTV = 5;      // 国产剧
    public static final int CH_WESTTV = 6;    // 欧美剧
    public static final int CH_KOREA = 7;
    public static final int CH_JAPAN = 8;
    public static final int CH_HK = 9;        // 港剧/港台剧
    public static final int CH_TW = 10;
    public static final int CH_THAI = 11;
    public static final int CH_ACTION = 12;
    public static final int CH_COMEDY = 13;
    public static final int CH_ROMANCE = 14;
    public static final int CH_SCIFI = 15;
    public static final int CH_HORROR = 16;
    public static final int CH_DRAMA = 17;
    public static final int CH_WAR = 18;
    public static final int CH_DOC = 19;
    public static final int CH_CNVAR = 20;
    public static final int CH_JPVAR = 21;
    public static final int CH_HKVAR = 22;
    public static final int CH_WESTVAR = 23;
    public static final int CH_CNANIME = 24;
    public static final int CH_JPANIME = 25;
    public static final int CH_WESTANIME = 26;
    public static final int CH_SHORT = 27;    // 短剧
    public static final int CH_4K = 28;
    public static final int CH_SUSPENSE = 29;

    public static final int CH_COUNT = 30;

    /**
     * 本文件格式：key|接口前缀|Referer|频道1..29 的 type_id（0 表示该源没有）。
     *
     * 这里的内容一个字都没改，只是从 SPEC 改名成 BUILTIN：它现在是「内置兜底表」。
     * 远端 sources.json 拉不到时（离线、GitHub 不可达、JSON 写坏、回包被中间设备替换）
     * 这张表必须还在 APK 里顶上，所以不能删、不能改排序 —— 顺序就是故障时的优先级。
     */
    private static final String[] BUILTIN = {
            // 片库与简介覆盖率最好的排前面
            "maoyan|https://api.maoyanapi.top/api.php/provide/vod/|https://www.maoyan.com/"
                    + "|1,2,3,4,13,16,15,31,14,30,33,6,7,8,9,10,11,12,27,34,36,35,37,38,40,39,46,0,24",
            "ffzy|https://api.ffzyapi.com/api.php/provide/vod/|https://www.ffzyapi.com/"
                    + "|1,2,3,4,13,16,15,22,14,21,24,6,7,8,9,10,11,12,20,25,27,26,28,29,30,31,36,0,0",
            "bfzy|https://bfzyapi.com/api.php/provide/vod/|https://bfzyapi.com/"
                    + "|20,30,45,39,31,32,34,36,33,35,38,21,22,25,24,23,26,27,28,46,48,47,49,40,41,42,58,0,0",
            "modu|https://caiji.moduapi.cc/api.php/provide/vod/|https://caiji.moduapi.cc/"
                    + "|7,8,9,1,26,29,28,31,27,30,33,10,11,12,13,14,15,16,24,34,36,35,37,1,2,3,38,0,21",
            // ikun 主域 ikunzyapi.com 从这台机器握手就断（SSLHandshakeException），
            // 实测 ikunzy.com 可直连，换过来
            "ikun|https://ikunzy.com/api.php/provide/vod/|https://ikunzy.com/"
                    + "|1,2,3,4,23,26,25,28,24,27,30,6,7,8,9,10,11,12,20,31,33,32,34,35,37,36,45,0,17",
            "360zy|https://360zy.com/api.php/provide/vod/|https://360zy.com/"
                    + "|1,2,3,4,13,16,15,31,14,30,33,6,7,8,9,10,11,12,27,34,36,35,37,38,40,39,46,0,24",
            "yhzy|https://yhzy.cc/api.php/provide/vod/|https://yhzy.cc/"
                    + "|1,2,3,4,13,24,16,23,14,15,0,6,7,8,9,10,11,12,21,26,27,28,29,30,31,32,0,0,0",
            // wsyzy 简介覆盖率差，但它的 suggest 是唯一支持拼音搜索的，必须留着
            "wsyzy|https://wsyzy.cc/api.php/provide/vod/|https://wsyzy.cc/"
                    + "|1,2,3,4,13,14,15,16,17,18,19,6,7,8,9,10,11,12,20,25,26,27,28,29,30,31,54,62,68",
            // cmov 慢，放最后兜底
            "cmov|https://www.cmov.cn/api.php/provide/vod/|https://www.cmov.cn/"
                    + "|1,2,3,4,13,16,15,15,14,14,0,6,7,8,9,10,11,12,29,20,22,21,23,24,25,26,0,0,0",
            // ---- v2.0 新增四源：与上面十个源不同库，用来补「热播剧搜不到」的缺口 ----
            "zuidazy|https://api.zuidapi.com/api.php/provide/vod/|https://www.zuidazy.com/"
                    + "|1,2,3,4,13,14,15,16,17,18,19,6,7,8,9,10,11,12,20,25,26,27,28,29,30,31,54,62,68",
            "dytt|https://caiji.dyttzyapi.com/api.php/provide/vod/|https://www.dyttzyapi.com/"
                    + "|1,2,3,4,13,16,15,22,14,21,24,6,7,8,9,10,11,12,20,25,27,26,28,29,30,31,36,0,0",
            "jszyapi|https://jszyapi.com/api.php/provide/vod/|https://jszyapi.com/"
                    + "|2,20,30,24,20,3,5,6,4,28,7,9,11,10,12,13,14,15,16,30,31,32,33,24,25,26,38,0,35",
            "uku|https://api.ukuapi.com/api.php/provide/vod/|https://www.ukuapi.com/"
                    + "|1,2,3,4,13,16,22,15,14,21,23,6,7,8,9,10,11,12,24,3,0,0,0,4,0,0,32,0,34",
            // 片库最大（42.9 万条）的一家，但从 2026-11-12 起一直 503，先留在这里等它恢复；
            // 熔断会把它冷却 3 分钟，不拖累其它源。
            "ysxq|https://www.ysxq.cc/api.php/provide/vod/|https://www.ysxq.cc/"
                    + "|1,2,3,4,13,16,15,15,14,14,29,6,7,8,9,10,11,12,24,30,31,39,32,33,34,35,60,86,23",
    };

    private static final List<Src> ALL = new ArrayList<Src>();

    /**
     * 启动时先把内置表铺满 ALL。任何时候 ALL 都不允许为空 ——
     * Multi / Api / Vod 全程直接遍历它，空表等于整个 App 变砖。
     */
    static {
        fill(BUILTIN, ALL);
    }

    /** 把一批 "key|api|referer|types" 行解析进 target，返回成功条数。 */
    private static int fill(String[] lines, List<Src> target) {
        int n = 0;
        if (lines == null) {
            return 0;
        }
        for (int i = 0; i < lines.length; i++) {
            Src s = parse(lines[i]);
            if (s != null) {
                target.add(s);
                n++;
            }
        }
        return n;
    }

    /** 内置表的源数量，自检页拿它和 count() 对比，判断远端表到底生效没有。 */
    public static int builtinCount() {
        List<Src> tmp = new ArrayList<Src>();
        return fill(BUILTIN, tmp);
    }

    /**
     * 用远端 sources.json 的内容整体替换源表。
     *
     * 容错策略（每一层都是为了让「远端出问题」等价于「什么都没发生」）：
     *   1. 空/null/太短 → 直接 return，ALL 保持内置；
     *   2. JSON 解析异常 → catch 掉 return，ALL 保持内置；
     *   3. 没有 sources 数组或数组为空 → return；
     *   4. 逐项校验（key 非空、api 含 scheme://），坏项只是跳过，不牵连整表；
     *   5. 合法项为 0 → 整体放弃（防止远端写了个空数组把 App 变砖）；
     *   6. 只有真拿到 >=1 个源，才在 synchronized 里 clear + addAll，
     *      中途没人能看见「清空了还没填」的中间态。
     *
     * 注意 hasH：它是 final 字段，由构造函数按 key 派生（cmov 不发 h=），
     * 这里刻意不给它覆盖能力 —— JSON 里就算写了 hasH 也忽略。理由是改 final
     * 语义要动构造函数和全部调用点，而 hasH 只跟 cmov 一家有关，
     * 远端要控制它，直接把 cmov 从表里去掉即可。
     *
     * 只使用 JSONObject(String) / optString / optInt / optJSONArray 与
     * JSONArray.length() / optJSONObject(int) —— test/harness 下的 json 替身
     * 只有这几个方法，Src.java 是要被 harness 编译的。
     */
    public static synchronized void load(String jsonText) {
        if (jsonText == null || jsonText.trim().length() < 20) {
            return;
        }
        List<Src> fresh = new ArrayList<Src>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(jsonText);
            org.json.JSONArray arr = root.optJSONArray("sources");
            if (arr == null || arr.length() == 0) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("key", "").trim();
                String api = o.optString("api", "").trim();
                if (key.length() == 0 || api.length() == 0 || api.indexOf("://") < 0) {
                    // 坏项只丢这一条：远端手滑写错一行，不该让整表作废
                    continue;
                }
                String referer = o.optString("referer", "").trim();
                String types = typesOf(o);
                if (types.length() == 0) {
                    // 给个 0 而不是空串：parse() 里 split(",") 得到 [""] 会让
                    // Integer.parseInt 抛异常，整条源被丢掉。写 0 相当于「只有推荐频道」，
                    // 这种源仍能进搜索和推荐流，比直接消失好。
                    types = "0";
                }
                Src s = parse(key + "|" + api + "|" + referer + "|" + types);
                if (s != null) {
                    fresh.add(s);
                }
            }
        } catch (Throwable t) {
            // 坏 JSON / 替身不支持的字段 / 内存不足，一律当作没拉到
            Trace.err("src", "远端源表解析失败，保留内置", t);
            return;
        }
        if (fresh.isEmpty()) {
            // 最关键的一条：远端给了一份「语法正确但没有可用源」的表，也必须放弃
            Trace.add("src", "远端源表无可用项，保留内置");
            return;
        }
        synchronized (ALL) {
            ALL.clear();
            ALL.addAll(fresh);
        }
        LOAD_SEQ++;
        Trace.add("src", "源表已切换为远端 " + fresh.size() + " 个");
    }

    /**
     * types 字段两种形态都收：JSON 数组 ["1","2"] 和逗号串 "1,2"。
     *
     * 为什么不逐项取数：test/harness 里的 JSONArray 替身只实现了 length() 和
     * optJSONObject(int)，真实的 optInt(int)/optString(int) 在替身里不存在，
     * 一旦写了就过不了 harness 编译。所以这里只调用 optString("types") ——
     * 真 org.json 对数组值会返回它的 toString()（形如 "[1,2,3]"），替身同理，
     * 拿到文本后剔掉所有非数字字符，[1, 2, 3] 和 [1,2,3] 洗出来都是 "1,2,3"。
     */
    private static String typesOf(org.json.JSONObject o) {
        String raw = o.optString("types", "");
        if (raw == null) {
            return "";
        }
        raw = raw.trim();
        if (raw.length() == 0) {
            return "";
        }
        if (raw.charAt(0) != '[') {
            // 本来就是 "1,2,3"，原样返回，连空格都不动（parse 会自己 trim）
            return raw;
        }
        // 数组形态：只留数字和逗号，顺手把 "[1, 2, 3]" 规整成 "1,2,3"
        StringBuilder sb = new StringBuilder();
        int numStart = -1;
        for (int j = 0; j < raw.length(); j++) {
            char ch = raw.charAt(j);
            boolean digit = ch >= '0' && ch <= '9';
            if (digit) {
                if (numStart < 0) {
                    numStart = j;
                }
            } else if (numStart >= 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(raw, numStart, j);
                numStart = -1;
            }
        }
        if (numStart >= 0) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(raw, numStart, raw.length());
        }
        return sb.toString();
    }

    /** 当前生效的源数量，自检页用来判断「远端表到底切过去没有」。 */
    public static synchronized int count() {
        return ALL.size();
    }

    /**
     * 源表版本号：每成功切换一次（load 真的替换了 ALL）就 +1。
     *
     * 为什么需要它：SrcSync 要判断「这次 load 到底生效没有」。光比较数量不可靠 ——
     * 远端表和内置表刚好一样大、或者刚好也是 14 个，数量就没变化；而 load 失败时
     * ALL 原封不动。用一个单调递增的计数器，调用方比对前后值即可精确判断，
     * 不用去猜数量。
     */
    public static synchronized long loadSeq() {
        return LOAD_SEQ;
    }

    private static long LOAD_SEQ;

    public final String key;
    public final String api;
    public final String referer;
    /** 频道 → 该源 type_id，下标即 CH_xxx。 */
    private final int[] map;
    /** cmov 不支持 h= 参数。 */
    public final boolean hasH;

    private Src(String key, String api, String referer, int[] map) {
        this.key = key;
        this.api = api;
        this.referer = referer;
        this.map = map;
        this.hasH = !"cmov".equals(key);
    }

    private static Src parse(String line) {
        try {
            String[] f = line.split("\\|");
            if (f.length < 4) {
                return null;
            }
            int[] m = new int[CH_COUNT];
            String[] nums = f[3].split(",");
            for (int i = 0; i < nums.length && i + 1 < CH_COUNT; i++) {
                m[i + 1] = Integer.parseInt(nums[i].trim());
            }
            return new Src(f[0].trim(), f[1].trim(), f[2].trim(), m);
        } catch (Throwable t) {
            Trace.err("src", "源定义解析失败 " + line, t);
            return null;
        }
    }

    /** 该源在此频道下的 type_id，0 表示没有。 */
    public int type(int ch) {
        if (ch <= CH_REC || ch >= CH_COUNT) {
            return 0;
        }
        return map[ch];
    }

    /** 该源是否提供此频道。推荐频道所有源都支持（不发 t 参数）。 */
    public boolean has(int ch) {
        return ch == CH_REC || type(ch) > 0;
    }

    /**
     * 当前生效的源表快照。
     *
     * 返回副本而不是 ALL 本体：SrcSync 在后台线程拉到远端表时会 clear + addAll，
     * 而 Multi 正在另一个线程按索引遍历。直接暴露本体的话，遍历到一半表被换掉，
     * 轻则少收一个源，重则 ConcurrentModificationException / IndexOutOfBounds。
     * 副本只有 14~30 个引用，一次拷贝的成本可以忽略。
     */
    public static List<Src> all() {
        synchronized (ALL) {
            return new ArrayList<Src>(ALL);
        }
    }

    public static Src byKey(String key) {
        // 同样走快照：这是详情页点击时调的，正好可能撞上后台换表
        List<Src> snap = all();
        if (key != null) {
            for (int i = 0; i < snap.size(); i++) {
                if (snap.get(i).key.equals(key)) {
                    return snap.get(i);
                }
            }
        }
        return snap.isEmpty() ? null : snap.get(0);
    }

    /**
     * 取接口的 scheme://host。
     *
     * 用途：suggest 接口返回的海报是站内相对路径（bfzy 100% 如此），
     * 必须拼上接口自己的域名才能取到图。注意要用 api 的 host 而不是 referer 的，
     * 因为有的源两者不同域（ffzy 的 referer 域名连 TLS 都握不上手）。
     */
    public static String root(String api) {
        if (api == null || api.length() == 0) {
            return "";
        }
        int i = api.indexOf("://");
        if (i < 0) {
            return "";
        }
        int j = api.indexOf('/', i + 3);
        return j > 0 ? api.substring(0, j) : api;
    }

    /** 详情不要，只要一个短名字显示在卡片角上。 */
    public String label() {
        return key;
    }
}

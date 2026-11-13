package com.yg.vodtv;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播源表：组（央视 / 卫视 / …）→ 台（CCTV-1 …）→ 线路（同一个台的多条 m3u8）。
 *
 * 为什么和 Src.java 一样做成「内置表 + 可热更新」：
 *   1. 内置表编进 APK，进程启动瞬间就有东西可播，远端或缓存全坏也不会黑屏；
 *   2. 真正的表放仓库 assets/live.json，由 {@link LiveSync} 拉下来整体替换，
 *      换台、换线路不用改 Java 重新发版（电视上装 APK 要 U 盘 + 遥控器，成本极高）。
 *
 * 线程模型：{@link LiveSync} 在后台线程 load（clear + addAll），UI 线程同时在遍历。
 * 所以对外只给 {@link #groups()} 的快照副本，ALL 本体永远不露出去。
 *
 * 只使用 JSONObject(String) / optString / optJSONArray 与
 * JSONArray.length() / optJSONObject(int) —— test/harness 下的 json 替身只有
 * 这几个方法，本类要被 harness 编译。数组里的字符串元素也不能用 optString(int)，
 * 只能像 Src.typesOf() 那样先把数组当整体读成文本再自己切（见 {@link #urlsOf}）。
 */
public final class Live {

    /** 一个台。urls 至少一条，按顺序尝试。 */
    public static final class Ch {
        public final String name;
        /** 台标，可能为空串（远端没给或给了相对路径）。 */
        public final String logo;
        public final List<String> urls;

        private Ch(String name, String logo, List<String> urls) {
            this.name = name;
            this.logo = logo;
            this.urls = urls;
        }
    }

    /** 一个分组，比如「央视」。 */
    public static final class Group {
        public final String id;
        public final String name;
        public final List<Ch> chs;

        private Group(String id, String name, List<Ch> chs) {
            this.id = id;
            this.name = name;
            this.chs = chs;
        }
    }

    /**
     * 内置兜底表。
     *
     * 这里故意全是 http://127.0.0.1:1/placeholder.m3u8 —— 它是「明显不可用」的
     * 占位地址，作用只是让远端/缓存全挂时 App 还能画出分组和台名，不至于白屏或崩。
     * 真表由远端 live.json 覆盖，所以这里不需要真实线路，也别往里塞会失效的第三方地址。
     * 格式与远端完全一致（version / updated / total / groups）。
     */
    private static final String BUILTIN =
            "{\n"
                    + "  \"version\": 1,\n"
                    + "  \"updated\": \"2026-11-13T00:00:00Z\",\n"
                    + "  \"total\": 17,\n"
                    + "  \"groups\": [\n"
                    + "    {\"id\": \"cctv\", \"name\": \"央视\", \"chs\": [\n"
                    + "      {\"name\": \"CCTV-1 综合\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"CCTV-5 体育\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"CCTV-6 电影\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"CCTV-13 新闻\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]}\n"
                    + "    ]},\n"
                    + "    {\"id\": \"weishi\", \"name\": \"卫视\", \"chs\": [\n"
                    + "      {\"name\": \"湖南卫视\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"浙江卫视\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"江苏卫视\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"东方卫视\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]}\n"
                    + "    ]},\n"
                    + "    {\"id\": \"yingshi\", \"name\": \"影视\", \"chs\": [\n"
                    + "      {\"name\": \"电影频道\", \"logo\": \"\", \"urls\":"
                    + " [\"http://127.0.0.1:1/placeholder.m3u8\","
                    + " \"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"剧集频道\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"经典影院\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]}\n"
                    + "    ]},\n"
                    + "    {\"id\": \"tiyu\", \"name\": \"体育\", \"chs\": [\n"
                    + "      {\"name\": \"体育一台\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"体育二台\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"足球频道\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]}\n"
                    + "    ]},\n"
                    + "    {\"id\": \"shaoer\", \"name\": \"少儿\", \"chs\": [\n"
                    + "      {\"name\": \"少儿动画\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"亲子乐园\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]},\n"
                    + "      {\"name\": \"儿歌天地\", \"logo\": \"\","
                    + " \"urls\": [\"http://127.0.0.1:1/placeholder.m3u8\"]}\n"
                    + "    ]}\n"
                    + "  ]\n"
                    + "}";

    /** 当前生效的组表。任何时候都不允许为空 —— UI 全程直接遍历它。 */
    private static final List<Group> ALL = new ArrayList<Group>();

    /** 内置表的组数 / 台数，编译期就算好，自检页拿它和当前值对比。 */
    private static final int BUILTIN_GROUPS;
    private static final int BUILTIN_CHS;

    /** 每成功替换一次就 +1，调用方比对前后值即可判断 load 到底生效没有。 */
    private static int LOAD_SEQ;

    static {
        List<Group> b = builtinTable();
        synchronized (ALL) {
            ALL.addAll(b);
        }
        BUILTIN_GROUPS = b.size();
        BUILTIN_CHS = chsOf(b);
    }

    private Live() {
    }

    /** 内置表单独抽出来解析：它的字符串写死在源码里，解析失败只能是代码被改坏了。 */
    private static List<Group> builtinTable() {
        try {
            return parse(BUILTIN);
        } catch (Throwable t) {
            Trace.err("live", "内置直播表解析失败", t);
            return new ArrayList<Group>();
        }
    }

    /**
     * 当前源表快照（副本）。
     *
     * 返回副本而不是 ALL 本体：LiveSync 在后台线程 clear + addAll，UI 线程正在遍历。
     * 直接暴露本体的话，遍历到一半表被换掉，轻则少画几个台，重则
     * ConcurrentModificationException / IndexOutOfBounds。副本就几十个引用，成本可忽略。
     */
    public static List<Group> groups() {
        synchronized (ALL) {
            return new ArrayList<Group>(ALL);
        }
    }

    public static int groupCount() {
        synchronized (ALL) {
            return ALL.size();
        }
    }

    /** 全部组的频道总数。 */
    public static int channelCount() {
        return chsOf(groups());
    }

    /** 内置表，编译期兜底。 */
    public static int builtinCount() {
        return BUILTIN_GROUPS;
    }

    /** 内置表的频道总数，给 status() 拼「内置 X 组 Y 台」用。 */
    static int builtinChannelCount() {
        return BUILTIN_CHS;
    }

    /**
     * 用远端 live.json 的内容整体替换源表。
     *
     * 容错六层，每一层都是为了让「远端出问题」等价于「什么都没发生」：
     *   1. 空 / null / 太短（< 20 字符）→ 直接 return，ALL 保持原样；
     *   2. JSON 解析抛异常 → catch (Throwable) 吞掉 → return；
     *   3. 没有 groups 数组或数组为空 → 返回空结果 → 保留原表；
     *   4. 逐组校验：id 为空跳过整组；chs 缺失或为空跳过整组；
     *   5. 逐台校验：name 为空跳过；urls 只留 http:// / https:// 开头且不含 '['（IPv6）
     *      的项，清洗后为空跳过该台；
     *   6. 组内 chs 全被跳掉 → 跳过该组；最终组数为 0 → 整体放弃，保留原表。
     *
     * 只有真拿到 >= 1 组，才在 synchronized 里 clear + addAll，中途没人能看见
     * 「清空了还没填」的中间态。
     */
    public static synchronized void load(String jsonText) {
        if (jsonText == null || jsonText.trim().length() < 20) {
            return;
        }
        List<Group> fresh;
        try {
            fresh = parse(jsonText);
        } catch (Throwable t) {
            // 坏 JSON / 替身不支持的字段 / 内存不足，一律当作没拉到
            Trace.err("live", "远端直播表解析失败，保留内置", t);
            return;
        }
        if (fresh.isEmpty()) {
            // 最关键的一条：远端给了一份「语法正确但没有可用台」的表，也必须放弃，
            // 否则一次手滑提交就能把直播页变成空白
            Trace.add("live", "远端直播表无可用项，保留内置");
            return;
        }
        synchronized (ALL) {
            ALL.clear();
            ALL.addAll(fresh);
        }
        LOAD_SEQ++;
        Trace.add("live", "直播表已更新 " + fresh.size() + " 组 " + chsOf(fresh) + " 台");
    }

    /** 每次成功 load 自增，供 UI 判断要不要重画。 */
    public static synchronized int loadSeq() {
        return LOAD_SEQ;
    }

    /** 形如 "内置 5 组 17 台 / 当前 5 组 17 台"。一次快照算完，避免两次读取撞上换表。 */
    public static String status() {
        List<Group> snap = groups();
        StringBuilder sb = new StringBuilder();
        sb.append("内置 ").append(BUILTIN_GROUPS).append(" 组 ").append(BUILTIN_CHS).append(" 台");
        sb.append(" / 当前 ").append(snap.size()).append(" 组 ").append(chsOf(snap)).append(" 台");
        return sb.toString();
    }

    /**
     * 真正的解析。返回的都是清洗过的新对象，可能为空列表（由调用方决定是保留旧表还是丢弃）。
     *
     * throws JSONException 是故意的：把「坏 JSON」和「语法正确但内容全废」两种失败
     * 分开报，前者走 Trace.err，后者走 Trace.add 的固定文案。
     */
    private static List<Group> parse(String jsonText) throws org.json.JSONException {
        List<Group> fresh = new ArrayList<Group>();
        org.json.JSONObject root = new org.json.JSONObject(jsonText);
        org.json.JSONArray arr = root.optJSONArray("groups");
        if (arr == null || arr.length() == 0) {
            return fresh;
        }
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject g = arr.optJSONObject(i);
            if (g == null) {
                continue;
            }
            // 第 4 层：组的身份不能空，不然 UI 没法按 id 记忆「上次看的是哪个组」
            String id = g.optString("id", "").trim();
            if (id.length() == 0) {
                continue;
            }
            String gname = g.optString("name", "").trim();
            if (gname.length() == 0) {
                // 名字缺了不该整组丢，用 id 顶上去，用户至少能看懂分组
                gname = id;
            }
            org.json.JSONArray ca = g.optJSONArray("chs");
            if (ca == null || ca.length() == 0) {
                continue;
            }
            List<Ch> chs = new ArrayList<Ch>();
            for (int j = 0; j < ca.length(); j++) {
                org.json.JSONObject co = ca.optJSONObject(j);
                if (co == null) {
                    continue;
                }
                String cname = co.optString("name", "").trim();
                if (cname.length() == 0) {
                    continue;
                }
                List<String> urls = urlsOf(co);
                if (urls.isEmpty()) {
                    // 第 5 层：一条能用的线路都没有的台，留着只会点进去就失败
                    continue;
                }
                chs.add(new Ch(cname, logoOf(co), urls));
            }
            if (chs.isEmpty()) {
                // 第 6 层前半：整组的台全被跳掉，这个组就没意义了
                continue;
            }
            fresh.add(new Group(id, gname, chs));
        }
        return fresh;
    }

    /**
     * 取一个台的线路列表并清洗。
     *
     * 为什么整数组当文本读：harness 的 JSONArray 替身只有 length() / optJSONObject(int) /
     * optInt(int,int)，没有 optString(int)，逐项取字符串过不了 harness 编译。
     * 而真 org.json 的 optString 对数组值返回它的 toString()（形如 ["a", "b"]），
     * 替身返回 List.toString()（形如 [a, b]），两种都是「方括号 + 逗号分隔」，
     * 剥掉方括号按逗号切再逐项清洗即可，两条路都通。
     */
    private static List<String> urlsOf(org.json.JSONObject o) {
        List<String> out = new ArrayList<String>();
        String raw = o.optString("urls", "");
        if (raw == null) {
            return out;
        }
        raw = raw.trim();
        if (raw.length() == 0) {
            return out;
        }
        if (raw.charAt(0) == '[') {
            int end = raw.lastIndexOf(']');
            raw = end > 0 ? raw.substring(1, end) : raw.substring(1);
        }
        // 按逗号切，然后把「不像新 URL」的碎片并回上一条 —— 线路 URL 里可能自带逗号参数
        // （http://h/a.m3u8?x=1,2）。真 org.json 的数组文本是 "[\"a\",\"b\"]"，
        // 替身是 List.toString() 的 "[a, b]"，两种都能被这条规则还原。
        List<String> segs = new ArrayList<String>();
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String s = bare(parts[i]);
            if (s.length() == 0) {
                continue;
            }
            if (isHttp(s)) {
                segs.add(parts[i]);
            } else if (segs.size() > 0) {
                int last = segs.size() - 1;
                segs.set(last, segs.get(last) + "," + parts[i]);
            }
            // 第一个 URL 之前的碎片（空白、孤儿引号）直接扔
        }
        for (int i = 0; i < segs.size(); i++) {
            String u = cleanUrl(segs.get(i));
            if (u.length() > 0) {
                out.add(u);
            }
        }
        return out;
    }

    /** 去掉引号与 JSON 转义，得到「干净文本」用于判断，不用于存储。 */
    private static String bare(String s) {
        if (s == null) {
            return "";
        }
        String u = s.trim();
        if (u.length() == 0) {
            return "";
        }
        u = u.replace("\"", "").replace("\\/", "/").trim();
        return u;
    }

    private static boolean isHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** 单条线路清洗：去空白、去 JSON 残留引号，只留 http(s) 且不含 '['（IPv6 字面量）。 */
    private static String cleanUrl(String s) {
        String u = bare(s);
        if (u.length() == 0) {
            return "";
        }
        if (u.indexOf('[') >= 0) {
            // http://[::1]:8080/a.m3u8 这类 IPv6 字面量：播放器与代理链都处理不了，
            // 而且它会让「按逗号切」这种解析彻底跑偏，直接丢
            return "";
        }
        if (!isHttp(u)) {
            return "";
        }
        return u;
    }

    /** 台标：只收绝对 http(s) 地址，相对路径/空值一律当没有（远端表里常有 "logo": ""）。 */
    private static String logoOf(org.json.JSONObject o) {
        String s = o.optString("logo", "");
        if (s == null) {
            return "";
        }
        s = s.replace("\\/", "/").trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        return "";
    }

    /** 一批组的频道总数。 */
    private static int chsOf(List<Group> gs) {
        int n = 0;
        for (int i = 0; i < gs.size(); i++) {
            Group g = gs.get(i);
            if (g == null || g.chs == null) {
                continue;
            }
            n += g.chs.size();
        }
        return n;
    }
}

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

    /**
     * 江西电信 IPTV 内置组（v2.11 新增）。
     *
     * 为什么写死在源码里而不是交给 CI 巡检：
     *   1. 115.153.245.70 是电信运营商内网的 IPTV 单播地址，只有电信宽带用户自己
     *      的链路能到；GitHub Actions 的 runner 在公网，pingscan 必然判死并把它剪掉。
     *   2. 这 12 路实测 TTFB 22~27ms、单档 8.4~9.4Mbps、10 秒分片 4.5~5.5 秒下完，
     *      比公网那些转了好几手的境外镜像源（200~2000ms、常年掉帧）好一个数量级。
     *      老板家的宽带正好是电信，这就是他能拿到的最快源。
     *   3. 路径里的 `?fmt=ts2hls` 是华为 RTSP→HLS 转换口的必需参数，少了直接 404。
     * 非电信线路打开这些台会连不上，播放页会自动跳过并换下一路，不会卡死在黑屏。
     */
    private static final String JX_TELECOM =
            "{\n"
                    + "  \"version\": 1,\n"
                    + "  \"groups\": [\n"
                    + "    {\"id\": \"jxitv\", \"name\": \"江西电信\", \"chs\": [\n"
                    + jx("江西卫视", "3221226656", "46275837") + ",\n"
                    + jx("江西都市", "3221227069", "94448631") + ",\n"
                    + jx("江西经济生活", "3221226946", "70055407") + ",\n"
                    + jx("江西公共农业", "3221226708", "53213097") + ",\n"
                    + jx("江西新闻", "3221226650", "53190437") + ",\n"
                    + jx("江西少儿", "3221226938", "68733921") + ",\n"
                    + jx("江西教育", "3221226942", "69552502") + "\n"
                    + "    ]},\n"
                    + "    {\"id\": \"jxitv2\", \"name\": \"电信央视卫视\", \"chs\": [\n"
                    + jx("CCTV-1 综合", "3221225699", "iptv8040") + ",\n"
                    + jx("CCTV-13 新闻", "3221227037", "87447722") + ",\n"
                    + jx("湖南卫视", "3221225697", "iptv8039") + ",\n"
                    + jx("浙江卫视", "3221225768",
                    "10000100000000060000000000913518_0") + ",\n"
                    + jx("东方卫视", "3221225770",
                    "10000100000000060000000000913526_0") + "\n"
                    + "    ]}\n"
                    + "  ]\n"
                    + "}";

    /** 拼一路江西电信频道的 JSON（路径固定，只有频道 ID 和令牌变）。 */
    private static String jx(String name, String id, String token) {
        return "      {\"name\": \"" + name + "\", \"logo\": \"\", \"urls\": ["
                + "\"http://115.153.245.70/PLTV/88888888/224/" + id + "/" + token
                + ".smil/index.m3u8?fmt=ts2hls\"]}";
    }

    /** 内置表的组数 / 台数，编译期就算好，自检页拿它和当前值对比。 */
    private static final int BUILTIN_GROUPS;
    private static final int BUILTIN_CHS;

    /** 每成功替换一次就 +1，调用方比对前后值即可判断 load 到底生效没有。 */
    private static int LOAD_SEQ;

    /**
     * 江西电信组，永远排在源表最前面。
     *
     * 它独立于 ALL：ALL 会被远端 live.json（以及 assets/live.json 缓存）整体替换，
     * 一旦远端表刷新，写死的内网源就会被冲掉。所以每次 groups() 时现拼：
     * 江西电信在前，远端表在后。
     */
    private static final List<Group> JX = new ArrayList<Group>();

    static {
        List<Group> b = builtinTable();
        synchronized (ALL) {
            ALL.addAll(b);
        }
        BUILTIN_GROUPS = b.size();
        BUILTIN_CHS = chsOf(b);
        JX.addAll(jxTable());
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

    /** 江西电信内置组：解析失败就当作没有这组，其它源照常可用。 */
    private static List<Group> jxTable() {
        try {
            return parse(JX_TELECOM);
        } catch (Throwable t) {
            Trace.err("live", "江西电信表解析失败", t);
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
            // v2.11：江西电信（内网单播，实测 20ms 级）固定排在前面，
            // 远端抓来的公网源放后面兜底。
            List<Group> out = new ArrayList<Group>(JX.size() + ALL.size());
            out.addAll(JX);
            out.addAll(ALL);
            return out;
        }
    }

    public static int groupCount() {
        synchronized (ALL) {
            return JX.size() + ALL.size();
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
     *      的项 —— 清洗后为空也**保留该台**（v2.8 起：空 urls 壳台留着，播放页会跳过
     *      并提示「这个台暂时没有可用的线路」，比整台消失友好）；
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
                // 第 5 层（v2.8 改）：线路全废的台**保留**，urls 写成空列表。
                // 以前这里直接丢掉整台，后果是「湖南卫视」这种老台从抽屉里消失，
                // 老用户按台名找不到还以为软件坏了。留着台名，播放页会跳过它并提示
                // 「这个台暂时没有可用的线路」，比无声无息地少一台好。
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

    /**
     * 台标三条通路用的前缀。必须与 {@code Ui.ASSET_PREFIX} 字面量一致，
     * 但这里不能直接引用 Ui —— Live 会被 test/harness 编译，而 Ui 依赖
     * android.graphics / android.util，harness 没有它的替身。
     */
    public static final String LOGO_ASSET = "asset:";

    /**
     * 台标：三条通路。
     *
     *   1. {@code http://} / {@code https://} 绝对地址 → 远端图，保持原样；
     *   2. {@code asset:logo/xxx.png} → APK 内置（v2.7 新增）；
     *   3. 其它（空串、相对路径、协议相对 {@code //host/x.png}）→ 当没有。
     *
     * 第 2 条是 v2.7 修「直播全是黑框」的核心：live.json 里原来 428 个台
     * `"logo": ""` 全是空的，巡检脚本指望远端给，远端从来没给过。
     * 现在由 test/live_scan.py 从 iptv-org 抓下来、编成 PNG 打进 APK，
     * 表里写 `asset:logo/<md5>.png`。
     *
     * 兼容 harness：只用到 optString，不碰 optString(int)。
     */
    private static String logoOf(org.json.JSONObject o) {
        String s = o.optString("logo", "");
        if (s == null) {
            return "";
        }
        s = s.replace("\\/", "/").trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        if (s.startsWith(LOGO_ASSET) && s.length() > LOGO_ASSET.length()) {
            return s;
        }
        return "";
    }

    /**
     * 台名 → 副名映射（设计稿里台名下面那行小字）。
     *
     * 为什么必须内置一张表：live.json 里 428 个台名**全部没有空格**
     * （实测 two-part = 0），不存在「CCTV-1 综合」这种可切分的形式，
     * 想做出设计稿那种「台名 + 副名」的两行层次，只能靠自己认。
     *
     * 只收 CCTV / CGTN / CETV 这类稳定频道，地方台不猜 —— 猜错了
     * 副名比没有副名更难看。成对存放，读的时候两两取。
     */
    private static final String[] ALIAS_PAIRS = {
            "CCTV-1", "综合",
            "CCTV-2", "财经",
            "CCTV-3", "综艺",
            "CCTV-4", "中文国际",
            "CCTV-5", "体育",
            "CCTV-5+", "体育赛事",
            "CCTV-6", "电影",
            "CCTV-7", "国防军事",
            "CCTV-8", "电视剧",
            "CCTV-9", "纪录",
            "CCTV-10", "科教",
            "CCTV-11", "戏曲",
            "CCTV-12", "社会与法",
            "CCTV-13", "新闻",
            "CCTV-14", "少儿",
            "CCTV-15", "音乐",
            "CCTV-16", "奥林匹克",
            "CCTV-17", "农业农村",
            "CCTV-4K", "超高清",
            "CCTV-8K", "超高清",
            "CGTN", "国际",
            "CGTN纪录", "纪录",
            "CETV-1", "教育综合",
            "CETV-2", "教育教学",
            "CETV-3", "教育人文",
            "CETV-4", "职业教育",
    };

    /**
     * 取副名，没有就返回空串（调用方据此决定那一行 GONE）。
     *
     * 匹配顺序有讲究：先查长名再查短名，否则 "CCTV-5+" 会先被
     * "CCTV-5" 命中，副名变成「体育」。所以按表里给出的顺序做**全等**匹配，
     * 不做 startsWith —— 源表里还有 "CCTV-1综合"、"CCTV1" 这类变体，
     * 全等匹配最省事也最不容易错。
     */
    public static String aliasOf(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim();
        for (int i = 0; i + 1 < ALIAS_PAIRS.length; i += 2) {
            if (ALIAS_PAIRS[i].equals(n)) {
                return ALIAS_PAIRS[i + 1];
            }
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

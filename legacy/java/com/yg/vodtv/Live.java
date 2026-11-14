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
     * 江西电信 IPTV 内置分组（v2.11 起）。
     *
     * 为什么写死在源码里而不是交给 CI 巡检：
     *   1. 115.153.245.70 这类地址是电信运营商内网的 IPTV 单播口，只有电信家宽
     *      自己的链路能到；GitHub Actions 的 runner 在公网，probe 必然判死并剪掉。
     *   2. 实测 TTFB 20~30ms、单档 8.4~9.4Mbps、10 秒分片 4.5~5.5 秒下完，
     *      比公网转了几手的境外镜像（200~2000ms、常年掉帧）好一个数量级。
     *   3. 路径里的 `?fmt=ts2hls` 是华为 RTSP→HLS 转换口的必需参数，少了直接 404；
     *      master 里的变体行 `01.m3u8?fmt=ts2hls,7824,01.m3u8,0,8467,0,0` 里那串
     *      逗号参数也必须原样带着，砍掉就又会跳回 master。
     *
     * v2.14：从 12 台扩到 165 台 / 174 条线路（判活脚本只拉 m3u8 列表、不碰 .ts，
     * 6 线程 + 4/8 秒超时，全表复测过一遍；已知失效的 CCTV-3/5/5+/6/8、
     * 江西影视旅游、天翼高清 1~10、直播室 201~211 等 262 条没有进来）。
     *
     * 台名重复的（CCTV-1、江西都市、西藏/新疆/兵团卫视、金鹰卡通、国学、爱体育）
     * 已经把「(备)」那一路并成同一台的第 2 条线路，播放页换源时会在台内切换，
     * 而不是在台列表里多出一行带括号的重复台。
     *
     * 非电信线路打开这些台会连不上，播放页会自动跳过并换下一路，不会卡在黑屏。
     */
    private static final String[] JX_GROUP_DEFS = {
            "jxitv|江西本地",
            "jxcctv|央视",
            "jxws|卫视",
            "jxfilm|影视剧场",
            "jxmore|付费专业",
    };

    /**
     * 江西电信内置源：一行一台，格式「组id|台名|完整线路」。
     *
     * 用「一行一条」的纯文本表而不是 JSON 字符串，是为了改源时只动一行、不碰转义，
     * 也避免 170 多条 JSON 拼串里的一个引号写错就让整张表解析失败。组装在
     * {@link #jxTable()} 里做，字段清洗规则和远端表一致（只要 http/https）。
     */
    private static final String[] JX_SOURCES = {
            "jxitv|江西卫视|http://115.153.245.70/PLTV/88888888/224/3221226656/46275837.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西都市|http://115.153.245.70/PLTV/88888888/224/3221226728/53707442.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西都市|http://115.153.245.70/PLTV/88888888/224/3221227069/94448631.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西经济生活|http://115.153.245.70/PLTV/88888888/224/3221226946/70055407.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西经济生活|http://115.153.245.70/PLTV/88888888/224/3221226717/53707446.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西新闻|http://115.153.245.70/PLTV/88888888/224/3221226650/53190437.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西公共农业|http://115.153.245.70/PLTV/88888888/224/3221226708/53213097.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西少儿|http://115.153.245.70/PLTV/88888888/224/3221226938/68733921.smil/index.m3u8?fmt=ts2hls",
            "jxitv|江西教育|http://115.153.245.70/PLTV/88888888/224/3221226942/69552502.smil/index.m3u8?fmt=ts2hls",
            "jxitv|赣州新闻综合|http://115.153.230.145/PLTV/88888888/224/3221227254/121266741.smil/index.m3u8?fmt=ts2hls",
            "jxitv|赣州公共|http://115.153.230.145/PLTV/88888888/224/3221227262/121375235.smil/index.m3u8?fmt=ts2hls",
            "jxitv|南昌新闻综合|http://115.153.254.81/PLTV/88888888/224/3221227078/113409131.smil/index.m3u8?fmt=ts2hls",
            "jxitv|南昌都市频道|http://115.153.254.81/PLTV/88888888/224/3221227075/113409249.smil/index.m3u8?fmt=ts2hls",
            "jxitv|南昌资讯频道|http://115.153.254.81/PLTV/88888888/224/3221227080/113409255.smil/index.m3u8?fmt=ts2hls",
            "jxitv|九江新闻综合|http://115.153.245.70/PLTV/88888888/224/3221227268/122499157.smil/index.m3u8?fmt=ts2hls",
            "jxitv|九江公共|http://115.153.245.70/PLTV/88888888/224/3221227270/122499168.smil/index.m3u8?fmt=ts2hls",
            "jxitv|九江教育|http://115.153.245.70/PLTV/88888888/224/3221225607/iptv7994.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-1 综合|http://115.153.245.70/PLTV/88888888/224/3221225699/iptv8040.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-1 综合|http://115.153.245.70/PLTV/88888888/224/3221225517/iptv7949.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-2 财经|http://115.153.245.70/PLTV/88888888/224/3221226825/59814584.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-4 国际|http://115.153.245.70/PLTV/88888888/224/3221226838/59814684.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-7 国防军事|http://115.153.245.70/PLTV/88888888/224/3221226827/59814693.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-9 纪录|http://115.153.245.70/PLTV/88888888/224/3221226840/59814699.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-10 科教|http://115.153.245.70/PLTV/88888888/224/3221226630/51813372.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-11 戏曲|http://115.153.245.70/PLTV/88888888/224/3221226986/85722616.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-12 社会与法|http://115.153.245.70/PLTV/88888888/224/3221226829/59814709.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-13 新闻|http://115.153.245.70/PLTV/88888888/224/3221227037/87447722.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-14 少儿|http://115.153.245.70/PLTV/88888888/224/3221226634/51813368.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-15 音乐|http://115.153.245.70/PLTV/88888888/224/3221226988/85722541.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-16 奥林匹克|http://115.153.245.70/PLTV/88888888/224/3221227051/92603106.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-17 农业农村|http://115.153.245.70/PLTV/88888888/224/3221226846/61397282.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-4K 超高清|http://115.153.245.70/PLTV/88888888/224/3221227073/106537394.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-4 欧洲|http://115.153.245.70/PLTV/88888888/224/3221226964/77799053.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV-4 美洲|http://115.153.245.70/PLTV/88888888/224/3221226962/77799052.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CCTV16-4K|http://115.153.233.154/PLTV/88888888/224/3221227051/92603106.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CETV-1|http://115.153.245.70/PLTV/88888888/224/3221226858/63768493.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CETV-2|http://115.153.245.70/PLTV/88888888/224/3221226978/85722625.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CETV-4|http://115.153.245.70/PLTV/88888888/224/3221226905/65242632.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CGTN|http://115.153.245.70/PLTV/88888888/224/3221226694/55738457.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CGTN 纪录|http://115.153.245.70/PLTV/88888888/224/3221226960/77798993.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|CGTN 英语纪录|http://115.153.245.70/PLTV/88888888/224/3221226947/77798966.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|中国环球法语|http://115.153.245.70/PLTV/88888888/224/3221226968/77799060.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|中国环球俄语|http://115.153.245.70/PLTV/88888888/224/3221226966/77798997.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|中国环球西班牙语|http://115.153.245.70/PLTV/88888888/224/3221226949/77798970.smil/index.m3u8?fmt=ts2hls",
            "jxcctv|中国环球阿拉伯语|http://115.153.245.70/PLTV/88888888/224/3221226951/77799001.smil/index.m3u8?fmt=ts2hls",
            "jxws|湖南卫视|http://115.153.245.70/PLTV/88888888/224/3221225697/iptv8039.smil/index.m3u8?fmt=ts2hls",
            "jxws|浙江卫视|http://115.153.245.70/PLTV/88888888/224/3221225768/10000100000000060000000000913518_0.smil/index.m3u8?fmt=ts2hls",
            "jxws|江苏卫视|http://115.153.245.70/PLTV/88888888/224/3221225766/10000100000000060000000000913517_0.smil/index.m3u8?fmt=ts2hls",
            "jxws|东方卫视|http://115.153.245.70/PLTV/88888888/224/3221225770/10000100000000060000000000913526_0.smil/index.m3u8?fmt=ts2hls",
            "jxws|北京卫视|http://115.153.245.70/PLTV/88888888/224/3221225546/iptv51912.smil/index.m3u8?fmt=ts2hls",
            "jxws|广东卫视|http://115.153.245.70/PLTV/88888888/224/3221225957/10000100000000060000000000913519_0.smil/index.m3u8?fmt=ts2hls",
            "jxws|深圳卫视|http://115.153.245.70/PLTV/88888888/224/3221225764/10000100000000060000000000913503_0.smil/index.m3u8?fmt=ts2hls",
            "jxws|安徽卫视|http://115.153.245.70/PLTV/88888888/224/3221226622/51176344.smil/index.m3u8?fmt=ts2hls",
            "jxws|山东卫视|http://115.153.245.70/PLTV/88888888/224/3221225919/10000100000000060000000000811554_0.smil/index.m3u8?fmt=ts2hls",
            "jxws|湖北卫视|http://115.153.245.70/PLTV/88888888/224/3221225827/10000100000000060000000000470332_2.smil/index.m3u8?fmt=ts2hls",
            "jxws|河南卫视|http://115.153.245.70/PLTV/88888888/224/3221226842/59814787.smil/index.m3u8?fmt=ts2hls",
            "jxws|四川卫视|http://115.153.245.70/PLTV/88888888/224/3221226693/51813384.smil/index.m3u8?fmt=ts2hls",
            "jxws|重庆卫视|http://115.153.245.70/PLTV/88888888/224/3221226638/51813388.smil/index.m3u8?fmt=ts2hls",
            "jxws|天津卫视|http://115.153.245.70/PLTV/88888888/224/3221226652/46098366.smil/index.m3u8?fmt=ts2hls",
            "jxws|北京卫视4K|http://115.153.245.70/PLTV/88888888/224/3221227248/119886813.smil/index.m3u8?fmt=ts2hls",
            "jxws|三沙卫视|http://115.153.245.70/PLTV/88888888/224/3221226984/85722645.smil/index.m3u8?fmt=ts2hls",
            "jxws|东南卫视|http://115.153.245.70/PLTV/88888888/224/3221226677/51176347.smil/index.m3u8?fmt=ts2hls",
            "jxws|云南卫视|http://115.153.245.70/PLTV/88888888/224/3221226937/72640386.smil/index.m3u8?fmt=ts2hls",
            "jxws|兵团卫视|http://115.153.245.70/PLTV/88888888/224/3221227278/123649594.smil/index.m3u8?fmt=ts2hls",
            "jxws|兵团卫视|http://115.153.245.70/PLTV/88888888/224/3221225753/iptv101723.smil/index.m3u8?fmt=ts2hls",
            "jxws|内蒙古卫视|http://115.153.245.70/PLTV/88888888/224/3221225591/iptv7986.smil/index.m3u8?fmt=ts2hls",
            "jxws|厦门卫视|http://115.153.245.70/PLTV/88888888/224/3221226982/85722609.smil/index.m3u8?fmt=ts2hls",
            "jxws|吉林卫视|http://115.153.245.70/PLTV/88888888/224/3221226831/59814731.smil/index.m3u8?fmt=ts2hls",
            "jxws|宁夏卫视|http://115.153.245.70/PLTV/88888888/224/3221225747/iptv101619.smil/index.m3u8?fmt=ts2hls",
            "jxws|山西卫视|http://115.153.245.70/PLTV/88888888/224/3221225551/iptv7966.smil/index.m3u8?fmt=ts2hls",
            "jxws|广西卫视|http://115.153.245.70/PLTV/88888888/224/3221226954/71778425.smil/index.m3u8?fmt=ts2hls",
            "jxws|新疆卫视|http://115.153.245.70/PLTV/88888888/224/3221227273/123649589.smil/index.m3u8?fmt=ts2hls",
            "jxws|新疆卫视|http://115.153.245.70/PLTV/88888888/224/3221225550/iptv61197.smil/index.m3u8?fmt=ts2hls",
            "jxws|河北卫视|http://115.153.245.70/PLTV/88888888/224/3221226821/59781859.smil/index.m3u8?fmt=ts2hls",
            "jxws|海南卫视|http://115.153.245.70/PLTV/88888888/224/3221226933/70442779.smil/index.m3u8?fmt=ts2hls",
            "jxws|西藏卫视|http://115.153.245.70/PLTV/88888888/224/3221227276/123649585.smil/index.m3u8?fmt=ts2hls",
            "jxws|西藏卫视|http://115.153.245.70/PLTV/88888888/224/3221225570/iptv101725.smil/index.m3u8?fmt=ts2hls",
            "jxws|贵州卫视|http://115.153.245.70/PLTV/88888888/224/3221226833/59814738.smil/index.m3u8?fmt=ts2hls",
            "jxws|辽宁卫视|http://115.153.245.70/PLTV/88888888/224/3221226649/46098370.smil/index.m3u8?fmt=ts2hls",
            "jxws|陕西卫视|http://115.153.245.70/PLTV/88888888/224/3221225755/iptv101726.smil/index.m3u8?fmt=ts2hls",
            "jxws|青海卫视|http://115.153.245.70/PLTV/88888888/224/3221225589/iptv7985.smil/index.m3u8?fmt=ts2hls",
            "jxws|黑龙江卫视|http://115.153.245.70/PLTV/88888888/224/3221225584/10000100000000060000000000031778_0.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|CHC动作电影|http://115.153.245.70/PLTV/88888888/224/3221226992/86128817.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|CHC家庭影院|http://115.153.245.70/PLTV/88888888/224/3221226959/86128800.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|CHC高清电影|http://115.153.245.70/PLTV/88888888/224/3221226990/86128784.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|IPTV谍战剧场|http://115.153.245.70/PLTV/88888888/224/3221227016/86441085.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|光影|http://115.153.245.70/PLTV/88888888/224/3221227013/86502094.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|军旅剧场|http://115.153.245.70/PLTV/88888888/224/3221226995/86441107.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|动作影院|http://115.153.245.70/PLTV/88888888/224/3221226999/86441130.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|古装剧场|http://115.153.245.70/PLTV/88888888/224/3221227020/86441115.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|喜剧影院|http://115.153.245.70/PLTV/88888888/224/3221226997/86441126.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|城市剧场|http://115.153.245.70/PLTV/88888888/224/3221227018/86441003.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|墨宝|http://115.153.245.70/PLTV/88888888/224/3221227032/86502054.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|家庭影院|http://115.153.245.70/PLTV/88888888/224/3221227022/86441136.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|怀旧剧场|http://115.153.245.70/PLTV/88888888/224/3221226996/86128881.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|星影|http://115.153.245.70/PLTV/88888888/224/3221227042/86502073.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|武侠剧场|http://115.153.245.70/PLTV/88888888/224/3221227003/86441017.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|热播剧场|http://115.153.245.70/PLTV/88888888/224/3221227008/86440955.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|爱大剧|http://115.153.245.70/PLTV/88888888/224/3221227014/86441032.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|爱电影|http://115.153.245.70/PLTV/88888888/224/3221226985/86441021.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|第一剧场|http://115.153.245.70/PLTV/88888888/224/3221226961/86128891.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|精选|http://115.153.245.70/PLTV/88888888/224/3221227005/86441212.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|经典电影|http://115.153.245.70/PLTV/88888888/224/3221227010/86440860.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|芒果跨年(特写)|http://115.153.245.70/PLTV/88888888/224/3221226956/72788191.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|芒果跨年云监工|http://115.153.245.70/PLTV/88888888/224/3221226958/72788248.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|鉴赏|http://115.153.245.70/PLTV/88888888/224/3221227011/86502050.smil/index.m3u8?fmt=ts2hls",
            "jxfilm|风云剧场|http://115.153.245.70/PLTV/88888888/224/3221226963/86128874.smil/index.m3u8?fmt=ts2hls",
            "jxmore|IPTV法治|http://115.153.245.70/PLTV/88888888/224/3221226993/86440997.smil/index.m3u8?fmt=ts2hls",
            "jxmore|IPTV相声小品|http://115.153.245.70/PLTV/88888888/224/3221226989/86441091.smil/index.m3u8?fmt=ts2hls",
            "jxmore|IPTV野外|http://115.153.245.70/PLTV/88888888/224/3221226991/86441042.smil/index.m3u8?fmt=ts2hls",
            "jxmore|世界地理|http://115.153.245.70/PLTV/88888888/224/3221226965/86128966.smil/index.m3u8?fmt=ts2hls",
            "jxmore|东方财经|http://115.153.245.70/PLTV/88888888/224/3221225609/iptv7995.smil/index.m3u8?fmt=ts2hls",
            "jxmore|中国天气|http://115.153.245.70/PLTV/88888888/224/3221227056/89471929.smil/index.m3u8?fmt=ts2hls",
            "jxmore|书画|http://115.153.245.70/PLTV/88888888/224/3221226971/86128924.smil/index.m3u8?fmt=ts2hls",
            "jxmore|优优宝贝|http://115.153.245.70/PLTV/88888888/224/3221227050/86502131.smil/index.m3u8?fmt=ts2hls",
            "jxmore|军事|http://115.153.245.70/PLTV/88888888/224/3221227038/86502156.smil/index.m3u8?fmt=ts2hls",
            "jxmore|动漫秀场|http://115.153.245.70/PLTV/88888888/224/3221225613/iptv7997.smil/index.m3u8?fmt=ts2hls",
            "jxmore|动画|http://115.153.245.70/PLTV/88888888/224/3221227009/86502150.smil/index.m3u8?fmt=ts2hls",
            "jxmore|卡酷少儿|http://115.153.245.70/PLTV/88888888/224/3221225566/iptv101695.smil/index.m3u8?fmt=ts2hls",
            "jxmore|台球|http://115.153.245.70/PLTV/88888888/224/3221227034/86502060.smil/index.m3u8?fmt=ts2hls",
            "jxmore|嘉佳卡通|http://115.153.245.70/PLTV/88888888/224/3221225588/10000100000000060000000000140157_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|四海钓鱼|http://115.153.245.70/PLTV/88888888/224/3221227048/86502173.smil/index.m3u8?fmt=ts2hls",
            "jxmore|国学|http://115.153.245.70/PLTV/88888888/224/3221226797/59781463.smil/index.m3u8?fmt=ts2hls",
            "jxmore|国学|http://115.153.245.70/PLTV/88888888/224/3221227028/86441216.smil/index.m3u8?fmt=ts2hls",
            "jxmore|地理|http://115.153.245.70/PLTV/88888888/224/3221227040/86502111.smil/index.m3u8?fmt=ts2hls",
            "jxmore|央视台球|http://115.153.245.70/PLTV/88888888/224/3221226955/86106661.smil/index.m3u8?fmt=ts2hls",
            "jxmore|央视精品|http://115.153.245.70/PLTV/88888888/224/3221226957/86128679.smil/index.m3u8?fmt=ts2hls",
            "jxmore|女性时尚|http://115.153.245.70/PLTV/88888888/224/3221226998/86128978.smil/index.m3u8?fmt=ts2hls",
            "jxmore|好学生|http://115.153.245.70/PLTV/88888888/224/3221227026/86441141.smil/index.m3u8?fmt=ts2hls",
            "jxmore|少儿动画|http://115.153.245.70/PLTV/88888888/224/3221227006/86440922.smil/index.m3u8?fmt=ts2hls",
            "jxmore|山东教育|http://115.153.245.70/PLTV/88888888/224/3221226980/85722602.smil/index.m3u8?fmt=ts2hls",
            "jxmore|快乐垂钓|http://115.153.245.70/PLTV/88888888/224/3221226834/59781974.smil/index.m3u8?fmt=ts2hls",
            "jxmore|戏曲|http://115.153.245.70/PLTV/88888888/224/3221227030/86441159.smil/index.m3u8?fmt=ts2hls",
            "jxmore|收视指南|http://115.153.245.70/PLTV/88888888/224/3221226987/86440886.smil/index.m3u8?fmt=ts2hls",
            "jxmore|文物宝库|http://115.153.245.70/PLTV/88888888/224/3221226808/59781526.smil/index.m3u8?fmt=ts2hls",
            "jxmore|早教|http://115.153.245.70/PLTV/88888888/224/3221227007/86441065.smil/index.m3u8?fmt=ts2hls",
            "jxmore|梨园|http://115.153.245.70/PLTV/88888888/224/3221226822/59781815.smil/index.m3u8?fmt=ts2hls",
            "jxmore|武术|http://115.153.245.70/PLTV/88888888/224/3221227017/86502065.smil/index.m3u8?fmt=ts2hls",
            "jxmore|武术世界|http://115.153.245.70/PLTV/88888888/224/3221226824/59781846.smil/index.m3u8?fmt=ts2hls",
            "jxmore|汽摩|http://115.153.245.70/PLTV/88888888/224/3221226819/59781852.smil/index.m3u8?fmt=ts2hls",
            "jxmore|游戏风云|http://115.153.245.70/PLTV/88888888/224/3221225615/iptv7998.smil/index.m3u8?fmt=ts2hls",
            "jxmore|爱上4K|http://115.153.245.70/PLTV/88888888/224/3221227025/86502164.smil/index.m3u8?fmt=ts2hls",
            "jxmore|爱体育|http://115.153.245.70/PLTV/88888888/224/3221227012/86440946.smil/index.m3u8?fmt=ts2hls",
            "jxmore|爱体育|http://115.153.245.70/PLTV/88888888/224/3221227044/86502115.smil/index.m3u8?fmt=ts2hls",
            "jxmore|爱生活|http://115.153.245.70/PLTV/88888888/224/3221227024/86441150.smil/index.m3u8?fmt=ts2hls",
            "jxmore|爱综艺|http://115.153.245.70/PLTV/88888888/224/3221226983/86440966.smil/index.m3u8?fmt=ts2hls",
            "jxmore|环球奇观|http://115.153.245.70/PLTV/88888888/224/3221226977/86128939.smil/index.m3u8?fmt=ts2hls",
            "jxmore|环球旅游|http://115.153.245.70/PLTV/88888888/224/3221227033/86502177.smil/index.m3u8?fmt=ts2hls",
            "jxmore|电视指南|http://115.153.245.70/PLTV/88888888/224/3221226969/86129034.smil/index.m3u8?fmt=ts2hls",
            "jxmore|美人|http://115.153.245.70/PLTV/88888888/224/3221227015/86502099.smil/index.m3u8?fmt=ts2hls",
            "jxmore|美妆|http://115.153.245.70/PLTV/88888888/224/3221227021/86502160.smil/index.m3u8?fmt=ts2hls",
            "jxmore|解密|http://115.153.245.70/PLTV/88888888/224/3221227023/86502069.smil/index.m3u8?fmt=ts2hls",
            "jxmore|财富天下|http://115.153.245.70/PLTV/88888888/224/3221227068/95616594.smil/index.m3u8?fmt=ts2hls",
            "jxmore|足球|http://115.153.245.70/PLTV/88888888/224/3221227019/86502107.smil/index.m3u8?fmt=ts2hls",
            "jxmore|金鹰卡通|http://115.153.245.70/PLTV/88888888/224/3221226972/80382319.smil/index.m3u8?fmt=ts2hls",
            "jxmore|金鹰卡通|http://115.153.245.70/PLTV/88888888/224/3221225585/iptv7983.smil/index.m3u8?fmt=ts2hls",
            "jxmore|金鹰纪实|http://115.153.245.70/PLTV/88888888/224/3221226823/59781863.smil/index.m3u8?fmt=ts2hls",
            "jxmore|音乐现场|http://115.153.245.70/PLTV/88888888/224/3221227001/86441055.smil/index.m3u8?fmt=ts2hls",
            "jxmore|风云足球|http://115.153.245.70/PLTV/88888888/224/3221227000/86128919.smil/index.m3u8?fmt=ts2hls",
            "jxmore|风云音乐|http://115.153.245.70/PLTV/88888888/224/3221226994/86128897.smil/index.m3u8?fmt=ts2hls",
            "jxmore|高尔夫网球|http://115.153.245.70/PLTV/88888888/224/3221226967/86128985.smil/index.m3u8?fmt=ts2hls",
            "jxmore|高网|http://115.153.245.70/PLTV/88888888/224/3221227036/86502103.smil/index.m3u8?fmt=ts2hls",
            "jxmore|魅力时尚|http://115.153.245.70/PLTV/88888888/224/3221226981/86440822.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室101|http://115.153.245.70/PLTV/88888888/224/3221226231/10000100000000060000000001398612_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室102|http://115.153.245.70/PLTV/88888888/224/3221226208/10000100000000060000000001398613_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室103|http://115.153.245.70/PLTV/88888888/224/3221226234/10000100000000060000000001398614_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室104|http://115.153.245.70/PLTV/88888888/224/3221226228/10000100000000060000000001398576_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室105|http://115.153.245.70/PLTV/88888888/224/3221226211/10000100000000060000000001398615_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室106|http://115.153.245.70/PLTV/88888888/224/3221226237/10000100000000060000000001398616_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室107|http://115.153.245.70/PLTV/88888888/224/3221226214/10000100000000060000000001398617_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室108|http://115.153.245.70/PLTV/88888888/224/3221226252/10000100000000060000000001408355_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室109|http://115.153.245.70/PLTV/88888888/224/3221226226/10000100000000060000000001408356_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室110|http://115.153.245.70/PLTV/88888888/224/3221226255/10000100000000060000000001408357_0.smil/index.m3u8?fmt=ts2hls",
            "jxmore|直播室111|http://115.153.245.70/PLTV/88888888/224/3221226229/10000100000000060000000001408358_0.smil/index.m3u8?fmt=ts2hls",
    };

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

    /**
     * 把 JX_SOURCES 按 JX_GROUP_DEFS 的顺序组装成分组。
     *
     * 同一个台名出现多行（主线路 + 备用线路）时并成同一个台的多条 urls，播放页
     * autoNext 会在台内换线，而不是在台列表里多摆一行「XX(备)」让人误以为是另一个台。
     * 任何一行写坏只丢那一行（Trace.err 留痕），不牵连整张表。
     */
    private static List<Group> jxTable() {
        List<Group> out = new ArrayList<Group>();
        for (int i = 0; i < JX_GROUP_DEFS.length; i++) {
            String def = JX_GROUP_DEFS[i];
            int bar = def.indexOf('|');
            if (bar <= 0 || bar >= def.length() - 1) {
                Trace.err("live", "江西电信分组定义写坏：" + def, null);
                continue;
            }
            String id = def.substring(0, bar).trim();
            String gname = def.substring(bar + 1).trim();
            // LinkedHashMap 保序：台序就是数据表里的书写顺序
            java.util.LinkedHashMap<String, List<String>> byName =
                    new java.util.LinkedHashMap<String, List<String>>();
            for (int j = 0; j < JX_SOURCES.length; j++) {
                String row = JX_SOURCES[j];
                if (row == null) {
                    continue;
                }
                int b1 = row.indexOf('|');
                int b2 = b1 < 0 ? -1 : row.indexOf('|', b1 + 1);
                if (b1 <= 0 || b2 <= b1 + 1) {
                    Trace.err("live", "江西电信源格式写坏：" + row, null);
                    continue;
                }
                if (!id.equals(row.substring(0, b1).trim())) {
                    continue;
                }
                String cname = row.substring(b1 + 1, b2).trim();
                String url = row.substring(b2 + 1).trim();
                if (cname.length() == 0
                        || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    continue;
                }
                List<String> bucket = byName.get(cname);
                if (bucket == null) {
                    bucket = new ArrayList<String>(2);
                    byName.put(cname, bucket);
                }
                if (!bucket.contains(url)) {
                    bucket.add(url);
                }
            }
            List<Ch> chs = new ArrayList<Ch>(byName.size());
            for (java.util.Map.Entry<String, List<String>> e : byName.entrySet()) {
                chs.add(new Ch(e.getKey(), "", e.getValue()));
            }
            if (!chs.isEmpty()) {
                out.add(new Group(id, gname, chs));
            }
        }
        return out;
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

    /**
     * 播放页专用的分组表（v2.16）。
     *
     * groups() 是把「内置江西电信 5 组 + 远端公共表 7 组」原样拼起来，分组条里
     * 一次列出 12 个分组。可远端那张表在这条电信宽带上实测是成片连不上的
     * （自检日志里刷屏的 SocketTimeoutException 全是它们），和内网源摆在同一行，
     * 只会让人点进去看黑屏 —— 老板说的「直播卡」有一大半是这么来的。
     * 所以播放页只把内置组排前面，远端那三百多台合并成一个「公共源」组挂在最后：
     * 想找 CCTV-3/5/6/8 这类内置表没有的台还能翻得到，但不占主路径。
     */
    public static List<Group> playerGroups() {
        synchronized (ALL) {
            List<Group> out = new ArrayList<Group>(JX.size() + 1);
            out.addAll(JX);
            List<Ch> remote = new ArrayList<Ch>();
            for (int i = 0; i < ALL.size(); i++) {
                Group g = ALL.get(i);
                if (g != null && g.chs != null) {
                    remote.addAll(g.chs);
                }
            }
            if (!remote.isEmpty()) {
                out.add(new Group("remote", "公共源", remote));
            }
            return out;
        }
    }

    /** 「江西电信」内置分组的下标；没有内置组时返回 -1。 */
    public static int jiangxiGroup() {
        synchronized (ALL) {
            for (int i = 0; i < JX.size(); i++) {
                String id = JX.get(i).id;
                if (id != null && id.startsWith("jx")) {
                    return i;
                }
            }
            return -1;
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

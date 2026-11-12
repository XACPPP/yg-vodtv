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

    /** 本文件格式：key|接口前缀|Referer|频道1..29 的 type_id（0 表示该源没有）。 */
    private static final String[] SPEC = {
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

    static {
        for (int i = 0; i < SPEC.length; i++) {
            Src s = parse(SPEC[i]);
            if (s != null) {
                ALL.add(s);
            }
        }
    }

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

    public static List<Src> all() {
        return ALL;
    }

    public static Src byKey(String key) {
        if (key != null) {
            for (int i = 0; i < ALL.size(); i++) {
                if (ALL.get(i).key.equals(key)) {
                    return ALL.get(i);
                }
            }
        }
        return ALL.isEmpty() ? null : ALL.get(0);
    }

    /** 详情不要，只要一个短名字显示在卡片角上。 */
    public String label() {
        return key;
    }
}

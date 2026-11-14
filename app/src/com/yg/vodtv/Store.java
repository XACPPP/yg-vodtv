package com.yg.vodtv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本机记录：每部剧每一集看到哪儿。
 *
 * 存法：一条记录一个 key，"p_" + 源_影片id + "_" + 集号，值是毫秒。
 * 多源之后影片 id 会撞（maoyan 的 123 和 ffzy 的 123 是两部不同的剧），
 * 所以 key 里必须带源名。
 * 另外用一个 "recent" 记最近看过的顺序，方便首页将来做「继续观看」。
 */
public final class Store {

    private static final String FILE = "yg_vod";
    private static final String K_POS = "p_";
    private static final String K_RECENT = "recent";
    private static final int RECENT_MAX = 20;

    private static SharedPreferences sp;

    private Store() {
    }

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    /** 多源下的影片唯一标识：源名_id。 */
    public static String vid(String src, int id) {
        if (src == null || src.length() == 0) {
            return String.valueOf(id);
        }
        return src + "_" + id;
    }

    /** 读出某剧某集的播放位置（毫秒），没有记录返回 0。 */
    public static int pos(String vid, int ep) {
        if (sp == null || vid == null || vid.length() == 0) {
            return 0;
        }
        try {
            return sp.getInt(K_POS + vid + "_" + ep, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 记住某剧某集看到哪儿；同时把这部剧挪到「最近观看」最前面。 */
    public static void savePos(String vid, int ep, int ms) {
        if (sp == null || vid == null || vid.length() == 0 || ms < 0) {
            return;
        }
        try {
            // 片头 5 秒内不记，免得每次都从 0 开始算「继续看」
            int v = ms < 5000 ? 0 : ms;
            SharedPreferences.Editor e = sp.edit();
            e.putInt(K_POS + vid + "_" + ep, v);
            String r = sp.getString(K_RECENT, "");
            String me = vid + "_" + ep;
            StringBuilder sb = new StringBuilder(me);
            if (r.length() > 0) {
                String[] parts = r.split(",");
                int n = 0;
                for (int i = 0; i < parts.length && n < RECENT_MAX - 1; i++) {
                    String p = parts[i];
                    if (p.length() == 0 || p.equals(me)) {
                        continue;
                    }
                    sb.append(',').append(p);
                    n++;
                }
            }
            e.putString(K_RECENT, sb.toString());
            e.apply();
        } catch (Throwable t) {
            Trace.err("store", "保存播放位置失败", t);
        }
    }

    /* ==================== 每集总时长 ==================== */

    private static final String K_DUR = "d_";

    /**
     * 记住某集的总时长（毫秒），没有记录返回 0。
     *
     * 为什么要单独存：详情页选集格子上的「已看进度条」要按真实比例画，
     * 而进度条只知道「看到第几毫秒」是没用的 —— 得知道整集多长才知道画多长。
     * 起播成功后由播放器回填，播过一集的剧下次进详情页就有准确比例了。
     */
    public static int dur(String vid, int ep) {
        if (sp == null || vid == null || vid.length() == 0) {
            return 0;
        }
        try {
            return sp.getInt(K_DUR + vid + "_" + ep, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void saveDur(String vid, int ep, int ms) {
        if (sp == null || vid == null || vid.length() == 0 || ms <= 0) {
            return;
        }
        try {
            sp.edit().putInt(K_DUR + vid + "_" + ep, ms).apply();
        } catch (Throwable t) {
            Trace.err("store", "保存总时长失败", t);
        }
    }

    /* ==================== 上次停留的频道 ==================== */

    private static final String K_TAB = "tab";

    /**
     * 上次看的是第几个频道。
     *
     * 冷启动默认停在「推荐」，但用户的习惯往往是连着追某部剧的时候
     * 天天在同一类里翻 —— 记住一下，下次开机少按几次遥控器。
     */
    public static int tab() {
        if (sp == null) {
            return 0;
        }
        try {
            return sp.getInt(K_TAB, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void saveTab(int idx) {
        if (sp == null || idx < 0) {
            return;
        }
        try {
            sp.edit().putInt(K_TAB, idx).apply();
        } catch (Throwable t) {
            Trace.err("store", "保存频道位置失败", t);
        }
    }

    /* ==================== 直播上次看到哪个台 ==================== */

    private static final String K_SECTION = "sect";

    /** 上次停在哪个卡片栏：0 影视点播 / 1 电视直播。 */
    public static int section() {
        if (sp == null) {
            return 0;
        }
        try {
            return sp.getInt(K_SECTION, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void saveSection(int s) {
        if (sp == null) {
            return;
        }
        try {
            sp.edit().putInt(K_SECTION, s).apply();
        } catch (Throwable t) {
            // 存不上就算了，只是少了「记住上次板块」
        }
    }

    private static final String K_LIVE_GROUP = "live_g";
    private static final String K_LIVE_CH = "live_c";

    /** 上次看的直播分组下标。没记录返回 -1，由调用方决定回落到第 0 组。 */
    public static int liveGroup() {
        if (sp == null) {
            return -1;
        }
        try {
            return sp.getInt(K_LIVE_GROUP, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int liveChannel() {
        if (sp == null) {
            return -1;
        }
        try {
            return sp.getInt(K_LIVE_CH, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void saveLive(int groupIdx, int chIdx) {
        if (sp == null || groupIdx < 0 || chIdx < 0) {
            return;
        }
        try {
            sp.edit().putInt(K_LIVE_GROUP, groupIdx).putInt(K_LIVE_CH, chIdx).apply();
        } catch (Throwable t) {
            Trace.err("store", "保存直播位置失败", t);
        }
    }

    private static final String K_LIVE_NAME = "live_n";

    /**
     * 上次看的台名。
     *
     * 为什么要存名字：源表每天巡检一次，台的位置天天在变 —— 湖南卫视今天在
     * sat 组第 3 个，明天可能在第 1 个，或者整台被筛空。只存下标的话，
     * 「开机直接播上次那个台」大概率播成别的台。存名字 + 名字找不到才回落到下标，
     * 才是可靠的做法。
     */
    public static String liveName() {
        if (sp == null) {
            return "";
        }
        try {
            String s = sp.getString(K_LIVE_NAME, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void saveLive(int groupIdx, int chIdx, String name) {
        saveLive(groupIdx, chIdx);
        if (sp == null || name == null || name.length() == 0) {
            return;
        }
        try {
            sp.edit().putString(K_LIVE_NAME, name).apply();
        } catch (Throwable t) {
            Trace.err("store", "保存直播台名失败", t);
        }
    }

    /** 清掉记忆的台名。v2.12 一次性迁到「江西电信」组时用：
     *  不清的话 restoreByName() 会按台名把老用户又拽回公共表的 CCTV-8。 */
    public static void clearLiveName() {
        if (sp == null) {
            return;
        }
        try {
            sp.edit().remove(K_LIVE_NAME).apply();
        } catch (Throwable t) {
            Trace.err("store", "清直播台名失败", t);
        }
    }

    /** v2.12 一次性迁移标记：老用户升级后第一次进直播，默认落到「江西电信」组。 */
    private static final String K_LIVE_JX = "live_jx_mig";

    public static boolean liveJxMigrated() {
        if (sp == null) {
            return true;
        }
        try {
            return sp.getBoolean(K_LIVE_JX, false);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void markLiveJxMigrated() {
        if (sp == null) {
            return;
        }
        try {
            sp.edit().putBoolean(K_LIVE_JX, true).apply();
        } catch (Throwable t) {
            Trace.err("store", "写直播迁移标记失败", t);
        }
    }

    /* ==================== 每个台上次能播的线路 ==================== */

    private static final String K_LIVE_URL = "live_u";
    /** 最多记 60 个台，超了丢最早的 —— 一条字符串不能无限长。 */
    private static final int LIVE_URL_MAX = 60;

    /**
     * v2.10：每个台记住「上次真的起播成功的那一路」。
     *
     * 之前 pickChannel 硬编码 urlIdx = 0，等于每次进台都从排序第一的源开始，
     * 而排序第一的源未必在这台电视/这条宽带上通 —— 用户看到的就是「每个台都在转圈」。
     * 记住成功过的下标，下次直接从那一路起播。
     *
     * 存储格式：`台名=下标;台名=下标;`（台名里的 `=`/`;` 会被换成 `_`）。
     */
    public static int liveUrlIdx(String name) {
        if (sp == null || name == null) {
            return 0;
        }
        String key = urlKey(name);
        if (key.length() == 0) {
            return 0;
        }
        try {
            String all = sp.getString(K_LIVE_URL, "");
            if (all == null || all.length() == 0) {
                return 0;
            }
            String hit = key + "=";
            // 按 ; 切开再逐条比对：直接 indexOf 会把 "HDTV=3" 里的 "TV=3" 误判成命中。
            String[] parts = all.split(";");
            for (int i = 0; i < parts.length; i++) {
                String one = parts[i].trim();
                if (!one.startsWith(hit)) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(one.substring(hit.length()).trim());
                    return v < 0 ? 0 : v;
                } catch (Throwable ignored) {
                    return 0;
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void saveLiveUrlIdx(String name, int idx) {
        if (sp == null || name == null || idx < 0) {
            return;
        }
        String key = urlKey(name);
        if (key.length() == 0) {
            return;
        }
        try {
            String all = sp.getString(K_LIVE_URL, "");
            if (all == null) {
                all = "";
            }
            StringBuilder sb = new StringBuilder();
            String hit = key + "=";
            // 先收集除自己以外的条目，再只留最近 LIVE_URL_MAX-1 条
            //（新记录写在末尾，所以超限时从头部丢最旧的）。
            String[] parts = all.split(";");
            java.util.ArrayList<String> keep = new java.util.ArrayList<String>();
            for (int i = 0; i < parts.length; i++) {
                String one = parts[i].trim();
                if (one.length() == 0 || one.startsWith(hit)) {
                    continue;
                }
                keep.add(one);
            }
            int from = Math.max(0, keep.size() - (LIVE_URL_MAX - 1));
            for (int i = from; i < keep.size(); i++) {
                sb.append(keep.get(i)).append(';');
            }
            sb.append(key).append('=').append(idx).append(';');
            sp.edit().putString(K_LIVE_URL, sb.toString()).apply();
        } catch (Throwable t) {
            Trace.err("store", "保存直播线路失败", t);
        }
    }

    /** 台名当 key 要收敛：去空白、截短、把会破坏 `名=值;` 格式的字符换掉。 */
    private static String urlKey(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim();
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s.replace('=', '_').replace(';', '_').replace('\n', '_');
    }

    /* ==================== 搜索历史 ==================== */
    /* ==================== 收藏 ==================== */

    private static final String K_LIKE = "like";

    /**
     * 是否已收藏。key 用 {@link #vid(String, int)} 拼的「源_id」。
     *
     * 存成一整条逗号串而不是每个 key 一个布尔：收藏量撑死几十条，
     * 一次读一次写比 SharedPreferences 里散落几十个 key 好维护。
     */
    public static boolean liked(String key) {
        if (sp == null || key == null || key.length() == 0) {
            return false;
        }
        try {
            String s = sp.getString(K_LIKE, "");
            if (s == null || s.length() == 0) {
                return false;
            }
            for (String p : s.split(",")) {
                if (key.equals(p)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Trace.err("store", "读收藏失败", t);
        }
        return false;
    }

    /** 切换收藏状态，返回切换后是否已收藏。 */
    public static boolean toggleLike(String key) {
        if (sp == null || key == null || key.length() == 0) {
            return false;
        }
        try {
            String s = sp.getString(K_LIKE, "");
            StringBuilder sb = new StringBuilder();
            boolean had = false;
            if (s != null && s.length() > 0) {
                for (String p : s.split(",")) {
                    if (p == null || p.length() == 0) {
                        continue;
                    }
                    if (key.equals(p)) {
                        had = true;
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(p);
                }
            }
            if (!had) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(key);
            }
            sp.edit().putString(K_LIKE, sb.toString()).apply();
            return !had;
        } catch (Throwable t) {
            Trace.err("store", "写收藏失败", t);
        }
        return false;
    }

    private static final String K_HIST = "hist";
    private static final int HIST_MAX = 6;

    /** 最近搜过的词，最新的在前。 */
    public static java.util.List<String> history() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (sp == null) {
            return out;
        }
        try {
            String s = sp.getString(K_HIST, "");
            if (s == null || s.length() == 0) {
                return out;
            }
            for (String p : s.split(",")) {
                if (p != null && p.length() > 0) {
                    out.add(p);
                }
            }
        } catch (Throwable t) {
            Trace.err("store", "读搜索历史失败", t);
        }
        return out;
    }

    /** 记一个搜索词：去重、置顶、最多 6 个。 */
    public static void addHistory(String kw) {
        if (sp == null || kw == null) {
            return;
        }
        String w = kw.trim();
        if (w.length() == 0) {
            return;
        }
        // 逗号是分隔符，词里带逗号会拆错，直接不记（实际片名几乎没有逗号）
        if (w.indexOf(',') >= 0) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(w);
            int n = 1;
            for (String p : history()) {
                if (n >= HIST_MAX) {
                    break;
                }
                if (p.equals(w)) {
                    continue;
                }
                sb.append(',').append(p);
                n++;
            }
            sp.edit().putString(K_HIST, sb.toString()).apply();
        } catch (Throwable t) {
            Trace.err("store", "存搜索历史失败", t);
        }
    }
}

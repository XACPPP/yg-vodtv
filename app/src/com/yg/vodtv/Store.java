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
}

package com.yg.vodtv;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 环形错误日志。
 *
 * 为什么需要：电视上出了问题（黑屏、加载不出、播放失败）只能看到一个屏，
 * 没有 adb 就拿不到 logcat。这里把关键失败点记在内存里，自检页直接显示，
 * 拍照发出来就能定位。
 */
public final class Trace {

    private static final int MAX = 120;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Trace() {
    }

    public static synchronized void add(String tag, String msg) {
        String m = msg == null ? "" : msg;
        if (m.length() > 200) {
            m = m.substring(0, 200) + "...";
        }
        String s = FMT.format(new Date()) + " [" + tag + "] " + m;
        LINES.addLast(s);
        while (LINES.size() > MAX) {
            LINES.removeFirst();
        }
    }

    public static void err(String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        if (t != null) {
            sb.append(" : ").append(t.getClass().getSimpleName());
            String tm = t.getMessage();
            if (tm != null && tm.length() > 0) {
                sb.append('(').append(tm).append(')');
            }
        }
        add(tag, sb.toString());
    }

    /** 最近的记录，最多 n 行，按时间正序。 */
    public static synchronized String tail(int n) {
        if (LINES.isEmpty()) {
            return "";
        }
        int skip = LINES.size() - Math.max(1, n);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : LINES) {
            if (i++ < skip) {
                continue;
            }
            sb.append(s).append('\n');
        }
        return sb.toString().trim();
    }

    public static synchronized int size() {
        return LINES.size();
    }
}

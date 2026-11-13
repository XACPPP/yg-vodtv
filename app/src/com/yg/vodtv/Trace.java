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

    /**
     * v2.19：同一条记录同时打到 logcat（tag=YGTV）。
     *
     * 以前只进内存环形队列，等于「只有拿着遥控器的人能在自检页看见」——
     * 验收时想把「这一台到底几秒起播、换了几路线」量出来，只能靠肉眼数秒表。
     * 现在 adb logcat -s YGTV 就能直接读到每一条打点，起播耗时也能算出来。
     */
    private static final String LOGCAT_TAG = "YGTV";

    /**
     * v2.19：除了内存和 logcat，再落一份文件。
     *
     * 这台海信（MStar，Android 5.1）默认不把三方 App 的 logcat 打出来 ——
     * adb logcat 里一条 YGTV 都没有，验收时想量「起播几秒、换台卡了几次」
     * 就只剩人肉掐表。写文件不依赖固件的日志开关：
     * /sdcard/Android/data/com.yg.vodtv/files/ygtv.log，adb pull 或 cat 直接读。
     * 超过 256KB 就改名成 ygtv.log.1 重新开始，避免长期占空间。
     */
    private static java.io.File logFile;
    private static final String NL = "\n";

    private Trace() {
    }

    /** App 启动时调一次；拿不到外部存储就放弃文件日志，不影响其它两个通道。 */
    public static synchronized void attach(android.content.Context c) {
        if (logFile != null || c == null) {
            return;
        }
        try {
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) {
                dir = c.getFilesDir();
            }
            if (dir == null) {
                return;
            }
            logFile = new java.io.File(dir, "ygtv.log");
        } catch (Throwable t) {
            logFile = null;
        }
    }

    private static void appendToFile(String s) {
        if (logFile == null) {
            return;
        }
        try {
            if (logFile.length() > 256 * 1024) {
                java.io.File old = new java.io.File(logFile.getPath() + ".1");
                if (old.exists()) {
                    old.delete();
                }
                logFile.renameTo(old);
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(logFile, true);
            try {
                out.write((s + NL).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
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
        try {
            android.util.Log.i(LOGCAT_TAG, s);
        } catch (Throwable ignored) {
            // logcat 打不出去不影响内存日志，自检页仍然能看
        }
        appendToFile(s);
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

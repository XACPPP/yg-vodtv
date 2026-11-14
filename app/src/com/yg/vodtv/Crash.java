package com.yg.vodtv;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * v2.21：全局崩溃兜底。
 *
 * 为什么需要：老板在电视上切到「付费专业」分组时 App 会崩，但 adb 侧怎么压都复现不出来
 * （见 README 的 v2.21 排查记录）。这台海信固件又屏蔽了三方 App 的 logcat，
 * dropbox 里的崩溃记录大多是 (contents lost) —— 等于崩了也抓不到栈。
 *
 * 所以把「抓栈」这件事放进 App 自己：
 *   1. 装 Thread.setDefaultUncaughtExceptionHandler，崩溃时把完整栈写进 Trace
 *      （内存环 + ygtv.log + logcat 三通道，至少有一条能读到）；
 *   2. 立刻重启回首页 GateActivity，别让电视停在桌面 —— 用户要的是「继续能看」；
 *   3. 20 秒内连续崩溃就不再自重启（防止真·必崩场景变成无限重启闪烁），
 *      只留日志和桌面。
 */
public final class Crash {

    private static final String TAG = "crash";
    /** 连续崩溃判定窗口：这个时间内又崩，就认为自重启没意义。 */
    private static final long LOOP_WINDOW_MS = 20000L;

    private static volatile boolean installed;

    private Crash() {
    }

    public static synchronized void install(Context c) {
        if (installed || c == null) {
            return;
        }
        installed = true;
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                boolean loop = false;
                try {
                    loop = record(t, e, app);
                } catch (Throwable ignored) {
                }
                if (!loop) {
                    try {
                        Intent i = new Intent(app, GateActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        app.startActivity(i);
                        // 给系统的 Activity 启动留点时间，再让本进程退出
                        Thread.sleep(900);
                    } catch (Throwable ignored) {
                    }
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
        replay(app);
        Trace.add(TAG, "崩溃兜底已装载");
    }

    /** 上一次崩溃的完整栈：内部目录落盘，下次启动回放进 Trace，重启后仍然读得到。 */
    private static File lastFile(Context c) {
        try {
            File f = new File(c.getFilesDir(), "crash_last.txt");
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void replay(Context c) {
        File f = lastFile(c);
        if (f == null || !f.exists() || f.length() == 0) {
            return;
        }
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 4000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int r = in.read(b);
                if (r <= 0) {
                    return;
                }
                String all = new String(b, 0, r, "UTF-8");
                Trace.add(TAG, "上次运行崩过，栈如下（共 " + all.split("\\n").length + " 行）");
                int n = 0;
                for (String line : all.split("\\n")) {
                    String s = line.trim();
                    if (s.length() == 0) {
                        continue;
                    }
                    Trace.add("stack", s);
                    if (++n >= 30) {
                        break;
                    }
                }
            } finally {
                in.close();
            }
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 写栈 + 判断是不是「刚崩过又要崩」。
     *
     * @return true 表示短时间内重复崩溃，调用方不要再自重启
     */
    private static boolean record(Thread t, Throwable e, Context app) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            e.printStackTrace(pw);
        } catch (Throwable ignored) {
        }
        pw.flush();
        String stack = sw.toString();

        Trace.add(TAG, "崩溃 " + (t == null ? "?" : t.getName()) + " " + e.getClass().getName()
                + "： " + String.valueOf(e.getMessage()));
        // 逐行落盘：Trace.add 单条上限 200 字，整段塞进去会被截断
        int n = 0;
        for (String line : stack.split("\n")) {
            String s = line.trim();
            if (s.length() == 0) {
                continue;
            }
            Trace.add("stack", s);
            if (++n >= 40) {
                break;
            }
        }

        try {
            File lf = lastFile(app);
            if (lf != null) {
                FileOutputStream lo = new FileOutputStream(lf, false);
                try {
                    lo.write(stack.getBytes("UTF-8"));
                } finally {
                    lo.close();
                }
            }
        } catch (Throwable ignored) {
        }

        long now = System.currentTimeMillis();
        long last = 0L;
        File mark = null;
        try {
            mark = new File(app.getCacheDir(), "crash_at.txt");
            if (mark.exists()) {
                byte[] b = new byte[32];
                java.io.FileInputStream in = new java.io.FileInputStream(mark);
                try {
                    int r = in.read(b);
                    if (r > 0) {
                        last = Long.parseLong(new String(b, 0, r).trim());
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mark != null) {
                FileOutputStream out = new FileOutputStream(mark, false);
                try {
                    out.write(String.valueOf(now).getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return last > 0 && now - last < LOOP_WINDOW_MS;
    }
}

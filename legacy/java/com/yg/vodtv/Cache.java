package com.yg.vodtv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 列表 JSON 的磁盘缓存。
 *
 * 为什么需要：切频道 Tab 必然「加载中…」，因为 Multi.list 的结果只活在内存里，
 * 一页用完就丢。这里把 Multi.Result.raw（已经序列化好的紧凑 JSON）按
 * {频道}_{页码}.json 落到 getCacheDir()/list/，下次进程起来可以直接
 * Multi.decode 上屏，不必再等十个采集源。
 *
 * 设计取舍：
 * 1) 只存字符串，不认识 JSON —— 序列化归 Multi.encode，解析归 Multi.decode，
 *    本类只负责字节的进出，换格式时不用动这里。
 * 2) 写 tmp 再 rename：电视写 flash 偶发掉电，半截文件比没有缓存更糟
 *    （decode 会一直失败）。rename 在同一分区上是原子的。
 * 3) 带宽预算 16MB 上限 / 12MB 回收线：一页 84 条大约 25~40KB，
 *    16MB 够存几百页；超了就从最旧（lastModified）开始删。
 * 4) 不用 android.util.Log：电视上没有 adb，日志走 Trace 才能显示在自检页。
 */
public final class Cache {

    /** 缓存目录总大小上限。 */
    private static final long MAX = 16L * 1024 * 1024;
    /** 清理时删到这个水位以下才收手（留点余量，避免每写一页就触发一次清理）。 */
    private static final long TRIM = 12L * 1024 * 1024;

    /** 短于这个长度的内容一律当成坏数据（一个像样的列表页不可能只有几个字节）。 */
    private static final int MIN_LEN = 20;

    /** 单页读进来最多这么大，防止坏文件把内存吃光。 */
    private static final long MAX_PAGE = 8L * 1024 * 1024;

    /** 缓存根目录；init 之前为 null，所有方法安全返回。 */
    private static File DIR;

    /** 写入计数，每 20 次顺带清理一遍（force=false，够用就不动手）。 */
    private static int ops;

    private Cache() {
    }

    /** 入口：App 启动时调一次，之后所有方法才生效。 */
    public static synchronized void init(Context c) {
        if (c == null) {
            return;
        }
        try {
            File d = new File(c.getCacheDir(), "list");
            if (!d.exists()) {
                d.mkdirs();
            }
            if (d.isDirectory()) {
                DIR = d;
            } else {
                Trace.add("cache", "缓存目录不可用：" + d.getAbsolutePath());
            }
        } catch (Throwable t) {
            Trace.err("cache", "缓存初始化失败", t);
        }
    }

    /** 读一页的原始 JSON；没有或太短都返回 null。 */
    public static String get(int ch, int pg) {
        File dir = DIR;
        if (dir == null) {
            return null;
        }
        File f = new File(dir, ch + "_" + pg + ".json");
        if (!f.isFile() || f.length() < MIN_LEN) {
            return null;
        }
        String s = read(f);
        if (s == null || s.length() < MIN_LEN) {
            return null;
        }
        return s;
    }

    /** 落盘一页原始 JSON。写 tmp 再 rename，异常吞掉。 */
    public static void put(int ch, int pg, String raw) {
        File dir = DIR;
        if (dir == null || raw == null || raw.length() < MIN_LEN) {
            return;
        }
        File dst = new File(dir, ch + "_" + pg + ".json");
        File tmp = new File(dir, ch + "_" + pg + ".json.tmp");
        try {
            if (!write(tmp, raw)) {
                tmp.delete();
                return;
            }
            // Linux 上 rename 可以直接盖旧文件；Windows/部分实现会失败，退回删了再改名
            if (!tmp.renameTo(dst)) {
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    tmp.delete();
                    return;
                }
            }
            int n;
            synchronized (Cache.class) {
                n = ++ops;
            }
            if (n % 20 == 0) {
                prune(false);
            }
        } catch (Throwable t) {
            tmp.delete();
            Trace.err("cache", "写缓存失败 ch=" + ch + " pg=" + pg, t);
        }
    }

    /**
     * 磁盘上的内容跟 raw 是否完全一致 —— 一致就说明「不用重画」。
     * 拿不到磁盘内容（没 init / 没缓存 / 读失败）一律返回 false。
     */
    public static boolean sameAs(int ch, int pg, String raw) {
        if (raw == null) {
            return false;
        }
        String disk = get(ch, pg);
        return disk != null && disk.equals(raw);
    }

    /**
     * 按 lastModified 从旧到新删，删到总量 <= TRIM。
     *
     * @param force false 表示「还没超 MAX 就别动手」，只有 force=true 才无条件压到 TRIM
     */
    private static void prune(boolean force) {
        File dir = DIR;
        if (dir == null) {
            return;
        }
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) {
                return;
            }
            List<File> list = new ArrayList<File>(fs.length);
            long sum = 0;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (f == null || !f.isFile() || !f.getName().endsWith(".json")) {
                    continue;
                }
                list.add(f);
                sum += f.length();
            }
            if (!force && sum <= MAX) {
                return;
            }
            Collections.sort(list, new Comparator<File>() {
                public int compare(File a, File b) {
                    long x = a.lastModified();
                    long y = b.lastModified();
                    if (x < y) {
                        return -1;
                    }
                    return x > y ? 1 : 0;
                }
            });
            long before = sum;
            int n = 0;
            for (int i = 0; i < list.size() && sum > TRIM; i++) {
                File f = list.get(i);
                long len = f.length();
                if (f.delete()) {
                    sum -= len;
                    n++;
                }
            }
            if (n > 0) {
                Trace.add("cache", "清理 " + n + " 个缓存文件，"
                        + (before / 1024) + "KB → " + (sum / 1024) + "KB");
            }
        } catch (Throwable t) {
            // 清理失败不影响读写，静静吞掉
        }
    }

    /** 当前缓存占用字节数（含写剩的 tmp）。 */
    public static long bytes() {
        File dir = DIR;
        if (dir == null) {
            return 0;
        }
        try {
            File[] fs = dir.listFiles();
            if (fs == null) {
                return 0;
            }
            long sum = 0;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (f == null || !f.isFile()) {
                    continue;
                }
                String n = f.getName();
                if (n.endsWith(".json") || n.endsWith(".json.tmp")) {
                    sum += f.length();
                }
            }
            return sum;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 全清（json + tmp）。 */
    public static void clear() {
        File dir = DIR;
        if (dir == null) {
            return;
        }
        try {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (int i = 0; i < fs.length; i++) {
                    File f = fs[i];
                    if (f == null || !f.isFile()) {
                        continue;
                    }
                    String n = f.getName();
                    if (n.endsWith(".json") || n.endsWith(".json.tmp")) {
                        f.delete();
                    }
                }
            }
            synchronized (Cache.class) {
                ops = 0;
            }
        } catch (Throwable t) {
            Trace.err("cache", "清空缓存失败", t);
        }
    }

    /** 后台强制整理一次（开机或退出播放页时用，别在主线程上删文件）。 */
    public static void trimAsync() {
        if (DIR == null) {
            return;
        }
        try {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    prune(true);
                }
            }, "cache-trim");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            Trace.err("cache", "启动清理线程失败", t);
        }
    }

    /* ==================== 内部 IO ==================== */

    private static String read(File f) {
        FileInputStream in = null;
        try {
            long n = f.length();
            if (n <= 0 || n > MAX_PAGE) {
                return null;
            }
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) n];
            int off = 0;
            while (off < buf.length) {
                int k = in.read(buf, off, buf.length - off);
                if (k <= 0) {
                    break;
                }
                off += k;
            }
            if (off <= 0) {
                return null;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable t) {
                    // 忽略
                }
            }
        }
    }

    private static boolean write(File f, String raw) {
        FileOutputStream out = null;
        try {
            byte[] b = raw.getBytes("UTF-8");
            out = new FileOutputStream(f);
            out.write(b);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable t) {
                    // 忽略
                }
            }
        }
    }
}

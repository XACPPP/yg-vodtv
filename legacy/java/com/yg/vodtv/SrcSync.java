package com.yg.vodtv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 源表热更新：远端 sources.json → 本地缓存 → {@link Src#load(String)}。
 *
 * 为什么要有这个类：原来 14 个采集源写死在 Src.java 里，换源、调优先级都得改 Java
 * 重新发版，而电视上装 APK 要 U 盘、要遥控器点，成本极高。现在源表放仓库里的
 * assets/sources.json，由 GitHub Actions 每天巡检后按分数重排并提交，
 * App 只要拉到这份 JSON 就等于换了源，完全不用发版。
 *
 * 三层兜底，任何一层出问题都等价于「什么都没发生」，App 永远用能用的表：
 *   1. Src 的静态块已经把内置表铺进 ALL，进程启动瞬间就有源可用；
 *   2. init() 里读上次缓存的 sources.json（同步、几十 KB、很快），比远端新就用缓存；
 *   3. refresh() 后台拉远端，成功才落盘 + 切换，失败只记一行 Trace。
 */
public final class SrcSync {

    /** 源表在仓库里的固定位置，CI 巡检后直接覆盖这个文件。 */
    public static final String URL =
            "https://raw.githubusercontent.com/XACPPP/yg-vodtv/master/app/src/main/assets/sources.json";

    /** 太短的响应一律当成错误页/限流页，不落盘。 */
    private static final int MIN_BYTES = 100;

    private static Context APP;
    private static File FILE;

    /** 当前源表来源，给自检页看：内置 / 缓存 / 远端。 */
    private static volatile String ORIGIN = "未同步";
    /** 最近一次成功同步的时间戳，失败不动它 —— 自检页能看出「最后一次成功是什么时候」。 */
    private static volatile String STAMP = "";

    /** 后台任务只允许一个在跑，避免自检页连点按钮时堆出一串线程。 */
    private static volatile boolean BUSY;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private SrcSync() {
    }

    /**
     * 必须在任何界面取数之前调用，建议紧贴 Store.init(this)。
     *
     * 为什么同步读盘：缓存文件就几十 KB，读一次是毫秒级；异步读的话首屏可能
     * 先用内置表发起请求，等缓存到了又换表，列表会闪一下重排，体验更差。
     */
    public static void init(Context c) {
        try {
            if (c == null) {
                return;
            }
            APP = c.getApplicationContext();
            FILE = new File(APP.getCacheDir(), "sources.json");
            if (FILE.exists() && FILE.length() > 20) {
                String text = readText(FILE);
                if (text != null && text.trim().length() > 20) {
                    // load 失败时 ALL 原封不动，用 loadSeq 前后比对就能精确判断
                    // 这次到底是「真换成了缓存表」还是「解析失败留了内置」。
                    long seq = Src.loadSeq();
                    Src.load(text);
                    if (Src.loadSeq() != seq) {
                        ORIGIN = "缓存";
                        STAMP = fmt(FILE.lastModified());
                    }
                }
            }
        } catch (Throwable t) {
            // 缓存坏了就当没有，绝对不能拦住启动
            Trace.err("src", "源表缓存读取失败，用内置", t);
        }
    }

    /**
     * 后台拉远端表。界面线程随便调，立即返回。
     *
     * 为什么不在启动时等它：GitHub raw 在国内电视网络下经常几秒甚至超时，
     * 让它挡住首屏等于自杀。先把内置/缓存用起来，拉到再换，用户最多看到一次列表刷新。
     *
     * 为什么把超时压到 6s/10s：Http.get 内部最多重试 3 次，默认的 12s/20s
     * 最坏要将近 100 秒才返回，后台线程会被一个连不上的 host 白占一分多钟；
     * 源表就几十 KB，6 秒连不上基本就是这条路走不通了。
     */
    public static void refresh() {
        if (BUSY || FILE == null) {
            // 没有 init 过就别乱跑，落盘路径都没有
            return;
        }
        BUSY = true;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String text = Http.get(URL, null, 6000, 10000);
                    if (text == null || text.length() <= MIN_BYTES) {
                        Trace.add("src", "远端源表同步失败，用内置");
                        return;
                    }
                    if (!writeText(FILE, text)) {
                        Trace.add("src", "远端源表落盘失败，本次不切换");
                        return;
                    }
                    long seq = Src.loadSeq();
                    Src.load(text);
                    if (Src.loadSeq() == seq) {
                        // 拉到了、也写盘了，但 Src.load 整体放弃（坏 JSON / 0 个可用源）。
                        // 这时缓存里已经是一份没用的表，下次启动还会再读一遍，
                        // 所以把它删掉，让下一轮回到内置。
                        FILE.delete();
                        Trace.add("src", "远端源表同步失败，用内置");
                        return;
                    }
                    ORIGIN = "远端";
                    STAMP = fmt(System.currentTimeMillis());
                    Trace.add("src", "远端源表同步 " + Src.count() + " 个");
                } catch (Throwable t) {
                    // 网络、JSON、磁盘，任何异常都不能影响已经在跑的 App
                    Trace.add("src", "远端源表同步失败，用内置");
                } finally {
                    BUSY = false;
                }
            }
        });
    }

    /**
     * 自检页显示的一行状态，例如
     * 「内置 14 / 当前 14 / 远端 2026-11-13 09:51 已同步」。
     *
     * 刻意把「内置」和「当前」分开报：两个数不一致才说明远端表真的生效了，
     * 这是排查「换了源但没变化」最快的一眼。
     */
    public static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("内置 ").append(Src.builtinCount());
        sb.append(" / 当前 ").append(Src.count());
        sb.append(" / ").append(ORIGIN);
        if (STAMP.length() > 0) {
            sb.append(' ').append(STAMP);
        }
        if (BUSY) {
            sb.append(" 同步中");
        }
        return sb.toString();
    }

    private static String fmt(long ms) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    /** API 22 上没有 Files.readAllBytes，手写一遍；读失败返回 null 交给上层兜底。 */
    private static String readText(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 写文件走 .tmp + rename。
     *
     * 为什么要这么麻烦：电视可能随时被遥控器关掉断电，直接覆盖写一半就重启，
     * 留下半个 JSON 会让下次启动读缓存失败。rename 在同一个文件系统里是原子的，
     * 要么旧文件完整，要么新文件完整。
     */
    private static boolean writeText(File f, String text) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (tmp.renameTo(f)) {
                return true;
            }
            // 某些实现下目标已存在会 rename 失败，删掉再来一次
            if (f.exists() && f.delete() && tmp.renameTo(f)) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp.exists() && !f.exists()) {
                // 别把半截临时文件留在缓存里占地方
                tmp.delete();
            }
        }
    }
}

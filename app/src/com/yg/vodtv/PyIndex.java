package com.yg.vodtv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拼音首字母索引：把「wddcz」翻成「伟大的长征」。
 *
 * 为什么非要在客户端做这件事：14 个采集源都是苹果CMS，服务端只给 vod_en（全拼）
 * 建了索引。实测 wd=wddcz 在 14 个源上只有 bfzy 吐 20 条噪声（我的独裁者 / 伟大的转折 /
 * 温暖的村庄…），《伟大的长征》一条都没有；同一批源换 wd=weidadechangzheng 立刻命中。
 * 也就是说「首字母」这条路服务端根本不存在，只能在离线侧把全库片名的首字母切出来。
 *
 * 索引形态（仓库 app/src/main/assets/py/，由 test/pinyin_index.py 生成，
 * GitHub Actions 每天重建后提交）：
 *   {a..z}.txt —— 每行「首字母<TAB>中文片名」，按行排好序，单桶 5KB~725KB；
 *   manifest.json —— 各桶条数与字节数，只用于自检页显示。
 *
 * 为什么不打包进 APK：assets 目前没进包（build.sh 的 aapt2 link 没有 -A），
 * 而且索引每天更新，跟着 APK 发版等于永远滞后。走 raw.githubusercontent 按需下桶：
 * 用户查哪个字母才下哪个，一个桶几 KB 到几百 KB，下过一次就永久缓存在 /cache/py/。
 */
public final class PyIndex {

    /** 索引在仓库里的固定位置，CI 每天重建后覆盖。 */
    public static final String BASE =
            "https://raw.githubusercontent.com/XACPPP/yg-vodtv/master/app/src/main/assets/py/";

    /** 太小的响应是错误页/限流页，不落盘。 */
    private static final int MIN_BYTES = 200;

    /** 一次最多翻出多少个候选片名。首字母重码很多（wddcz 就有两个），但太多了没用。 */
    private static final int MAX_NAMES = 8;

    /** 输入长度的合理区间：1 个字母命中几万条，没意义；超过 24 个字母基本是乱输。 */
    private static final int MIN_KEY = 2;
    private static final int MAX_KEY = 24;

    private static final Object LOCK = new Object();

    private static Context APP;
    private static File DIR;

    /** 单桶缓存：用户多半连续改几个字母，同一个桶不要反复读盘和拆行。 */
    private static String LAST_L;
    private static String LAST_BODY;

    private PyIndex() {
    }

    /** 建缓存目录并清掉上次留下的半截临时文件。可以在任何线程调用。 */
    public static void init(Context c) {
        try {
            if (c == null) {
                return;
            }
            APP = c.getApplicationContext();
            DIR = new File(APP.getCacheDir(), "py");
            if (!DIR.exists()) {
                DIR.mkdirs();
            }
        } catch (Throwable t) {
            Trace.err("py", "首字母表目录初始化失败，本次搜索走老路", t);
        }
    }

    /**
     * 查一个首字母缩写，返回命中的中文片名（可能为空）。
     *
     * 只有「纯字母、2~24 位」才查：中文、数字、带符号的输入交给原来的 wd= 通路。
     * 桶没缓存时这里会同步下载，最多卡 4s/8s 的 Http 超时 —— 调用方在 io 线程，
     * 不会冻界面；下载失败就当没有，让搜索退回原逻辑，绝不抛异常。
     */
    public static List<String> lookup(String kw) {
        List<String> out = new ArrayList<String>();
        if (DIR == null || kw == null) {
            return out;
        }
        String q = kw.trim().toLowerCase(Locale.US);
        if (q.length() < MIN_KEY || q.length() > MAX_KEY || !isLowerAscii(q)) {
            return out;
        }
        String body = bucket(q.charAt(0));
        if (body == null) {
            return out;
        }
        out = pick(body, q, MAX_NAMES);
        if (!out.isEmpty()) {
            Trace.add("py", "首字母 " + q + " → " + out.size() + " 个候选，首个「" + out.get(0) + "」");
        }
        return out;
    }

    /**
     * 纯函数：从一份桶内容里挑出前缀匹配的片名。
     *
     * 两轮扫描：先收「首字母完全相同」的（用户已经打全了），再收「只是以它开头」的
     * （用户少打了几个字母）。这样 {@link #lookup} 拿到的第一个候选永远是最贴的，
     * 「头号候选查不到就退下一个」这个兜底才站得住。
     *
     * 单独抽成纯函数是为了让 test/harness/PyTab 能离线测匹配规则，不碰网络和磁盘。
     */
    public static List<String> pick(String body, String prefix, int cap) {
        List<String> out = new ArrayList<String>();
        if (body == null || prefix == null || prefix.length() == 0) {
            return out;
        }
        String q = prefix.trim().toLowerCase(Locale.US);
        if (q.length() == 0) {
            return out;
        }
        int max = cap <= 0 ? MAX_NAMES : cap;
        String[] lines;
        try {
            lines = body.split("\n");
        } catch (Throwable t) {
            return out;
        }
        // 第一轮：key 与输入完全一致的
        for (int i = 0; i < lines.length && out.size() < max; i++) {
            if (q.equals(keyOf(lines[i]))) {
                addName(out, lines[i]);
            }
        }
        // 第二轮：以输入开头的长 key
        for (int i = 0; i < lines.length && out.size() < max; i++) {
            String key = keyOf(lines[i]);
            if (key != null && key.length() > q.length() && key.startsWith(q)) {
                addName(out, lines[i]);
            }
        }
        return out;
    }

    /** 取一行的 key 部分（制表符之前，小写）；格式不对返回 null。 */
    private static String keyOf(String ln) {
        if (ln == null) {
            return null;
        }
        int tab = ln.indexOf('\t');
        if (tab <= 0) {
            return null;
        }
        return ln.substring(0, tab).trim().toLowerCase(Locale.US);
    }

    /** 把一行里的片名追加进结果（去重、去空）。 */
    private static void addName(List<String> out, String ln) {
        int tab = ln.indexOf('\t');
        if (tab <= 0 || tab + 1 >= ln.length()) {
            return;
        }
        String name = ln.substring(tab + 1).trim();
        if (name.length() > 0 && !out.contains(name)) {
            out.add(name);
        }
    }

    /** 自检页用：本地缓存了几个桶、占多大。 */
    public static String status() {
        if (DIR == null) {
            return "未初始化";
        }
        File[] fs = DIR.listFiles();
        int n = 0;
        long bytes = 0;
        if (fs != null) {
            for (int i = 0; i < fs.length; i++) {
                if (fs[i].isFile() && fs[i].getName().endsWith(".txt")) {
                    n++;
                    bytes += fs[i].length();
                }
            }
        }
        return "首字母表 " + n + "/23 桶 " + fmt(bytes);
    }

    /** 清缓存时一起清掉，下次搜索会重新下桶。 */
    public static void clear() {
        synchronized (LOCK) {
            LAST_L = null;
            LAST_BODY = null;
        }
        if (DIR == null) {
            return;
        }
        try {
            File[] fs = DIR.listFiles();
            if (fs == null) {
                return;
            }
            for (int i = 0; i < fs.length; i++) {
                if (fs[i].isFile()) {
                    fs[i].delete();
                }
            }
        } catch (Throwable t) {
            Trace.err("py", "首字母表清理失败", t);
        }
    }

    private static boolean isLowerAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    /** 取一个字母的桶内容（本地有就读盘，没有就先下载）。失败返回 null。 */
    private static String bucket(char L) {
        final String key = String.valueOf(L);
        synchronized (LOCK) {
            if (key.equals(LAST_L) && LAST_BODY != null) {
                return LAST_BODY;
            }
        }
        File f = new File(DIR, key + ".txt");
        String text = null;
        try {
            if (f.exists() && f.length() > MIN_BYTES) {
                text = readText(f);
            }
        } catch (Throwable t) {
            text = null;
        }
        if (text == null || text.length() < MIN_BYTES) {
            String net = Http.get(BASE + key + ".txt", null, 4000, 8000);
            if (net == null || net.length() < MIN_BYTES) {
                Trace.add("py", "首字母表 " + key + " 拿不到，本次搜索走老路");
                return null;
            }
            writeText(f, net);
            text = net;
        }
        synchronized (LOCK) {
            LAST_L = key;
            LAST_BODY = text;
        }
        return text;
    }

    private static String fmt(long b) {
        if (b < 1024) {
            return b + "B";
        }
        if (b < 1024 * 1024) {
            return (b / 1024) + "KB";
        }
        return String.format(Locale.US, "%.1fMB", b / 1048576.0);
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

    /** .tmp + rename：电视随时可能被遥控器断电，别留半截文件让下次读破。 */
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
                tmp.delete();
            }
        }
    }
}

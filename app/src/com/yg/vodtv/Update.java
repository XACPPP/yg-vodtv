package com.yg.vodtv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 应用内更新：查 GitHub Releases，比版本号，下载 APK 后交给系统安装器。
 * 这样以后改一行代码推个 tag 就能让电视自己升级，不用再插 U 盘。
 */
public final class Update {

    /** GitHub 仓库（发布 Release 用）。换仓库只改这两行。 */
    public static final String OWNER = "XACPPP";
    public static final String REPO = "yg-vodtv";
    private static final String LATEST = "https://api.github.com/repos/" + OWNER + "/" + REPO
            + "/releases/latest";

    /** 下载目录：放外部存储，系统安装器（另一个 uid）才读得到。 */
    private static final String DIR = "YG影视TV";

    private Update() {
    }

    /** 检查结果。 */
    public static final class Info {
        public boolean ok = false;
        /** 有新版本才非空。 */
        public String version = "";
        public String notes = "";
        public String apkUrl = "";
        public long size = 0;
        public String error = "";
    }

    public interface Cb {
        void done(Info info);
    }

    /** 扔到后台线程调用；回调在主线程。 */
    public static void check(final Context ctx, final Cb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final Info info = checkSync();
                ui.post(new Runnable() {
                    public void run() {
                        if (cb != null) {
                            cb.done(info);
                        }
                    }
                });
            }
        }, "update-check").start();
    }

    static Info checkSync() {
        Info r = new Info();
        try {
            String body = Http.get(LATEST, null);
            if (body == null) {
                r.error = "连不上 GitHub";
                Trace.add("update", "检查更新失败：无响应");
                return r;
            }
            JSONObject o = new JSONObject(body);
            String tag = o.optString("tag_name", "");
            String name = o.optString("name", "");
            String notes = o.optString("body", "");
            r.version = clean(tag.length() > 0 ? tag : name);
            r.notes = notes == null ? "" : notes.trim();
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                String firstApk = "";
                long firstSize = 0;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String url = a.optString("browser_download_url", "");
                    if (!url.toLowerCase().endsWith(".apk")) {
                        continue;
                    }
                    // 优先拿纯 ASCII 名字那个：中文名被 GitHub 转码成 YG.TV.apk，照样能用
                    if (firstApk.length() == 0) {
                        firstApk = url;
                        firstSize = a.optLong("size", 0);
                    }
                    if (url.endsWith("/YGTV.apk")) {
                        firstApk = url;
                        firstSize = a.optLong("size", 0);
                        break;
                    }
                }
                r.apkUrl = firstApk;
                r.size = firstSize;
            }
            r.ok = true;
            boolean newer = compare(r.version, Ver.NAME) > 0;
            Trace.add("update", "最新 " + r.version + "，当前 " + Ver.NAME
                    + (newer ? "，可升级" : "，已是最新"));
            if (!newer) {
                r.version = "";
            }
        } catch (Throwable t) {
            r.error = "检查更新出错";
            Trace.err("update", "检查更新异常", t);
        }
        return r;
    }

    /** tag 可能是 v1.4 / 1.4 / release-1.4，只留数字和点。 */
    static String clean(String tag) {
        if (tag == null) {
            return "";
        }
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        return sb.toString();
    }

    /** 分段比版本号：1.10 > 1.9。 */
    static int compare(String a, String b) {
        String[] x = (a == null ? "" : a).split("\\.");
        String[] y = (b == null ? "" : b).split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = num(x, i);
            int yi = num(y, i);
            if (xi != yi) {
                return xi > yi ? 1 : -1;
            }
        }
        return 0;
    }

    private static int num(String[] arr, int i) {
        if (i >= arr.length) {
            return 0;
        }
        try {
            return Integer.parseInt(arr[i].trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    public interface Progress {
        /** percent < 0 表示总长度未知。 */
        void onProgress(int percent);
    }

    /**
     * 下载目录。必须放在外部存储：系统安装器是另一个 uid，
     * 读不到我们 /data/data 下的私有 cache（Android 5.1 上 file:// 会直接装失败）。
     */
    private static File targetDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                File d = new File(new File(ext, "Download"), DIR);
                if (!d.exists()) {
                    d.mkdirs();
                }
                if (d.isDirectory() && d.canWrite()) {
                    return d;
                }
            }
        } catch (Throwable t) {
            Trace.err("update", "外部存储不可用", t);
        }
        return null;
    }

    /** 后台线程下载，返回文件；失败 null。 */
    public static File download(Context ctx, Info info, Progress p) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = targetDir();
            if (dir == null) {
                Trace.add("update", "外部存储不可写，无法下载安装包");
                return null;
            }
            File f = new File(dir, "YGTV-new.apk");
            if (f.exists() && !f.delete()) {
                Trace.add("update", "旧安装包删不掉，直接覆盖");
            }
            java.net.HttpURLConnection c = Http.open(info.apkUrl, null);
            int code = c.getResponseCode();
            if (code != 200) {
                Trace.add("update", "下载返回 " + code);
                c.disconnect();
                return null;
            }
            long total = info.size > 0 ? info.size : c.getContentLength();
            in = c.getInputStream();
            out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            long got = 0;
            int n;
            int last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (p != null && total > 0) {
                    int pc = (int) (got * 100 / total);
                    if (pc != last) {
                        last = pc;
                        p.onProgress(pc);
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            c.disconnect();
            if (f.length() < 20000) {
                Trace.add("update", "下载的文件太小 " + f.length() + "B");
                return null;
            }
            Trace.add("update", "下载完成 " + f.length() + "B -> " + f.getAbsolutePath());
            return f;
        } catch (Throwable t) {
            Trace.err("update", "下载失败", t);
            return null;
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable t) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable t) {
            }
        }
    }

    /** 调系统安装器（targetSdk 22 可用 file://，但文件必须在安装器读得到的外部存储）。 */
    public static boolean install(Context ctx, File apk) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Trace.add("update", "已调用系统安装器：" + apk.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Trace.err("update", "调安装器失败", t);
            return false;
        }
    }
}

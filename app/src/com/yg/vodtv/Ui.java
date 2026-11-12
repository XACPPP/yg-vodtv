package com.yg.vodtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 统一 UI 尺寸（按 1920 宽等比缩放）与海报异步加载。 */
public final class Ui {

    /* ===== 配色（与 stitch 设计稿对齐） ===== */
    public static final int BG = 0xFF0E1116;
    public static final int BG_CARD = 0xFF1B2029;
    /** 卡片悬浮/次级底色。 */
    public static final int BG_HOVER = 0xFF252C38;
    /** 底部信息栏。 */
    public static final int BG_BAR = 0xFF11141A;
    public static final int FG = 0xFFEEF2F7;
    public static final int FG_DIM = 0xFF9AA6B5;
    /** 简介正文，比 FG_DIM 亮一点，长文更好读。 */
    public static final int FG_BODY = 0xFFB9C3CF;
    public static final int ACCENT = 0xFFFFB300;
    /** 焦点高光（比 ACCENT 略亮）。 */
    public static final int ACCENT_HI = 0xFFFFC107;
    /** 8% 白，分隔线/卡片描边。 */
    public static final int LINE = 0x14FFFFFF;
    /** 角标配色。 */
    public static final int TAG_4K = 0xFF7C4DFF;
    public static final int TAG_EXCLUSIVE = 0xFFE50914;
    public static final int TAG_VIP = 0xFFD4AF37;

    private static float scale = 1f;
    private static int screenW = 1920;
    private static int screenH = 1080;

    private Ui() {
    }

    public static void init(Context c) {
        screenW = c.getResources().getDisplayMetrics().widthPixels;
        screenH = c.getResources().getDisplayMetrics().heightPixels;
        scale = screenW / 1920f;
    }

    public static int px(float v) {
        return Math.max(1, Math.round(v * scale));
    }

    public static int w() {
        return screenW;
    }

    public static int h() {
        return screenH;
    }

    public static TextView text(Context c, String s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 加粗文本，用于标题和 CTA。 */
    public static TextView bold(Context c, String s, float size, int color) {
        TextView t = text(c, s, size, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static GradientDrawable round(int fill, int radius, int stroke, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        if (stroke > 0) {
            g.setStroke(px(stroke), strokeColor);
        }
        return g;
    }

    /** 圆角矩形 + 上下渐变压暗（海报底部压暗，片名压在图上更高级）。 */
    public static GradientDrawable roundGrad(int top, int bottom, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { top, bottom });
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(radius));
        return g;
    }

    /** 焦点态背景：聚焦时琥珀色描边 + 提亮。 */
    public static StateListDrawable focusBg(int fill, int radius, int strokeColor) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] { android.R.attr.state_focused }, round(0xFF2A3242, radius, 3, strokeColor));
        d.addState(new int[] { android.R.attr.state_pressed }, round(0xFF2A3242, radius, 3, strokeColor));
        d.addState(new int[] {}, round(fill, radius, 0, 0));
        return d;
    }

    public static void immersive(android.view.Window w) {
        w.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** ============ 海报加载 ============ */

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** 占位图按尺寸缓存，避免每张卡片都新画一张。加个上限防止尺寸组合把内存吃满。 */
    private static final Map<String, Bitmap> PLACEHOLDERS = new HashMap<String, Bitmap>();
    private static final int PLACEHOLDER_MAX = 24;

    /** 已经彻底取不到的地址：同一部片不反复重试，免得每次滚动都打十几次请求。 */
    private static final Set<String> DEAD = new HashSet<String>();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 载入海报。三种结果都可辨认：
     * 1) 成功 → 真图；
     * 2) 还没好 → 灰底「载入中」；
     * 3) 彻底失败 → 片名首字色块（不是死灰块），并在 Trace 里留痕。
     */
    public static void loadPoster(final ImageView iv, final String url, final int w, final int h) {
        loadPoster(iv, url, w, h, null);
    }

    public static void loadPoster(final ImageView iv, final String url, final int w, final int h,
                                  final String name) {
        if (iv == null) {
            return;
        }
        if (url == null || url.length() == 0) {
            iv.setTag("__none__");
            iv.setImageBitmap(letter(w, h, name));
            return;
        }
        final String key = url;
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        if (DEAD.contains(key)) {
            iv.setImageBitmap(letter(w, h, name));
            return;
        }
        // 先放占位，图到了再覆盖：这样一眼能看出是没图还是没加载出来
        iv.setImageBitmap(loading(w, h));
        POOL.execute(new Runnable() {
            public void run() {
                final Bitmap b = fetchAny(url, w, h);
                if (b == null) {
                    DEAD.add(key);
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (key.equals(iv.getTag())) {
                                iv.setImageBitmap(letter(w, h, name));
                            }
                        }
                    });
                    return;
                }
                CACHE.put(key, b);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (key.equals(iv.getTag())) {
                            iv.setImageBitmap(b);
                        }
                    }
                });
            }
        });
    }

    /** 换源后想强制重试某张图时调用（自检页用）。 */
    public static void forgetFailures() {
        DEAD.clear();
    }

    /**
     * 多跳兜底，但不再串行等满三轮。
     *
     * 之前是 https 失败等完 12s 连接超时、再试 http 等 12s、再试图片代理，
     * 电视上一篇海报要 3 秒起（日志里 [selftest] 海报 OK 3018ms），整墙就废了。
     *
     * 现在的策略：
     * 1) 先用「快超时」试原始地址（3s/5s）——正常网络下 1 秒内就回来了；
     * 2) 原始地址失败就直接上图片代理（同一份 UA，代理侧更容易连通），
     *    用正常超时，因为代理慢一点没关系，它几乎不会失败；
     * 3) 代理也失败才回到 http 降级 + 正常超时，作为最后一道。
     *
     * 这样最常见的两种场景都只花一跳：图床直连通的走 1，图床被墙的走 2。
     */
    private static Bitmap fetchAny(String raw, int w, int h) {
        String up = toHttps(raw);

        // 第一跳：原始地址，快超时
        Bitmap b = fetch(up, w, h, 3000, 5000);
        if (b != null) {
            return b;
        }

        // 第二跳：图片代理（针对「图床域名在国内不可达」）
        b = fetch(proxied(raw), w, h, 6000, 12000);
        if (b != null) {
            Trace.add("poster", "直连失败，图片代理兜底成功 " + Http.shortUrl(raw));
            return b;
        }

        // 第三跳：http 降级（针对「证书链太旧/只监听 80 端口」的老图床）
        String down = httpize(raw);
        if (!down.equals(up)) {
            b = fetch(down, w, h, 6000, 10000);
            if (b != null) {
                Trace.add("poster", "https/代理都没取到，http 兜底成功 " + Http.shortUrl(raw));
                return b;
            }
        }

        Trace.add("poster", "海报三跳全失败（原始/代理/http）" + Http.shortUrl(raw));
        return null;
    }

    /** 公共图片代理：wsrv.nl 与 images.weserv.nl 是同一个服务，实测能取到各家图床的图。 */
    public static String proxied(String url) {
        try {
            return "https://wsrv.nl/?url=" + URLEncoder.encode(url, "UTF-8");
        } catch (Throwable t) {
            return url;
        }
    }

    /** 统一走 https（片源海报 https/http 都在）。 */
    public static String toHttps(String url) {
        if (url.startsWith("http://")) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    /** 降级到 http（保留给确实没有 https 的源）。 */
    public static String httpize(String url) {
        if (url.startsWith("https://")) {
            return "http://" + url.substring(8);
        }
        return url;
    }

    /** 灰底 + 「载入中」占位图。 */
    public static Bitmap loading(int w, int h) {
        return placeholder(w, h, "载入中");
    }

    /** 灰底 + 文字占位图（老接口，等价于 loading）。 */
    public static Bitmap placeholder(int w, int h) {
        return placeholder(w, h, "暂无封面");
    }

    private static Bitmap placeholder(int w, int h, String s) {
        int bw = Math.max(8, w);
        int bh = Math.max(8, h);
        String k = bw + "x" + bh + s;
        Bitmap hit = PLACEHOLDERS.get(k);
        if (hit != null) {
            return hit;
        }
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(b);
        cv.drawColor(BG_CARD);
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(FG_DIM);
        p.setTextSize(Math.max(12, bh / 9f));
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(s, bw / 2f, bh / 2f + p.getTextSize() / 3f, p);
        remember(k, b);
        return b;
    }

    /**
     * 首字色块：取片名第一个字的网络可达色，做成识别度更高的失败占位。
     * 比「暂无封面」四个字强——至少能一眼认出这是哪部片。
     */
    public static Bitmap letter(int w, int h, String name) {
        int bw = Math.max(8, w);
        int bh = Math.max(8, h);
        String ch = firstChar(name);
        String k = "L" + bw + "x" + bh + ch;
        Bitmap hit = PLACEHOLDERS.get(k);
        if (hit != null) {
            return hit;
        }
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(b);
        cv.drawColor(palette(ch));
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(0xE6FFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(Math.max(12, Math.min(bw, bh) * 0.42f));
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(ch, bw / 2f, bh / 2f + p.getTextSize() / 3f, p);
        remember(k, b);
        return b;
    }

    private static void remember(String key, Bitmap b) {
        if (PLACEHOLDERS.size() >= PLACEHOLDER_MAX) {
            PLACEHOLDERS.clear();
        }
        PLACEHOLDERS.put(key, b);
    }

    private static String firstChar(String name) {
        if (name == null || name.trim().length() == 0) {
            return "?";
        }
        String s = name.trim();
        int end = s.offsetByCodePoints(0, 1);
        return s.substring(0, end).toUpperCase();
    }

    /** 固定的一组低饱和深色，保证白字可读，也避免整墙花花绿绿。 */
    private static int palette(String ch) {
        int[] cs = { 0xFF2B3A55, 0xFF3B2F4A, 0xFF1F3B36, 0xFF4A3328, 0xFF2F3F4A,
                0xFF3E2A2A, 0xFF243447, 0xFF3A3A22 };
        int i = Math.abs(ch.hashCode()) % cs.length;
        return cs[i];
    }

    /**
     * 自检用：完全按真实加载链（https → http → 图片代理）取一次图。
     * @return null 表示成功；否则是能直接显示在自检页的失败原因。
     */
    public static String probe(String url) {
        if (url == null || url.length() == 0) {
            return "片源没给海报地址";
        }
        Bitmap b = fetchAny(url, px(300), px(450));
        if (b == null) {
            return "三种方式都取不到（https / http / 图片代理），详见下方日志";
        }
        return null;
    }

    private static Bitmap fetch(String url, int reqW, int reqH) {
        return fetch(url, reqW, reqH, 0, 0);
    }

    /** 带超时的取图；connectMs/readMs 传 0 表示用 Http 的默认 12s/20s。 */
    private static Bitmap fetch(String url, int reqW, int reqH, int connectMs, int readMs) {
        try {
            byte[] data = connectMs > 0
                    ? Http.getBytes(url, null, connectMs, readMs)
                    : Http.getBytes(url, null);
            if (data == null) {
                return null;
            }
            if (data.length < 200) {
                Trace.add("poster", "数据太短 " + data.length + "B " + Http.shortUrl(url));
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sw = o.outWidth;
            int sh = o.outHeight;
            if (sw <= 0 || sh <= 0) {
                Trace.add("poster", "不是图片 " + data.length + "B " + Http.shortUrl(url));
                return null;
            }
            int sample = 1;
            if (reqW > 0) {
                while (sw / (sample * 2) >= reqW) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b == null) {
                Trace.add("poster", "解码失败 " + sw + "x" + sh + " " + Http.shortUrl(url));
                return null;
            }
            if (b.getWidth() > reqW * 2 || b.getHeight() > reqH * 2) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, reqW), Math.max(1, reqH), true);
                if (s != b) {
                    b.recycle();
                    b = s;
                }
            }
            return b;
        } catch (Throwable t) {
            Trace.err("poster", "加载异常 " + Http.shortUrl(url), t);
            return null;
        }
    }
}

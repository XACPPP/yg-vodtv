package com.yg.vodtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 统一 UI 尺寸（按 1920 宽等比缩放）与海报异步加载。 */
public final class Ui {

    public static final int BG = 0xFF0E1116;
    public static final int BG_CARD = 0xFF1B2029;
    public static final int FG = 0xFFEEF2F7;
    public static final int FG_DIM = 0xFF9AA6B5;
    public static final int ACCENT = 0xFFFFB300;

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

    /** 占位图按尺寸缓存，避免每张卡片都新画一张。 */
    private static final Map<String, Bitmap> PLACEHOLDERS = new HashMap<String, Bitmap>();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void loadPoster(final ImageView iv, final String url, final int w, final int h) {
        if (iv == null) {
            return;
        }
        if (url == null || url.length() == 0) {
            iv.setTag("__none__");
            iv.setImageBitmap(placeholder(w, h));
            return;
        }
        final String key = url;
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        // 先放「暂无封面」，图到了再覆盖：这样一眼能看出是没图还是没加载出来
        iv.setImageBitmap(placeholder(w, h));
        POOL.execute(new Runnable() {
            public void run() {
                final Bitmap b = fetchAny(url, w, h);
                if (b == null) {
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

    /**
     * https 优先，失败再用 http 兜底。
     * 之前一刀切走 http（为绕开 Android 5.1 旧根证书），但有些网络会拦 http，
     * 结果就是整墙黑框。两条协议都试过才算真失败，并写进 Trace。
     */
    private static Bitmap fetchAny(String raw, int w, int h) {
        String up = toHttps(raw);
        Bitmap b = fetch(up, w, h);
        if (b != null) {
            return b;
        }
        String down = httpize(raw);
        if (!down.equals(up)) {
            b = fetch(down, w, h);
            if (b != null) {
                Trace.add("poster", "https 没取到，http 兜底成功 " + Http.shortUrl(raw));
                return b;
            }
        }
        Trace.add("poster", "海报取不到 " + Http.shortUrl(raw));
        return null;
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

    /** 灰底 + 「暂无封面」占位图，替代纯黑块。 */
    public static Bitmap placeholder(int w, int h) {
        int bw = Math.max(8, w);
        int bh = Math.max(8, h);
        String k = bw + "x" + bh;
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
        cv.drawText("暂无封面", bw / 2f, bh / 2f + p.getTextSize() / 3f, p);
        PLACEHOLDERS.put(k, b);
        return b;
    }

    private static Bitmap fetch(String url, int reqW, int reqH) {
        try {
            byte[] data = Http.getBytes(url, Api.REFERER);
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

package com.yg.vodtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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

    /* ===== surface 层级（stitch 设计稿 M3 令牌，界面分区靠它分层） ===== */
    /** 最低层：整页底色。 */
    public static final int SURFACE_LOWEST = 0xFF0B0E13;
    /** 低层：分组条凹陷容器 / 次级面板。 */
    public static final int SURFACE_LOW = 0xFF191C21;
    /** 标准卡片底色。 */
    public static final int SURFACE = 0xFF1D2025;
    /** 高层：焦点卡 / 悬浮块。 */
    public static final int SURFACE_HIGH = 0xFF272A30;
    /** 最高层：角标、键帽。 */
    public static final int SURFACE_HIGHEST = 0xFF32353A;
    /** 正文强调色（比 FG 暖，设计稿 on_surface_variant）。 */
    public static final int ON_SURFACE_VARIANT = 0xFFD6C4AC;
    /** 描边色（设计稿 outline_variant）。 */
    public static final int OUTLINE_VARIANT = 0xFF514532;
    /** 品牌浅琥珀，用于顶栏字标（设计稿 primary）。 */
    public static final int PRIMARY_LIGHT = 0xFFFFD79B;
    /** 状态绿（在线/正常）。 */
    public static final int OK_GREEN = 0xFF00E676;

    private static float scale = 1f;
    private static int screenW = 1920;
    private static int screenH = 1080;
    /** 读 assets 里的台标要用，init 时保存一份。 */
    private static Context APP;

    private Ui() {
    }

    public static void init(Context c) {
        APP = c.getApplicationContext();
        screenW = c.getResources().getDisplayMetrics().widthPixels;
        screenH = c.getResources().getDisplayMetrics().heightPixels;
        scale = screenW / 1920f;
        // 海报磁盘缓存目录：第二次开 App、或者翻回已看过的页，直接读盘不走网络
        try {
            DISK = new java.io.File(c.getCacheDir(), "poster");
            if (!DISK.exists()) {
                DISK.mkdirs();
            }
            // 启动时后台核一次总大小，老版本留下的超大目录会被顺手收掉
            trimDiskAsync();
        } catch (Throwable t) {
            DISK = null;
        }
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

    /**
     * 海报底部压暗蒙层，严格照搬设计稿的
     * `linear-gradient(to top, rgba(14,17,22,.96) 0%, rgba(14,17,22,.6) 35%, transparent 65%)`。
     *
     * v2.7 是「42% 高的两色渐变」，只有两种颜色、也没有 35% 那个拐点，
     * 结果片名区域压得不够、海报上半张却被糊上一层灰 —— 这是卡片看起来「闷」的原因。
     *
     * 不用 GradientDrawable 的三色构造：`setColors(int[], float[])` 要 API 24，
     * 本项目 minSdk 21，只能用 LinearGradient 自己画。
     */
    public static Drawable posterScrim(final int radius) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader shader;

            @Override
            protected void onBoundsChange(Rect b) {
                if (b.height() <= 0) {
                    return;
                }
                // BOTTOM_TOP 的 y0 是底边：0% 处最黑，65% 往上完全透明
                shader = new LinearGradient(0, b.height(), 0, 0,
                        new int[] { 0xF50E1116, 0x990E1116, 0x000E1116, 0x000E1116 },
                        new float[] { 0f, 0.35f, 0.65f, 1f }, Shader.TileMode.CLAMP);
                p.setShader(shader);
            }

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                if (shader == null) {
                    onBoundsChange(b);
                }
                c.drawRect(b, p);
            }

            @Override
            public void setAlpha(int a) {
                p.setAlpha(a);
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
                p.setColorFilter(cf);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /**
     * 分类条左右的渐隐蒙层（设计稿 .scrim-channel-rail-left / -right）。
     *
     * 作用不是装饰：横向滚动条没有滚动条可看，右端突然切断会像「内容坏了」；
     * 蒙一层渐变过去，读起来就是「后面还有，往右按」。左边那道同理。
     */
    public static Drawable railFade(final boolean left, final int width, final int solidColor) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader shader;

            @Override
            protected void onBoundsChange(Rect b) {
                if (b.width() <= 0) {
                    return;
                }
                int solid = solidColor;
                int clear = solidColor & 0x00FFFFFF;
                if (left) {
                    shader = new LinearGradient(0, 0, b.width(), 0,
                            new int[] { solid, clear }, new float[] { 0f, 1f }, Shader.TileMode.CLAMP);
                } else {
                    shader = new LinearGradient(0, 0, b.width(), 0,
                            new int[] { clear, solid }, new float[] { 0f, 1f }, Shader.TileMode.CLAMP);
                }
                p.setShader(shader);
            }

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                if (shader == null) {
                    onBoundsChange(b);
                }
                c.drawRect(b, p);
            }

            @Override
            public void setAlpha(int a) {
                p.setAlpha(a);
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
                p.setColorFilter(cf);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /* ===== 任意多段渐变（v2.8 直播页按 Stitch 严格 1080P 稿对齐时新增） ===== */

    /**
     * 任意方向的多段线性渐变。
     *
     * 设计稿里顶条是 `rgba(14,17,22,0.92) → 0.5 → transparent` 三段，
     * 播放页四边压暗是 `0.72 → 0.3 → 0.85` 这种「中间最亮」的形状，
     * GradientDrawable 的 start/center/end 三色构造只支持对称且要 API 24，
     * minSdk 21 只能自己用 LinearGradient 画。
     *
     * @param vertical true = 从上到下；false = 从左到右
     */
    public static Drawable fade(final boolean vertical, final int[] colors, final float[] stops) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader shader;

            @Override
            protected void onBoundsChange(Rect b) {
                if (b.width() <= 0 || b.height() <= 0) {
                    return;
                }
                if (vertical) {
                    shader = new LinearGradient(0, 0, 0, b.height(), colors, stops,
                            Shader.TileMode.CLAMP);
                } else {
                    shader = new LinearGradient(0, 0, b.width(), 0, colors, stops,
                            Shader.TileMode.CLAMP);
                }
                p.setShader(shader);
            }

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                if (shader == null) {
                    onBoundsChange(b);
                }
                c.drawRect(b, p);
            }

            @Override
            public void setAlpha(int a) {
                p.setAlpha(a);
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
                p.setColorFilter(cf);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /**
     * 播放页的四边压暗：左右一道、上下再叠一道。
     *
     * 设计稿的写法是 `linear-gradient(90deg, .72 → .3 → .85)` 叠
     * `linear-gradient(180deg, .7 → .2 → .75)`——四边都压暗、中间偏上最亮，
     * 这样顶条/底条/左右抽屉压上去都不会出现「贴纸感」的硬边。
     * 底色统一用播放器的影院黑 #0E1116，不用纯黑，纯黑会把视频边缘咬掉一圈。
     */
    public static Drawable playerVeil() {
        Drawable h = fade(false,
                new int[] { 0xB80E1116, 0x4D0E1116, 0xD90E1116 },
                new float[] { 0f, 0.45f, 1f });
        Drawable v = fade(true,
                new int[] { 0xB30E1116, 0x330E1116, 0xBF0E1116 },
                new float[] { 0f, 0.3f, 1f });
        LayerDrawable ld = new LayerDrawable(new Drawable[] { v, h });
        return ld;
    }

    /** 焦点态背景：聚焦时琥珀色描边 + 提亮。 */
    public static StateListDrawable focusBg(int fill, int radius, int strokeColor) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] { android.R.attr.state_focused }, round(0xFF2A3242, radius, 3, strokeColor));
        d.addState(new int[] { android.R.attr.state_pressed }, round(0xFF2A3242, radius, 3, strokeColor));
        d.addState(new int[] {}, round(fill, radius, 0, 0));
        return d;
    }

    /* ===== 设计稿级别的绘制件 ===== */

    /**
     * 整页背景：径向渐变 #151A22 → #0E1116，光源在屏幕上方 35% 处。
     *
     * 之前整页是纯色 {@link #BG}，跟设计稿差的就是这一层——纯色屏幕像 PPT，
     * 有中心光的背景才有「影院暗厅」的纵深感。这是用户说「low」的第一层原因。
     *
     * 用自定义 Drawable 而不是 GradientDrawable：后者只支持线性渐变，
     * 画不出设计稿里的 radial-gradient。Shader 在 onBoundsChange 里重建，
     * 保证任何屏幕尺寸下光源都落在同一个相对位置。
     */
    public static Drawable pageBg() {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader shader;

            @Override
            protected void onBoundsChange(Rect b) {
                if (b.width() <= 0 || b.height() <= 0) {
                    return;
                }
                float cx = b.width() * 0.5f;
                float cy = b.height() * 0.35f;
                float r = Math.max(b.width(), b.height()) * 0.78f;
                shader = new RadialGradient(cx, cy, r,
                        new int[] { 0xFF151A22, 0xFF0E1116 },
                        new float[] { 0f, 1f }, Shader.TileMode.CLAMP);
                p.setShader(shader);
            }

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                if (shader == null) {
                    onBoundsChange(b);
                }
                c.drawRect(b, p);
            }

            @Override
            public void setAlpha(int a) {
                p.setAlpha(a);
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
                p.setColorFilter(cf);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.OPAQUE;
            }
        };
    }

    /**
     * 焦点发光底：外扩一圈半透明琥珀（模拟 CSS box-shadow）+ 内层实心描边。
     *
     * 设计稿里焦点三件套是
     *   box-shadow: 0 0 0 2px #FFC107, 0 0 0 6px rgba(255,193,7,.2), 0 10px 30px rgba(255,179,0,.35)
     * Android 的 GradientDrawable 只有单层 stroke，画不出「双环 + 外发光」，
     * 所以用 LayerDrawable 叠三层：外发光（半透明粗描边）、琥珀环、填充。
     * 焦点卡因此比原来「3px 描边」明显更「亮起来」。
     */
    public static Drawable glowBg(int fill, int radius, int ringColor) {
        int r = px(radius);
        // 第 1 层：最外圈，宽 5px、透明度约 20%，模仿扩散光晕
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.RECTANGLE);
        halo.setCornerRadius(r + px(4));
        halo.setColor(0x00000000);
        halo.setStroke(px(5), (ringColor & 0x00FFFFFF) | 0x33000000);
        // 第 2 层：主环，2px 实心琥珀
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setCornerRadius(r);
        ring.setColor(fill);
        ring.setStroke(px(2), ringColor);
        LayerDrawable ld = new LayerDrawable(new Drawable[] { halo, ring });
        // 外圈左右上下各让出 2px，视觉上环和方块之间有呼吸感
        ld.setLayerInset(0, -px(2), -px(2), -px(2), -px(2));
        return ld;
    }

    /**
     * 焦点态背景（新版）：默认态用 surface 层级底色 + 极淡描边，
     * 聚焦态切到 {@link #glowBg} 的发光环。替换 {@link #focusBg} 用于新页面。
     */
    public static StateListDrawable focusBgGlow(int fill, int radius, int glowColor) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] { android.R.attr.state_focused },
                glowBg(SURFACE_HIGH, radius, glowColor));
        d.addState(new int[] { android.R.attr.state_pressed },
                glowBg(SURFACE_HIGH, radius, glowColor));
        d.addState(new int[] {},
                round(fill, radius, 1, 0x14FFFFFF));
        return d;
    }

    /**
     * 遥控器键帽：设计稿 footer 里那种「深色小方块包一个键名」。
     * primary=true 时用琥珀实心（对应设计稿的 OK 键）。
     */
    public static TextView keycap(Context c, String label, boolean primary) {
        TextView t = bold(c, label, 15, primary ? BG : FG);
        t.setGravity(android.view.Gravity.CENTER);
        int padH = px(primary ? 9 : 8);
        t.setPadding(padH, px(3), padH, px(3));
        t.setBackground(primary
                ? round(ACCENT, 5, 0, 0)
                : round(SURFACE, 5, 1, 0x33FFFFFF));
        return t;
    }

    /**
     * 圆点（状态灯 / 焦点呼吸点）。size 为直径（设计稿单位）。
     */
    public static View dot(Context c, float size, int color) {
        View v = new View(c);
        v.setBackground(round(color, (int) (size / 2f) + 1, 0, 0));
        v.setLayoutParams(new android.widget.FrameLayout.LayoutParams(px(size), px(size)));
        return v;
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

    /**
     * 内存缓存。key 用海报原始地址：同一部片在首页、搜索页、详情页共用一张图。
     * 48MB 大约能放 200 张 234x351 的 RGB_565 海报。
     */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** 占位图按尺寸缓存，避免每张卡片都新画一张。加个上限防止尺寸组合把内存吃满。 */
    private static final Map<String, Bitmap> PLACEHOLDERS = new HashMap<String, Bitmap>();
    private static final int PLACEHOLDER_MAX = 24;

    /**
     * 取不到的地址，但**不是永久黑名单**。
     *
     * 之前是个裸 HashSet，一张图失败一次就永远显示首字色块，用户滚动回来也不重试。
     * 电视上 84 张图同一瞬间轰出去，网络稍有波动就成片失败，整墙就全变灰块——
     * 「很多片没封面」的根因就在这里，而不是片源没给图。
     *
     * 现在记「上次失败时刻」，冷却 90 秒后再试；并且失败次数计入 DEAD_MAX，
     * 真的坏地址（404 之类）最多重试两次就被真正放弃。
     */
    private static final Map<String, long[]> DEAD = new HashMap<String, long[]>();
    private static final long DEAD_COOLDOWN = 90000;
    private static final int DEAD_MAX = 2;

    /** 同时下载几张「当前看得见」的图。比 6 更小，避免瞬时并发把图床惹毛。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    /**
     * 预加载专用线程池，和 POOL 分开。
     *
     * 分开的原因：预加载是「趁现在空闲多下几张」，绝不能抢占当前屏幕的配额。
     * 只有 2 条线程，慢慢下，滚到那儿的时候通常已经在内存里了。
     */
    private static final ExecutorService PREFETCH = Executors.newFixedThreadPool(2);

    /**
     * 读磁盘专用线程池。
     *
     * v2.1 修 bug：以前 fromDisk 是在 POOL（网络池）里做的，于是「磁盘命中」
     * 也要排在同页一堆网络请求后面 —— 明明图就在手机上，第二次打开还是要等。
     * 磁盘读只要几毫秒，必须走独立的两条线程，命中后立刻上屏。
     */
    private static final ExecutorService DISKPOOL = Executors.newFixedThreadPool(2);

    /** 已排进预加载队列的地址，避免同一条反复排队。 */
    private static final java.util.Set<String> QUEUED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 一次最多预取多少张（一页 84 张里先预热前 56 张，剩下的滚到再说）。 */
    private static final int PREFETCH_MAX = 56;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 攒批上屏队列。
     *
     * 以前每张图解码完就单独 MAIN.post 一次，首屏 42 张就是 42 条主线程消息，
     * 一帧贴一张，视觉上就是「海报一张一张跳出来」——图其实早就在磁盘里，
     * 体感却像没加载好。改成先入队，由一次 FLUSH 在同一个消息里贴完整屏。
     *
     * 每项固定是 { ImageView, Bitmap, String url } 三元组。
     */
    private static final java.util.List<Object[]> READY = new java.util.ArrayList<Object[]>();
    private static boolean flushPosted = false;

    /**
     * 一次主线程消息贴完整批。
     *
     * 先持锁把队列拷走并立刻解锁，真正的 setImageBitmap 在锁外做：
     * 上屏过程中后台线程还能继续往 READY 里塞，不会被主线程卡住。
     * tag 对不上说明卡片已被复用/滚走，直接丢掉，不贴错图。
     */
    private static final Runnable FLUSH = new Runnable() {
        public void run() {
            java.util.List<Object[]> batch;
            synchronized (READY) {
                batch = new java.util.ArrayList<Object[]>(READY);
                READY.clear();
                flushPosted = false;
            }
            for (int i = 0; i < batch.size(); i++) {
                Object[] e = batch.get(i);
                ImageView iv = (ImageView) e[0];
                Bitmap b = (Bitmap) e[1];
                String url = (String) e[2];
                if (iv != null && iv.getParent() != null && url.equals(iv.getTag())) {
                    iv.setImageBitmap(b);
                }
            }
        }
    };

    /**
     * 把一张解好的图排进下一批。
     *
     * 判断和入队必须在同一把锁里，否则「已 post / 未 post」会和 FLUSH 里的
     * clear + flushPosted=false 撞车，出现图排进队列却没人贴的情况。
     */
    private static void pushReady(ImageView iv, String url, Bitmap b) {
        if (iv == null || url == null || b == null) {
            return;
        }
        synchronized (READY) {
            READY.add(new Object[] { iv, b, url });
            if (flushPosted) {
                return;
            }
            flushPosted = true;
            MAIN.post(FLUSH);
        }
    }

    /* ============ 磁盘缓存 ============ */

    /** 海报磁盘缓存目录（cacheDir/poster）。电视内存小，重启后全靠它。 */
    private static java.io.File DISK;
    private static int diskOps = 0;

    /**
     * 磁盘缓存上限。
     *
     * v2.2：以前按「文件数 600 张」限制，问题是一张图可能 15KB 也可能 400KB，
     * 600 张最坏能到 200MB+，手机上看着就是「缓存无限变大」。
     * 现在改成按**字节**硬限：
     * - 超过 {@link #DISK_MAX_BYTES} 就开始清理，一直清到 {@link #DISK_TRIM_BYTES} 以下；
     * - 清理按「最久没被访问」删（读命中会 touch 修改时间），常用的图不会先被删掉；
     * - App 启动时在后台先扫一次，防止历史版本留下的目录一直躺着。
     */
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;   // 64MB 触发清理
    private static final long DISK_TRIM_BYTES = 40L * 1024 * 1024;  // 清到 40MB 停

    /**
     * 缓存文件名。
     *
     * v2.1：尺寸不再精确进 key，改成 64px 一档。
     * 首页卡片 234×351、搜索结果卡 200×300、详情页 380×570 —— 精确 key 的话
     * 同一张图会按三种尺寸存三份，磁盘涨得快、命中率却低（换个页面就 miss）。
     * 量化以后几种尺寸大概率落在同一档，一份图多处复用。
     */
    private static String diskName(String url, int w, int h) {
        int hash = (url.hashCode() & 0x7fffffff);
        return hash + "-" + url.length() + "-" + bucket(w) + "x" + bucket(h) + ".jpg";
    }

    /** 把像素尺寸量化到 64 的整数倍，用来减少同一张图存多份。 */
    private static int bucket(int v) {
        if (v <= 0) {
            return 0;
        }
        return ((v + 63) / 64) * 64;
    }

    private static Bitmap fromDisk(String url, int w, int h) {
        if (DISK == null) {
            return null;
        }
        try {
            java.io.File f = new java.io.File(DISK, diskName(url, w, h));
            if (!f.exists() || f.length() < 200) {
                return null;
            }
            // LRU 的「用过」标记：改一下修改时间，清理时就不会先删它。
            // 每分钟最多 touch 一次，避免天天刷文件系统。
            long now = System.currentTimeMillis();
            if (now - f.lastModified() > 60000L) {
                f.setLastModified(now);
            }
            // 必须显式指定 RGB_565：默认 decodeFile 给的是 ARGB_8888（384KB/张），
            // 而 CACHE 只有 48MB，几十张就把它挤爆。海报是不透明 JPEG，
            // 565 看不出差别，内存直接省一半。
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bm == null || bm.getByteCount() < 200) {
                return null;
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 磁盘上是否已有这张图（纯 stat，不解码）。
     *
     * 给 MainActivity 在渲染整屏时做「先判盘、再决定贴什么」用：
     * stat 一次是微秒级，decode 是毫秒级，能先判就不必白白占 DISKPOOL。
     * 判据和 fromDisk 保持一致，否则会出现「说有好、解出来是空」的假命中。
     */
    public static boolean onDisk(String url, int w, int h) {
        try {
            if (DISK == null || url == null || url.length() == 0) {
                return false;
            }
            java.io.File f = new java.io.File(DISK, diskName(url, w, h));
            return f.exists() && f.length() >= 200;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void putDisk(String url, int w, int h, Bitmap b) {
        if (DISK == null || b == null) {
            return;
        }
        java.io.File tmp = null;
        try {
            java.io.File f = new java.io.File(DISK, diskName(url, w, h));
            if (f.exists() && f.length() > 200) {
                f.setLastModified(System.currentTimeMillis());
                return; // 已经有了，别重复写
            }
            tmp = new java.io.File(DISK, f.getName() + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            b.compress(Bitmap.CompressFormat.JPEG, 86, out);
            out.flush();
            out.close();
            if (!tmp.renameTo(f)) {
                tmp.delete();
            } else if (++diskOps % 20 == 0) {
                // 以前每 40 次才查一次，手机上一次性刷 84 张，中间能涨出十几 MB。
                // 改成 20 次，并且检查本身是「按字节算」，不会漏。
                pruneDisk(false);
            }
        } catch (Throwable t) {
            try {
                if (tmp != null) {
                    tmp.delete();
                }
            } catch (Throwable t2) {
            }
        }
    }

    /**
     * 按字节清理磁盘缓存。
     *
     * 排序键用 lastModified（读命中会 touch），所以是「最久没被看过的先删」，
     * 而不是「最早下载的先删」——常看的剧的封面会一直留着。
     *
     * @param force true 表示不管有没有超限都先算一遍（启动时用）
     */
    private static void pruneDisk(boolean force) {
        if (DISK == null) {
            return;
        }
        try {
            java.io.File[] fs = DISK.listFiles();
            if (fs == null || fs.length == 0) {
                return;
            }
            long total = 0;
            for (int i = 0; i < fs.length; i++) {
                total += fs[i].length();
            }
            // 没超上限就不动。force 只表示「这次不管计数器，真的去算一遍大小」，
            // 不代表「可以不超限也删」——删用户正在用的图没必要。
            if (total <= DISK_MAX_BYTES) {
                return;
            }
            java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() {
                public int compare(java.io.File a, java.io.File b) {
                    long x = a.lastModified();
                    long y = b.lastModified();
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
            long freed = 0;
            int dropped = 0;
            for (int i = 0; i < fs.length && total > DISK_TRIM_BYTES; i++) {
                long len = fs[i].length();
                if (fs[i].delete()) {
                    total -= len;
                    freed += len;
                    dropped++;
                }
            }
            Trace.add("poster", "缓存清理：删 " + dropped + " 张，释放 "
                    + (freed / 1024) + "KB，现存 " + (total / 1024) + "KB");
        } catch (Throwable t) {
        }
    }

    /** 启动时在后台扫一次，别让历史遗留的大目录一直躺着。 */
    public static void trimDiskAsync() {
        if (DISK == null) {
            return;
        }
        DISKPOOL.execute(new Runnable() {
            public void run() {
                pruneDisk(true);
            }
        });
    }

    /** 设置页/自检页的「清空缓存」按钮。 */
    public static void clearDisk() {
        if (DISK == null) {
            return;
        }
        try {
            java.io.File[] fs = DISK.listFiles();
            if (fs != null) {
                for (int i = 0; i < fs.length; i++) {
                    fs[i].delete();
                }
            }
            CACHE.evictAll();
            DEAD.clear();
        } catch (Throwable t) {
        }
    }

    /** 自检页显示用：磁盘上有多少张海报。 */
    public static int diskCount() {
        try {
            java.io.File[] fs = DISK == null ? null : DISK.listFiles();
            return fs == null ? 0 : fs.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 自检页显示用：磁盘缓存占了多少字节，以及上限是多少。 */
    public static long diskBytes() {
        try {
            java.io.File[] fs = DISK == null ? null : DISK.listFiles();
            if (fs == null) {
                return 0;
            }
            long n = 0;
            for (int i = 0; i < fs.length; i++) {
                n += fs[i].length();
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 磁盘缓存上限（字节），自检页显示用。 */
    public static long diskLimit() {
        return DISK_MAX_BYTES;
    }

    /* ============ 直连可用性（自适应跳跳） ============ */

    /**
     * 连续多少次「原始地址」失败后，就暂时认为图床直连不通。
     *
     * 国内网络看图床，最常见的情况是整片域名都连不上：每张图都先花 3 秒等超时
     * 再走代理，84 张卡就是 4 分钟纯等待。这里记一个「直连挂了」的截止时刻，
     * 挂了就直接跳第二跳，把那 3 秒省掉；过一会儿再试一次直连，恢复了就自动切回来。
     */
    private static int directFails = 0;
    private static long directDownUntil = 0;
    private static final int DIRECT_FAIL_LIMIT = 3;
    private static final long DIRECT_DOWN_MS = 120000;

    private static boolean directDown() {
        return System.currentTimeMillis() < directDownUntil;
    }

    private static void noteDirect(boolean ok) {
        if (ok) {
            if (directFails > 0 || directDownUntil > 0) {
                Trace.add("poster", "图床直连恢复，回到直连优先");
            }
            directFails = 0;
            directDownUntil = 0;
            return;
        }
        directFails++;
        if (directFails >= DIRECT_FAIL_LIMIT && directDownUntil == 0) {
            directDownUntil = System.currentTimeMillis() + DIRECT_DOWN_MS;
            Trace.add("poster", "图床直连连续失败 " + directFails
                    + " 次，接下来 2 分钟直接走图片代理");
        }
    }

    /** 自检页显示用：直连是不是被判定为不可用。 */
    public static String directState() {
        return directDown() ? "直连已判定不通（走代理）" : "直连优先";
    }

    /* ============ 失败冷却 ============ */

    private static boolean dead(String key) {
        long[] v = DEAD.get(key);
        if (v == null) {
            return false;
        }
        if (v[1] >= DEAD_MAX) {
            return true; // 连续失败太多次，这个地址真没图
        }
        return System.currentTimeMillis() - v[0] < DEAD_COOLDOWN;
    }

    private static void markDead(String key) {
        long[] v = DEAD.get(key);
        if (v == null) {
            DEAD.put(key, new long[] { System.currentTimeMillis(), 1 });
        } else {
            v[0] = System.currentTimeMillis();
            v[1] = v[1] + 1;
        }
    }

    /** 换源后想强制重试某张图时调用（自检页用）。 */
    public static void forgetFailures() {
        DEAD.clear();
    }

    /** 自检页显示用：当前有多少地址在冷却中。 */
    public static int deadCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (long[] v : DEAD.values()) {
            if (v[1] >= DEAD_MAX || now - v[0] < DEAD_COOLDOWN) {
                n++;
            }
        }
        return n;
    }

    /* ============ 对外加载入口 ============ */

    /**
     * 载入海报。三种结果都可辨认：
     * 1) 成功 → 真图；
     * 2) 还没好 → 暗一档的首字色块（看得出在等）；
     * 3) 彻底失败 → 亮白首字色块，并在 Trace 里留痕。
     */
    public static void loadPoster(final ImageView iv, final String url, final int w, final int h) {
        loadPoster(iv, url, w, h, null, false);
    }

    public static void loadPoster(final ImageView iv, final String url, final int w, final int h,
                                  final String name) {
        loadPoster(iv, url, w, h, name, false);
    }

    /**
     * @param channel true 表示这是直播频道卡：占位图用 {@link #channelTile}
     *                （CCTV-8 画 "8"、湖南卫视画 "湖南"），而不是所有人都一样的首字。
     */
    public static void loadPoster(final ImageView iv, final String url, final int w, final int h,
                                  final String name, final boolean channel) {
        if (iv == null) {
            return;
        }
        if (url == null || url.length() == 0) {
            iv.setTag("__none__");
            iv.setImageBitmap(tile(w, h, name, channel));
            return;
        }
        iv.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        if (dead(url)) {
            iv.setImageBitmap(tile(w, h, name, channel));
            return;
        }
        iv.setImageBitmap(loading(w, h, name, channel));
        startLoad(iv, url, w, h, name, channel);
    }

    /** 占位图二选一：直播卡用短标签块，影视海报用首字块。 */
    private static Bitmap tile(int w, int h, String name, boolean channel) {
        return channel ? channelTile(w, h, name) : letter(w, h, name);
    }

    /* ============ 惰性加载：滚到哪下哪 ============ */

    /** 一张待加载的海报。先登记，等它进入视口再真正下载。 */
    private static final class Pending {
        final String url;
        final int w;
        final int h;
        final String name;
        final boolean channel;

        Pending(String url, int w, int h, String name, boolean channel) {
            this.url = url;
            this.w = w;
            this.h = h;
            this.name = name;
            this.channel = channel;
        }
    }

    private static final Map<ImageView, Pending> WAITING =
            new java.util.WeakHashMap<ImageView, Pending>();

    /**
     * 只登记，不下载。
     *
     * 一页 84 张卡如果全部立刻发请求，就要排队好几分钟才轮到最后一张，
     * 期间整墙都是占位图，看起来就是卡。改成进入视口附近才真取。
     */
    public static void loadPosterDeferred(ImageView iv, String url, int w, int h, String name) {
        loadPosterDeferred(iv, url, w, h, name, false);
    }

    /** @param channel 见 {@link #loadPoster(ImageView, String, int, int, String, boolean)}。 */
    public static void loadPosterDeferred(ImageView iv, String url, int w, int h, String name,
                                          boolean channel) {
        if (iv == null) {
            return;
        }
        if (url == null || url.length() == 0) {
            iv.setTag("__none__");
            iv.setImageBitmap(tile(w, h, name, channel));
            return;
        }
        iv.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        if (dead(url)) {
            iv.setImageBitmap(tile(w, h, name, channel));
            return;
        }
        iv.setImageBitmap(loading(w, h, name, channel));
        WAITING.put(iv, new Pending(url, w, h, name, channel));
    }

    /**
     * 把进入视口（含下方一屏预取）的待加载海报真正发出去。
     * 由 MainActivity 在滚动和渲染后调用，主线程执行，只做可见性判断。
     *
     * v2.1 修 bug：以前视口是拿 container 自己的高度算的，而 container 是
     * ScrollView 里面那一整块 rows —— 它的高度等于全部内容高度（12 排 × 351px
     * ≈ 4200px，超过屏幕 4 倍）。于是 `bottom = top + 4200`、`pad = 4200`，
     * 判定条件对任何一张卡都成立，pump 一次就把 84 张全排进队列，
     * 「滚到哪下哪」彻底失效：首屏要等 84 张排完，体感就是「每次都还在加载」。
     * 现在改用 getGlobalVisibleRect —— 它返回的是被所有祖先裁过之后的可见区域，
     * 再加一屏预取，才是真正的视口。
     */
    public static void pump(View container) {
        if (container == null || WAITING.isEmpty()) {
            return;
        }
        android.graphics.Rect vis = new android.graphics.Rect();
        if (!container.getGlobalVisibleRect(vis)) {
            return; // 整个容器都不在屏幕上（比如搜索层盖住了），先不下
        }
        int top = vis.top;
        int bottom = vis.bottom;
        // 多预取一屏，滚动时不会一片占位
        int pad = Math.max(200, vis.height());

        java.util.Iterator<Map.Entry<ImageView, Pending>> it = WAITING.entrySet().iterator();
        // 两张堆：内存里现成的直接进 READY（一次 FLUSH 贴完），
        // 剩下的攒成一批交给 DISKPOOL 的**一个**任务读完。
        final java.util.List<Object[]> batch = new java.util.ArrayList<Object[]>();
        while (it.hasNext()) {
            Map.Entry<ImageView, Pending> e = it.next();
            ImageView iv = e.getKey();
            if (iv == null || iv.getParent() == null) {
                it.remove();
                continue;
            }
            int[] loc = new int[2];
            iv.getLocationOnScreen(loc);
            int y = loc[1];
            if (y + iv.getHeight() < top - pad) {
                continue; // 还在上面，等它滚回来再说
            }
            if (y > bottom + pad) {
                continue; // 太靠下，还没轮到
            }
            Pending p = e.getValue();
            it.remove();
            Bitmap hit = CACHE.get(p.url);
            if (hit != null) {
                pushReady(iv, p.url, hit);
                continue;
            }
            batch.add(new Object[] { iv, p });
        }
        if (batch.isEmpty()) {
            return;
        }
        // 关键：整批只占 DISKPOOL 的一个位置。
        // 以前是 42 张各排一个任务，排在 2 条线程上、各自 MAIN.post 一次，
        // 于是上屏被拉成 42 帧 —— 这就是「一张一张跳出来」的真凶。
        DISKPOOL.execute(new Runnable() {
            public void run() {
                for (int i = 0; i < batch.size(); i++) {
                    Object[] e = batch.get(i);
                    ImageView iv = (ImageView) e[0];
                    Pending p = (Pending) e[1];
                    Bitmap b = fromDisk(p.url, p.w, p.h);
                    if (b != null) {
                        CACHE.put(p.url, b);
                        DEAD.remove(p.url);
                        pushReady(iv, p.url, b);
                        continue;
                    }
                    // 盘上没有才走网络，这张仍然排进 POOL（只有 4 条线程），
                    // 不在这里串行等，否则一张慢图会卡住后面整批。
                    netLoad(iv, p.url, p.w, p.h, p.name, p.channel);
                }
            }
        });
    }

    /**
     * 「切 Tab 渲染完立刻整屏铺满」的入口。
     *
     * 内容和 pump 完全一样，只是语义更明确：MainActivity 在整页重新构建之后
     * 主动叫一次，把已经在视口里的卡片一次性铺出来，不等滚动事件。
     */
    public static void primeNow(View container) {
        pump(container);
    }

    /**
     * 预取成功后，把图直接贴到已经绑定同一地址的卡片上。
     *
     * v2.1：这是「预加载白干」的补丁。预取线程下完图只写了 CACHE/DISK，
     * 而屏幕上那些 ImageView 是在 WAITING 里等 pump 的 —— pump 只认视口，
     * 用户不滚就永远不触发。所以这里主动扫一遍 WAITING，同 url 的直接上屏。
     */
    private static void applyToWaiting(String url, Bitmap b) {
        if (WAITING.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<ImageView, Pending>> it = WAITING.entrySet().iterator();
        int hit = 0;
        while (it.hasNext()) {
            Map.Entry<ImageView, Pending> e = it.next();
            Pending p = e.getValue();
            if (p == null || !url.equals(p.url)) {
                continue;
            }
            ImageView iv = e.getKey();
            it.remove();
            if (iv != null && iv.getParent() != null && url.equals(iv.getTag())) {
                // 不在这里贴：预取是 2 条线程并发下完一张就回调一次，
                // 直接 setImageBitmap 会是「又跳一张」。排进 READY，
                // 让同一批完成的图合并成一次消息贴完。
                pushReady(iv, url, b);
                hit++;
            }
        }
        if (hit > 0) {
            Trace.add("poster", "预取命中直接上屏 " + hit + " 张");
        }
    }

    /**
     * 预加载：把这一页的海报提前下好，滚的时候直接命中内存/磁盘。
     *
     * 用户的原话是「加载不出来或者加载很慢，给人感知很差」。
     * 惰性加载解决的是「别把带宽一次性烧光」，但第一屏仍然要等网络。
     * 所以在渲染完之后，按顺序把这批图排进 2 条后台线程慢慢下：
     * 用户还在看第一排的时候，第三、四排已经在路上了。
     */
    public static void prefetch(final java.util.List<Vod> items, final int w, final int h) {
        if (items == null || items.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        int n = 0;
        for (int i = 0; i < items.size() && n < PREFETCH_MAX; i++) {
            Vod v = items.get(i);
            if (v == null || v.pic == null || v.pic.length() == 0) {
                continue;
            }
            final String url = v.pic;
            if (CACHE.get(url) != null || dead(url)) {
                continue;
            }
            if (!QUEUED.add(url)) {
                continue;
            }
            n++;
            final int fw = w;
            final int fh = h;
            PREFETCH.execute(new Runnable() {
                public void run() {
                    try {
                        if (CACHE.get(url) != null) {
                            return;
                        }
                        Bitmap b = fromDisk(url, fw, fh);
                        if (b == null) {
                            b = fetchAny(url, fw, fh);
                        }
                        if (b == null) {
                            markDead(url);
                            return;
                        }
                        putDisk(url, fw, fh, b);
                        CACHE.put(url, b);
                        DEAD.remove(url);
                        final Bitmap fb = b;
                        // v2.1 修 bug：以前下完就只扔进 CACHE，屏幕上已经绑好的
                        // ImageView 完全不知道 —— 图明明在内存里，卡片还是灰的，
                        // 非得等用户滚一下触发 pump 才显示。用户的原话就是
                        //「预加载完就不管后续封面了」。
                        MAIN.post(new Runnable() {
                            public void run() {
                                applyToWaiting(url, fb);
                            }
                        });
                    } catch (Throwable t) {
                    } finally {
                        QUEUED.remove(url);
                    }
                }
            });
        }
        if (n > 0) {
            Trace.add("poster", "预加载排队 " + n + " 张");
        }
    }

    /** 真正发起下载（内部用，loadPoster 与 pump 共用）。 */
    private static void startLoad(final ImageView iv, final String url, final int w, final int h,
                                  final String name, final boolean channel) {
        final String key = url;
        if (!key.equals(iv.getTag())) {
            return; // 这张卡已经被复用了，别再往它身上贴图
        }
        // 内存命中就地解决，连线程都不用起
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        // APK 内置台标：直接从 assets 读，不走网络也不进磁盘缓存
        if (isAsset(url)) {
            DISKPOOL.execute(new Runnable() {
                public void run() {
                    final Bitmap b = assetBitmap(url, w, h);
                    if (b == null) {
                        MAIN.post(new Runnable() {
                            public void run() {
                                if (key.equals(iv.getTag())) {
                                    iv.setImageBitmap(tile(w, h, name, channel));
                                }
                            }
                        });
                        return;
                    }
                    CACHE.put(key, b);
                    pushReady(iv, key, b);
                }
            });
            return;
        }
        // 先单独问一遍磁盘线程池：几毫秒的事，不该排在网络请求后面
        DISKPOOL.execute(new Runnable() {
            public void run() {
                final Bitmap disk = fromDisk(url, w, h);
                if (disk != null) {
                    CACHE.put(key, disk);
                    DEAD.remove(key);
                    // 交给攒批通道，和同批读盘的其他张合并成一次主线程回帖
                    pushReady(iv, key, disk);
                    return;
                }
                netLoad(iv, url, w, h, name, channel);
            }
        });
    }

    /* ============ 内置台标（APK assets/logo/*.png） ============ */

    /** 台标地址前缀。live.json 里写 "asset:logo/ab12cd34.png" 就走这条路。 */
    public static final String ASSET_PREFIX = "asset:";
    public static boolean isAsset(String url) {
        return url != null && url.startsWith(ASSET_PREFIX);
    }

    /**
     * 从 assets 解一张台标。
     *
     * 为什么台标要打进 APK 而不是走网络：直播卡片一屏 18 张，电视台标都是
     * 几十 KB 的小 PNG，走网络第一屏必等 2~3 秒，而且图床随时会挂
     * （之前 428 个台 logo 全是空串，就是因为指望远端给、远端没给）。
     * 打进 APK 后首屏 0 延迟，且永远可用。
     *
     * 解码按目标高度降采样 + RGB_565，96x54 的台标解出来只占几十 KB 内存。
     */
    private static Bitmap assetBitmap(String url, int reqW, int reqH) {
        if (APP == null) {
            return null;
        }
        java.io.InputStream in = null;
        try {
            String path = url.substring(ASSET_PREFIX.length());
            in = APP.getAssets().open(path);
            byte[] data = readAll(in);
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (reqW > 0 && o.outWidth / (sample * 2) >= reqW) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            // 必须按 ARGB_8888 解：台标 PNG 全是透明底，直接用 RGB_565 会
            // 把透明区压成纯黑 —— 那正是用户说的「全是黑框框」。
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            return b == null ? null : ensureVisible(b);
        } catch (Throwable t) {
            Trace.add("logo", "内置台标读不到 " + url);
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable t) {
            }
        }
    }

    private static byte[] readAll(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 台标「防糊」：iptv-org 抓来的台标有一批是深色字压在透明底上，
     * 直接贴到深色卡片（SURFACE_HIGH #272A30）上等于隐形。
     * 实测 CETV-1 的不透明像素里有 57.9% 亮度低于 60，眼看上去就是一团黑。
     *
     * 做法两件事：
     *   1. 先把台标合成到与卡片同色的不透明底上 —— 原图是透明底的，
     *      ImageView 底下的圆角板是 SURFACE_HIGH，合成后完全看不出接缝，
     *      同时避免 RGB_565 丢 alpha 后透明区变纯黑（那就是用户说的「黑框框」）。
     *   2. 深色字占比重时，垫一层很淡的白，把深色笔画提亮到能看清。
     */
    private static Bitmap ensureVisible(Bitmap src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        long sum = 0;
        long n = 0;
        int dark = 0;
        int stepX = Math.max(1, w / 32);
        int stepY = Math.max(1, h / 18);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = src.getPixel(x, y);
                if (((px >>> 24) & 0xFF) < 40) {
                    continue; // 透明像素不参与判断
                }
                int lum = (((px >> 16) & 0xFF) * 299 + ((px >> 8) & 0xFF) * 587
                        + (px & 0xFF) * 114) / 1000;
                sum += lum;
                n++;
                if (lum < 60) {
                    dark++;
                }
            }
        }
        int avg = n == 0 ? 255 : (int) (sum / n);
        int darkPct = n == 0 ? 0 : (int) (dark * 100 / n);
        // 够亮且不暗：不垫白，只做底色合成
        boolean lift = avg < 110 || darkPct >= 45;
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawColor(SURFACE_HIGH);
            if (lift) {
                cv.drawColor(darkPct >= 55 ? 0x26FFFFFF : 0x1AFFFFFF);
            }
            cv.drawBitmap(src, 0, 0, null);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 磁盘没有，才走网络（在 POOL 里跑）。 */
    private static void netLoad(final ImageView iv, final String url, final int w, final int h,
                                final String name, final boolean channel) {
        final String key = url;
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap b = fetchAny(url, w, h);
                if (b == null) {
                    markDead(key);
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (key.equals(iv.getTag())) {
                                iv.setImageBitmap(tile(w, h, name, channel));
                            }
                        }
                    });
                    return;
                }
                putDisk(url, w, h, b);
                CACHE.put(key, b);
                DEAD.remove(key);
                final Bitmap fb = b;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (key.equals(iv.getTag())) {
                            iv.setImageBitmap(fb);
                        }
                    }
                });
            }
        });
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

        // 第一跳：原始地址，快超时。
        // 已经判定直连不通就整跳跳过，不给每张图都白等 3 秒。
        Bitmap b = null;
        if (!directDown()) {
            b = fetch(up, w, h, 3000, 5000);
            if (b != null) {
                noteDirect(true);
                return b;
            }
            noteDirect(false);
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

    /** 灰底 + 「载入中」占位图（不知道片名时用）。 */
    public static Bitmap loading(int w, int h) {
        return loading(w, h, null);
    }

    /**
     * 载入中的占位：有片名就画成暗一档的首字色块，没有就画灰底「载入中」。
     *
     * 为什么要这样：整墙灰块在视觉上像「坏了」，而暗色首字块像「正在出」，
     * 同样的等待时间，观感差别很大。
     */
    public static Bitmap loading(int w, int h, String name) {
        return loading(w, h, name, false);
    }

    /** @param channel 见 {@link #loadPoster(ImageView, String, int, int, String, boolean)}。 */
    public static Bitmap loading(int w, int h, String name, boolean channel) {
        if (name == null || name.trim().length() == 0) {
            return placeholder(w, h, "载入中");
        }
        return channel ? channelTile(w, h, name) : letterBlock(w, h, name, 0x66FFFFFF);
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
     * 首字色块：取片名第一个字，配一组低饱和深色背景。
     * 比「暂无封面」四个字强——至少能一眼认出这是哪部片。
     */
    public static Bitmap letter(int w, int h, String name) {
        return letterBlock(w, h, name, 0xE6FFFFFF);
    }

    private static Bitmap letterBlock(int w, int h, String name, int charColor) {
        int bw = Math.max(8, w);
        int bh = Math.max(8, h);
        String ch = firstChar(name);
        String k = "L" + bw + "x" + bh + ch + Integer.toHexString(charColor);
        Bitmap hit = PLACEHOLDERS.get(k);
        if (hit != null) {
            return hit;
        }
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(b);
        cv.drawColor(palette(ch));
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(charColor);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(Math.max(12, Math.min(bw, bh) * 0.42f));
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(ch, bw / 2f, bh / 2f + p.getTextSize() / 3f, p);
        remember(k, b);
        return b;
    }

    /**
     * 直播频道的兜底色块（没有台标时用）。
     *
     * 为什么不直接用 {@link #letter}：382 个台里有 34 个是 CCTV-x 打头，
     * 全画一个「C」等于没有信息量 —— 一屏 18 张卡，十几张长得一模一样。
     * 所以这里换成「能区分开来的短标签」：
     *   CCTV-8 / CCTV-8K      → 8 / 8K
     *   CCTV-5+                → 5+
     *   CGTN纪录               → 纪
     *   湖南卫视 / 浙江卫视     → 湖南 / 浙江
     *   广东体育               → 体育
     * 其余取前两字。
     */
    public static Bitmap channelTile(int w, int h, String name) {
        int bw = Math.max(8, w);
        int bh = Math.max(8, h);
        String label = channelLabel(name);
        String k = "T" + bw + "x" + bh + label;
        Bitmap hit = PLACEHOLDERS.get(k);
        if (hit != null) {
            return hit;
        }
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(b);
        cv.drawColor(palette(label));
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(0xF2FFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        // 标签 1~3 个字符，字号跟着缩，免得 "5+" 撑满而 "湖南" 出框
        int n = label.length();
        float factor = n >= 3 ? 0.30f : (n == 2 ? 0.36f : 0.42f);
        p.setTextSize(Math.max(12, Math.min(bw, bh) * factor));
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(label, bw / 2f, bh / 2f + p.getTextSize() / 3f, p);
        remember(k, b);
        return b;
    }

    /** 给兜底色块挑一个「能区分」的短标签，规则见 {@link #channelTile}。 */
    public static String channelLabel(String name) {
        if (name == null) {
            return "?";
        }
        String s = name.trim();
        if (s.length() == 0) {
            return "?";
        }
        String up = s.toUpperCase(Locale.US);
        if (up.startsWith("CCTV")) {
            String rest = s.substring(4);
            while (rest.startsWith("-") || rest.startsWith("_") || rest.startsWith(" ")) {
                rest = rest.substring(1);
            }
            if (rest.length() > 0) {
                return rest.length() > 3 ? rest.substring(0, 3) : rest;
            }
            return "CC";
        }
        if (up.startsWith("CGTN")) {
            String rest = s.substring(4).replace("纪录", "纪").replace("记录", "纪");
            return rest.length() > 0 ? firstChar(rest) : "CG";
        }
        if (up.startsWith("CETV")) {
            String rest = s.substring(4);
            while (rest.startsWith("-")) {
                rest = rest.substring(1);
            }
            return rest.length() > 0 ? rest.substring(0, Math.min(2, rest.length())) : "CE";
        }
        int wei = s.indexOf("卫视");
        if (wei > 0) {
            String head = s.substring(0, wei);
            return head.length() > 2 ? head.substring(0, 2) : head;
        }
        if (s.startsWith("广东") && s.length() == 4) {
            // 广东体育 → 「体育」比「广东」更有辨识度（广东系台有 6 个，
            // 全叫「广东」就又是一排一样的方块）。只在长度刚好 4 时这么干，
            // 避免把「广东广播电视台」这类长名截成怪的半截词。
            return s.substring(2);
        }
        // 兜底取两个字而不是一个字：382 个台里地方台占 234 个，
        // 只取首字会出现「浙」重复 22 次、「广」8 次，看起来像一堆一样的方块。
        // 取两字后唯一标签从 206 提到 241，重复感明显下降。
        return s.length() >= 2 ? s.substring(0, 2) : firstChar(s);
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
     * 自检用：完全按真实加载链（原始 → 图片代理 → http）取一次图。
     * @return null 表示成功；否则是能直接显示在自检页的失败原因。
     */
    public static String probe(String url) {
        if (url == null || url.length() == 0) {
            return "片源没给海报地址";
        }
        Bitmap b = fetchAny(url, px(300), px(450));
        if (b == null) {
            return "三跳都取不到（原始 / 图片代理 / http），详见下方日志";
        }
        return null;
    }

    /** 自检页显示用：一句话说明三块缓存的状态。 */
    public static String cacheStat() {
        long by = diskBytes();
        long lim = diskLimit();
        return "内存 " + CACHE.size() + " 张 · 磁盘 " + diskCount() + " 张/"
                + mb(by) + "（上限 " + mb(lim) + "）· 冷却 " + deadCount() + " 个";
    }

    private static String mb(long b) {
        if (b < 1024 * 1024) {
            return (b / 1024) + "KB";
        }
        return String.format(java.util.Locale.US, "%.1fMB", b / 1048576.0);
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

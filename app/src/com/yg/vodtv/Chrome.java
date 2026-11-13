package com.yg.vodtv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 三个 Activity 共用的界面零件：品牌块、双板块胶囊、键帽提示条、标题行。
 *
 * 为什么要单独抽一个类：v2.6 之前「影视」和「电视」是两个 Activity 各写一套顶栏，
 * 两边字号、内边距、圆角都不一样（一个 8px 一个全胶囊），一眼看就是两个人做的。
 * 抽出来之后两页共用同一份，改一处两边同步。
 *
 * 数值全部取自 stitch 设计稿（项目 9584627652671101173）：
 *   顶栏 72px 高、左右留 56px；品牌方块 40x40 圆角 8；板块胶囊 px-24 py-8；
 *   标题 38px extrabold；副标题 15px FG_DIM；footer 键帽 15px。
 *
 * 注意：本类**不能**被 test/harness 编译的那些类引用（Ui 依赖 android.graphics）。
 */
public final class Chrome {

    /** 顶栏高度（设计稿 72px）。 */
    public static final float BAR_H = 72f;

    private Chrome() {
    }

    /* ==================== 品牌区 ==================== */

    /**
     * 品牌方块：40x40 圆角 8，琥珀→橙黄线性渐变，里面反白写 "YG"。
     *
     * 设计稿是 `bg-gradient-to-br from-[#FFC107] to-[#FF9F00]`；
     * Android 的 GradientDrawable 只能 8 个方向，TOP_BOTTOM 最接近，
     * 对 40px 的小方块视觉差别可以忽略。
     */
    public static FrameLayout brandBlock(Context c) {
        FrameLayout box = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFFFFC107, 0xFFFF9F00 });
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.px(8));
        box.setBackground(g);

        TextView t = Ui.bold(c, "YG", 20, Ui.BG);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.02f);
        box.addView(t, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.setLayoutParams(new LinearLayout.LayoutParams(Ui.px(40), Ui.px(40)));
        return box;
    }

    /** 品牌字样「极清影院」，20px bold、tracking-wider、FG 色。 */
    public static TextView brandText(Context c) {
        TextView t = Ui.bold(c, "极清影院", 20, Ui.FG);
        t.setLetterSpacing(0.08f);
        return t;
    }

    /** 1px 竖直分隔线（设计稿 bg-[#252C38]），height 24。 */
    public static View vDivider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.BG_HOVER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(1), Ui.px(24));
        lp.leftMargin = Ui.px(20);
        lp.rightMargin = Ui.px(20);
        v.setLayoutParams(lp);
        return v;
    }

    /* ==================== 双板块胶囊 ==================== */

    /**
     * 双板块容器：内凹胶囊底（#151A22 + 60% 描边），里面放「电视」「影视」两个 pill。
     * 设计稿 `p-1 bg-[#151A22] border border-[#252C38]/60 rounded-full`。
     */
    public static LinearLayout boardCapsule(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.px(4), Ui.px(4), Ui.px(4), Ui.px(4));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.px(999));
        g.setColor(0xFF151A22);
        g.setStroke(Ui.px(1), 0x99252C38);
        row.setBackground(g);
        return row;
    }

    /**
     * 板块 pill。选中态 = 琥珀实心 + 深色字 + 外发光环；未选 = 灰字，聚焦时描边。
     *
     * 选中的用 LayerDrawable 加一圈半透明外发光（设计稿 .amber-glow），
     * 这是「看起来很贵」和「看起来像 2012 年 PPT」的差别所在。
     */
    public static TextView boardPill(Context c, String label, boolean on) {
        TextView t = on ? Ui.bold(c, label, 17, Ui.BG) : Ui.text(c, label, 17, Ui.FG_DIM);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(24), Ui.px(7), Ui.px(24), Ui.px(7));
        if (on) {
            t.setBackground(glowPill(Ui.ACCENT, true));
        } else {
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[] { android.R.attr.state_focused }, pillBg(0x33FFFFFF, Ui.ACCENT, 1));
            d.addState(new int[] { android.R.attr.state_pressed }, pillBg(0x33FFFFFF, Ui.ACCENT, 1));
            d.addState(new int[] {}, pillBg(0x00000000, 0x00000000, 0));
            t.setBackground(d);
        }
        return t;
    }

    /** 真·胶囊背景（全圆角），可选描边颜色。 */
    public static GradientDrawable pillBg(int fill, int strokeColor, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.px(999));
        g.setColor(fill);
        if (strokeW > 0) {
            g.setStroke(Ui.px(strokeW), strokeColor);
        }
        return g;
    }

    /** 琥珀实心 + 外发光（对应设计稿 .amber-glow）。 */
    private static Drawable glowPill(int fill, boolean glow) {
        GradientDrawable core = pillBg(fill, 0x00000000, 0);
        if (!glow) {
            return core;
        }
        GradientDrawable halo = pillBg(0x00000000, 0x44FFB300, 5);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(new Drawable[] { halo, core });
        ld.setLayerInset(0, -Ui.px(1), -Ui.px(1), -Ui.px(1), -Ui.px(1));
        return ld;
    }

    /* ==================== 顶栏状态 pill ==================== */

    /**
     * 右上角统计胶囊：`1080 频道 · 8 分组` 这种，前半数字用琥珀加粗。
     *
     * 用 SpannableString 而不是拆两个 View：拆开之后基线常常对不齐，
     * 一屏电视上看就是「数字比汉字高半个像素」，很脏。
     */
    public static TextView statPill(Context c, String text) {
        TextView t = Ui.text(c, text, 13, Ui.FG_DIM);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setLetterSpacing(0.04f);
        t.setPadding(Ui.px(16), Ui.px(6), Ui.px(16), Ui.px(6));
        GradientDrawable g = pillBg(0xCC1B2029, 0x33252C38, 1);
        t.setBackground(g);
        return t;
    }

    /* ==================== 标题行 ==================== */

    /** 主标题（设计稿 38px extrabold）。 */
    public static TextView title(Context c, String s) {
        TextView t = Ui.bold(c, s, 38, Ui.FG);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    /**
     * 内联小标签：`LIVE IP-TV`，12px、琥珀字、琥珀 10% 底、琥珀 30% 描边、4px 圆角。
     * 贴着主标题右侧放，用来给标题一「产品感」。
     */
    public static TextView inlineTag(Context c, String s) {
        TextView t = Ui.bold(c, s, 12, Ui.ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setLetterSpacing(0.10f);
        t.setPadding(Ui.px(10), Ui.px(3), Ui.px(10), Ui.px(3));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.px(4));
        g.setColor(0x1AFFB300);
        g.setStroke(Ui.px(1), 0x4DFFB300);
        t.setBackground(g);
        return t;
    }

    /** 副标题（设计稿 15px FG_DIM）。 */
    public static TextView subtitle(Context c, String s) {
        return Ui.text(c, s, 15, Ui.FG_DIM);
    }

    /* ==================== 底部键帽提示 ==================== */

    /**
     * 一行键位提示：`[OK] 播放　　[←→] 换源　　[菜单] 线路　　[返回] 退出`。
     *
     * primaryKey 决定哪个键帽用琥珀实心（设计稿里 OK 键是实心的，
     * 一眼就能看出「这个键最常用」）。
     */
    public static LinearLayout keyHintRow(Context c, String[][] items, String primaryKey) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < items.length; i++) {
            String key = items[i][0];
            String desc = items[i][1];
            boolean primary = primaryKey != null && primaryKey.equals(key);
            TextView cap = Ui.keycap(c, key, primary);
            row.addView(cap);
            TextView d = Ui.text(c, " " + desc, 15, Ui.FG_DIM);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.rightMargin = Ui.px(24);
            dlp.leftMargin = Ui.px(6);
            row.addView(d, dlp);
        }
        return row;
    }

    /** 底部横线（hairline），footer 上沿那条。 */
    public static View hairline(Context c, int topMargin) {
        View v = new View(c);
        v.setBackgroundColor(0x33252C38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(1)));
        lp.topMargin = Ui.px(topMargin);
        v.setLayoutParams(lp);
        return v;
    }

    /** 状态绿点（在线提示）+ 文字。 */
    public static LinearLayout statusDot(Context c, String text, int dotColor) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View d = new View(c);
        d.setBackground(Ui.round(dotColor, 4, 0, 0));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.px(6), Ui.px(6));
        dlp.rightMargin = Ui.px(8);
        row.addView(d, dlp);
        row.addView(Ui.text(c, text, 13, Ui.FG_DIM));
        return row;
    }

    /* ==================== 背景 ==================== */

    /**
     * 给一个容器套上设计稿的径向渐变背景。
     * 直接 setBackgroundDrawable 会丢掉原有的 padding，所以这里只 setBackground。
     */
    public static void applyPageBg(View v) {
        v.setBackground(Ui.pageBg());
    }

    /**
     * 顶栏竖排渐变蒙层：让顶栏和内容之间有过渡，而不是一条生硬的分界线。
     * 返回值直接 addView 到最上层即可，不拦截触摸。
     */
    public static View topScrim(Context c, int heightDp) {
        View v = new View(c);
        v.setBackground(Ui.roundGrad(0xCC0E1116, 0x000E1116, 0));
        v.setClickable(false);
        v.setFocusable(false);
        return v;
    }
}

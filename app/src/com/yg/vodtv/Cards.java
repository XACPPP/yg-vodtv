package com.yg.vodtv;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 影片卡片：海报 + 底部渐变压暗 + 片名压在图上 + 角标。
 * 全部纯代码构建，避免 layout 资源（工具链没有 aapt 之外的资源处理）。
 *
 * v1.9 两处针对卡顿的改动：
 * 1) 支持「惰性加载」（lazy=true）——只登记不下载，滚到视口附近才真取；
 * 2) ImageView 不再设圆角背景。每张卡一个 GradientDrawable 是几十个 Drawable，
 *    这是滚动掉帧的隐性成本；卡片外框已有 focusBg，视觉上不差这一层。
 */
public final class Cards {

    /** 海报宽高比，2:3，与设计稿一致。 */
    public static final float RATIO = 1.5f;

    private Cards() {
    }

    public static View poster(Context c, Vod v, int outerW, int posterH, View.OnClickListener click) {
        return poster(c, v, outerW, posterH, click, true);
    }

    /**
     * @param lazy true = 只登记，滚到视口再下载。首页海报墙必须用 true：
     *             一页 84 张卡同时发请求，队列要几分钟才轮到最后一排，
     *             期间整墙都是「载入中」，看起来就是卡死。
     */
    public static View poster(Context c, Vod v, int outerW, int posterH,
                              View.OnClickListener click, boolean lazy) {
        final int w = outerW;
        final int h = posterH;

        FrameLayout box = new FrameLayout(c);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(Ui.focusBg(0x00000000, 12, Ui.ACCENT));
        int pad = Ui.px(4);
        box.setPadding(pad, pad, pad, pad);

        // 内层给死尺寸：外层在 MainActivity 里是 WRAP_CONTENT，
        // 直接用 MATCH_PARENT 的子 View 会被量成 0 高。
        FrameLayout f = new FrameLayout(c);
        box.addView(f, new FrameLayout.LayoutParams(w, h));

        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setFocusable(false);
        f.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (lazy) {
            Ui.loadPosterDeferred(iv, v.pic, w, h, v.name);
        } else {
            Ui.loadPoster(iv, v.pic, w, h, v.name);
        }

        // 底部渐变压暗：片名压在海报内左下角，卡片才有「内容」而不是贴图
        View scrim = new View(c);
        scrim.setBackground(Ui.roundGrad(0x00000000, 0xE60B0E13, 12));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.round(h * 0.42f));
        slp.gravity = Gravity.BOTTOM;
        f.addView(scrim, slp);

        // 角标：只有真的有 remarks 才建，省掉一半空 TextView
        String remark = v.remarks == null ? "" : v.remarks.trim();
        if (remark.length() > 0) {
            TextView tag = Ui.bold(c, remark, 15, 0xFFFFFFFF);
            tag.setBackground(Ui.round(tagColor(remark), 4, 1, 0x33FFFFFF));
            tag.setPadding(Ui.px(7), Ui.px(2), Ui.px(7), Ui.px(2));
            tag.setSingleLine(true);
            tag.setEllipsize(TextUtils.TruncateAt.END);
            tag.setMaxWidth(Math.round(w * 0.6f));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            tp.topMargin = Ui.px(8);
            tp.rightMargin = Ui.px(8);
            f.addView(tag, tp);
        }

        // 片名压在海报上
        TextView nm = Ui.bold(c, v.name, 22, Ui.FG);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setShadowLayer(Ui.px(6), 0, Ui.px(2), 0xCC000000);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                Math.round(w - Ui.px(20)), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tlp.leftMargin = Ui.px(10);
        tlp.bottomMargin = Ui.px(10);
        f.addView(nm, tlp);

        // 焦点：放大 + 上移，一眼可辨。setZ 让放大的卡片不被同排邻居盖住
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                        .translationY(has ? -Ui.px(4) : 0)
                        .setDuration(180).start();
                v.setZ(has ? 10f : 0f);
            }
        });
        box.setOnClickListener(click);
        return box;
    }

    /** 角标底色（详情页标签也复用）。默认半透明黑，特殊属性才上色。 */
    public static int tagColor(String remarks) {
        if (remarks == null) {
            return 0xCC000000;
        }
        if (remarks.contains("4K") || remarks.contains("蓝光")) {
            return 0xE67C4DFF;
        }
        if (remarks.contains("独播") || remarks.contains("首播")) {
            return 0xE6E50914;
        }
        if (remarks.contains("VIP")) {
            return 0xE6D4AF37;
        }
        return 0xCC000000;
    }
}

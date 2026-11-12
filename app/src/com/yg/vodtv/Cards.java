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
 */
public final class Cards {

    /** 海报宽高比，2:3，与设计稿一致。 */
    public static final float RATIO = 1.5f;

    private Cards() {
    }

    public static View poster(Context c, Vod v, int outerW, int posterH, View.OnClickListener click) {
        final int w = outerW;
        final int h = posterH;

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(Ui.focusBg(0x00000000, 12, Ui.ACCENT));
        int pad = Ui.px(4);
        box.setPadding(pad, pad, pad, pad);

        FrameLayout f = new FrameLayout(c);
        f.setClipToOutline(false);

        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.BG_CARD, 12, 0, 0));
        iv.setFocusable(false);
        f.addView(iv, new FrameLayout.LayoutParams(w, h));
        Ui.loadPoster(iv, v.pic, w, h, v.name);

        // 底部渐变压暗：片名压在海报内左下角，卡片才有「内容」而不是贴图
        View scrim = new View(c);
        scrim.setBackground(Ui.roundGrad(0x00000000, 0xF20B0E13, 12));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, Math.round(h * 0.46f));
        slp.gravity = Gravity.BOTTOM;
        f.addView(scrim, slp);

        if (v.remarks != null && v.remarks.length() > 0) {
            TextView tag = Ui.bold(c, v.remarks, 15, 0xFFFFFFFF);
            int bgColor = tagColor(v.remarks);
            tag.setBackground(Ui.round(bgColor, 4, 1, 0x33FFFFFF));
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
        TextView t = Ui.bold(c, v.name, 22, Ui.FG);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(Ui.px(6), 0, Ui.px(2), 0xCC000000);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                Math.round(w - Ui.px(20)), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tlp.leftMargin = Ui.px(10);
        tlp.bottomMargin = Ui.px(10);
        f.addView(t, tlp);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(w, h);
        box.addView(f, flp);

        // 焦点：放大 + 上移 + 打亮邻居之外的那一张，一眼可辨
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                        .translationY(has ? -Ui.px(4) : 0)
                        .setDuration(180).start();
                v.setAlpha(1f);
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

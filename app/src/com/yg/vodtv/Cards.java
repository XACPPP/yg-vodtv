package com.yg.vodtv;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 影片卡片：海报 + 角标 + 片名，全部是纯代码构建，避免 layout 资源。 */
public final class Cards {

    private Cards() {
    }

    public static View poster(Context c, Vod v, int outerW, int posterH, View.OnClickListener click) {
        int innerW = outerW - Ui.px(12);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(Ui.focusBg(0x00000000, 10, Ui.ACCENT));
        box.setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6));

        FrameLayout f = new FrameLayout(c);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.BG_CARD, 6, 0, 0));
        iv.setFocusable(false);
        f.addView(iv, new FrameLayout.LayoutParams(innerW, posterH));
        Ui.loadPoster(iv, v.pic, innerW, posterH);

        if (v.remarks != null && v.remarks.length() > 0) {
            TextView tag = Ui.text(c, v.remarks, 20, 0xFFFFFFFF);
            tag.setBackground(Ui.round(0xCC000000, 4, 0, 0));
            tag.setPadding(Ui.px(8), Ui.px(3), Ui.px(8), Ui.px(3));
            tag.setSingleLine(true);
            tag.setEllipsize(TextUtils.TruncateAt.END);
            tag.setMaxWidth(Ui.px(160));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            tp.topMargin = Ui.px(8);
            tp.rightMargin = Ui.px(8);
            f.addView(tag, tp);
        }
        box.addView(f);

        TextView t = Ui.text(c, v.name, 26, Ui.FG);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(innerW,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.px(8);
        box.addView(t, tlp);

        box.setOnClickListener(click);
        return box;
    }
}

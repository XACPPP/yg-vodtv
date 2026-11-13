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
 * 影片卡片：海报 + 底部渐变压暗 + 片名/副名两行 + 角标。
 * 全部纯代码构建，避免 layout 资源（工具链没有 aapt 之外的资源处理）。
 *
 * v1.9 两处针对卡顿的改动：
 * 1) 支持「惰性加载」（lazy=true）——只登记不下载，滚到视口附近才真取；
 * 2) ImageView 不再设圆角背景。每张卡一个 GradientDrawable 是几十个 Drawable，
 *    这是滚动掉帧的隐性成本；卡片外框已有 focusBg，视觉上不差这一层。
 *
 * v2.8 照 Stitch「Cinematic Cinema 10-Foot」重做卡片内部：
 * 1) 蒙层从「42% 高的两色渐变」换成设计稿的 to-top 四段 scrim
 *    （0% .96 / 35% .6 / 65% 透明），片名压在渐变最浓的那一段上才读得清；
 * 2) 片名下面加一行 13px 副名（类型 / 地区 / 导演），焦点态转琥珀 ——
 *    一屏 7 张卡只有片名的话，扫视时认不出片子；
 * 3) 焦点卡左下补「● 正在选中」提示（设计稿的选中语义，v2.7 漏了）；
 * 4) 非焦点卡整体压到 76% 不透明度（设计稿 opacity-72），焦点卡不压 ——
 *    这样焦点在哪张卡一眼可辨，而不是只靠一圈描边。
 */
public final class Cards {

    /** 海报宽高比，2:3，与设计稿一致。 */
    public static final float RATIO = 1.5f;

    /** 卡片拿到焦点时的回调，用来喂首页底部的焦点信息 dock。 */
    public interface OnCardFocus {
        void onCardFocus(Vod v);
    }

    private Cards() {
    }

    public static View poster(Context c, Vod v, int outerW, int posterH, View.OnClickListener click) {
        return poster(c, v, outerW, posterH, click, true, null);
    }

    public static View poster(Context c, Vod v, int outerW, int posterH,
                              View.OnClickListener click, boolean lazy) {
        return poster(c, v, outerW, posterH, click, lazy, null);
    }

    /**
     * @param lazy true = 只登记，滚到视口再下载。首页海报墙必须用 true：
     *             一页 84 张卡同时发请求，队列要几分钟才轮到最后一排，
     *             期间整墙都是「载入中」，看起来就是卡死。
     */
    public static View poster(Context c, final Vod v, int outerW, int posterH,
                              View.OnClickListener click, boolean lazy, final OnCardFocus cb) {
        // 外层 box 四周各有 pad 的内边距，内层必须扣掉，
        // 否则 7 列一行会横向溢出（每张多出 2*pad）。
        final int padOuter = Ui.px(4);
        final int w = Math.max(1, outerW - padOuter * 2);
        final int h = posterH;

        FrameLayout box = new FrameLayout(c);
        box.setFocusable(true);
        box.setClickable(true);
        // v2.7：焦点态从「只有一层 3px 描边」升级成设计稿的 tv-focus-glow
        // （描边 + 外发光 + 投影三件套）。纯描边在 10 尺距离上只是「边上有点黄」，
        // 加上外发光才有「这张卡亮了」的感觉 —— 这是设计稿和 v2.6 最直观的差距。
        box.setBackground(Ui.focusBgGlow(0x00000000, 12, Ui.ACCENT));
        box.setClipChildren(false);
        int pad = Ui.px(4);
        box.setPadding(pad, pad, pad, pad);

        // 内层给死尺寸：外层在 MainActivity 里是 WRAP_CONTENT，
        // 直接用 MATCH_PARENT 的子 View 会被量成 0 高。
        final FrameLayout f = new FrameLayout(c);
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

        // 底部渐变压暗：照设计稿的 to-top 四段（0.96 / 0.6 / 透明 / 透明）。
        // 旧版是「42% 高的两色渐变」，最上沿有一道可见的硬边，片名压上去发灰。
        View scrim = new View(c);
        scrim.setBackground(Ui.posterScrim(12));
        f.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.round(h * 0.62f), Gravity.BOTTOM));

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

        // ---- 底部文字区：片名 / 副名 / 聚焦提示 ----
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        // 设计稿 p-3.5 = 14px；左侧多留 2px 抵消描边的视觉内缩
        bottom.setPadding(Ui.px(6), 0, Ui.px(6), Ui.px(2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        blp.leftMargin = Ui.px(8);
        blp.rightMargin = Ui.px(8);
        blp.bottomMargin = Ui.px(10);
        f.addView(bottom, blp);

        final TextView nm = Ui.bold(c, v.name, 22, Ui.FG);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setShadowLayer(Ui.px(6), 0, Ui.px(2), 0xCC000000);
        bottom.addView(nm);

        final String sub = subtitle(v);
        final TextView subView = Ui.text(c, sub, 13, Ui.FG_DIM);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        subView.setShadowLayer(Ui.px(4), 0, Ui.px(1), 0xCC000000);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp2.topMargin = Ui.px(3);
        subView.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
        bottom.addView(subView, slp2);

        // 「● 正在选中」：设计稿焦点卡左下那一行。默认占位（INVISIBLE）而不是 GONE，
        // 否则焦点切换时文字会上下跳一格。
        LinearLayout hint = new LinearLayout(c);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.px(4);
        bottom.addView(hint, hlp);

        View dot = Ui.dot(c, 8, Ui.ACCENT);
        hint.addView(dot);
        TextView hintTxt = Ui.bold(c, "正在选中", 12, Ui.ACCENT);
        hintTxt.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        htlp.leftMargin = Ui.px(6);
        hint.addView(hintTxt, htlp);

        // 焦点：放大 + 上移，一眼可辨。setZ 让放大的卡片不被同排邻居盖住。
        // 非焦点压到 76% 透明（设计稿 opacity-72），焦点回到 100%。
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View fv, boolean has) {
                fv.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                        .translationY(has ? -Ui.px(4) : 0)
                        .setDuration(180).start();
                fv.setZ(has ? 10f : 0f);
                f.animate().alpha(has ? 1f : 0.76f).setDuration(140).start();
                nm.setTextColor(has ? 0xFFFFFFFF : Ui.FG);
                subView.setTextColor(has ? Ui.ACCENT_HI : Ui.FG_DIM);
                hint.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
                if (has && cb != null) {
                    cb.onCardFocus(v);
                }
            }
        });
        // 初始态：还没焦点时也要是暗的，否则一起播就整屏 84 张全亮，看不出层次
        f.setAlpha(0.76f);
        // v2.10：把 Vod 挂到外层 box 上，MainActivity 用 getCurrentFocus().getTag()
        // 就能反查「当前焦点是哪一部」，不用再另外维护一份焦点索引。
        box.setTag(v);
        box.setOnClickListener(click);
        return box;
    }

    /**
     * 副名：设计稿是「全30集 · 王家卫导演」这种形态。
     * 我们的数据里 remarks 常是「更新至30集」「HD中字」，director 单独有，
     * 拼成「更新至30集 · 王家卫」比硬凑「导演」二字更稳（有些片子没导演）。
     */
    private static String subtitle(Vod v) {
        StringBuilder sb = new StringBuilder();
        String remark = v.remarks == null ? "" : v.remarks.trim();
        // remarks 已经在右上角做过角标了，这里只用「集数/清晰度以外」的信息：
        // 年份 + 地区 + 类型 是用户扫视时最需要的三样。
        if (v.year != null && v.year.trim().length() > 0) {
            sb.append(v.year.trim());
        }
        String area = v.area == null ? "" : v.area.trim();
        if (area.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(area);
        }
        String type = v.typeName == null ? "" : v.typeName.trim();
        if (type.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            // 平台给的 typeName 常是「动作片,科幻片」这种逗号串，取第一段就够
            int cut = type.indexOf(',');
            if (cut < 0) {
                cut = type.indexOf('，');
            }
            sb.append(cut > 0 ? type.substring(0, cut) : type);
        }
        // 三样都没有才退回 remarks，总比空一行强
        if (sb.length() == 0) {
            sb.append(remark);
        }
        return sb.toString();
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

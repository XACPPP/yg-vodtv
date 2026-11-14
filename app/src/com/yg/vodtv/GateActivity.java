package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.21：首屏「电视直播 / 影视点播」二选一。
 *
 * 为什么单独开一个 Activity：以前首页就是影视的片库，顶栏右上角两个小胶囊去切板块 ——
 * 老板的原话是「电视和影视这两个切换界面和按钮怎么这么小啊，进软件第一件事不应该就是从
 * 这两个里面选吗」。10 尺视距下靠一个 40px 的胶囊去承载「第一件事」，本来就反直觉。
 *
 * 现在这一屏只干一件事：把两块巨型卡片摆出来，焦点默认落在「电视直播」上，
 * 按 OK 直接进直播（大多数打开电视的场景就是想看台），左右键挪到「影视点播」再 OK 进片库。
 * 视觉按 Stitch 的 Cinematic Amber 稿实现（深底 + 单侧琥珀光 + 大字 + 焦点发光描边）。
 */
public final class GateActivity extends Activity {

    /** 卡片底色：比页面底色亮一档，靠焦点描边和发光来分层。 */
    private static final int CARD_FILL = 0xFF171C24;
    private static final int CARD_W = 860;
    private static final int CARD_H = 620;
    private static final int CARD_GAP = 44;

    private final Handler ui = new Handler();
    private TextView clock;
    private Card cardLive;
    private Card cardVod;

    private final Runnable tickClock = new Runnable() {
        @Override
        public void run() {
            if (clock != null) {
                clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
            }
            ui.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Trace.attach(this);
        Crash.install(this);
        Trace.add("gate", "首页就绪 " + Ver.label());

        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.pageBg());
        root.addView(glow(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.px(300), Gravity.TOP));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar());
        col.addView(cardRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(bottomBar());
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        cardLive.card.requestFocus();
        tickClock.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从片库/直播返回时，焦点回到「上一次选的那个」，别每次都重置
        View want = cardLive.card.hasFocus() ? cardLive.card
                : (cardVod.card.hasFocus() ? cardVod.card : cardLive.card);
        want.requestFocus();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /* ==================== 顶栏 ==================== */

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(48), Ui.px(18), Ui.px(48), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(88)));

        bar.addView(Chrome.brandBlock(this));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.HORIZONTAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.setPadding(Ui.px(14), 0, 0, 0);
        TextView name = Ui.bold(this, "YG 影视TV", 24, Ui.FG);
        name.setLetterSpacing(0.02f);
        titleBox.addView(name);
        TextView pro = Ui.bold(this, "PREMIUM", 10, Ui.ACCENT);
        pro.setPadding(Ui.px(9), Ui.px(3), Ui.px(9), Ui.px(3));
        pro.setBackground(Ui.round(0x14FFB300, 4, 1, 0x40FFB300));
        LinearLayout.LayoutParams proLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        proLp.leftMargin = Ui.px(12);
        titleBox.addView(pro, proLp);
        bar.addView(titleBox);

        bar.addView(hSpacer());

        bar.addView(dotPill("1080P · 已连接", Ui.OK_GREEN));
        clock = Ui.bold(this, "--:--", 20, Ui.FG);
        LinearLayout.LayoutParams clkLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clkLp.leftMargin = Ui.px(26);
        bar.addView(clock, clkLp);
        return bar;
    }

    private View dotPill(String s, int dotColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.px(16), Ui.px(7), Ui.px(18), Ui.px(7));
        box.setBackground(Ui.round(0x14FFFFFF, 20, 1, 0x1FFFFFFF));
        View dot = new View(this);
        dot.setBackground(Ui.round(dotColor, 6, 0, 0));
        box.addView(dot, new LinearLayout.LayoutParams(Ui.px(9), Ui.px(9)));
        TextView t = Ui.text(this, s, 12, Ui.FG_DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.px(9);
        box.addView(t, lp);
        return box;
    }

    /* ==================== 两块大卡 ==================== */

    private View cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.px(8), 0, Ui.px(8));

        cardLive = new Card("电视直播", "LIVE BROADCAST", "500+ 高清频道", 0,
                "江西本地 · 央视 · 卫视 · 付费专业，共 500+ 台",
                new String[] { "CCTV-1 4K", "江西卫视", "高清电影", "时移回看" },
                "按 OK 进入直播", "即点即播 · 秒级响应",
                new Runnable() {
                    @Override
                    public void run() {
                        Trace.add("gate", "进入直播");
                        startActivity(new Intent(GateActivity.this, LivePlayerActivity.class));
                    }
                });

        cardVod = new Card("影视点播", "VIDEO ON DEMAND", "海量高清蓝光", 1,
                "24 个分类 · 14 个片源，最新电影剧集",
                new String[] { "院线大片", "热播连续剧", "纪录片", "4K HDR" },
                "按 OK 进入影视", "每日同步更新",
                new Runnable() {
                    @Override
                    public void run() {
                        Trace.add("gate", "进入影视");
                        startActivity(new Intent(GateActivity.this, MainActivity.class));
                    }
                });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(Ui.px(CARD_W), Ui.px(CARD_H));
        row.addView(cardLive.card, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(Ui.px(CARD_W), Ui.px(CARD_H));
        lp2.leftMargin = Ui.px(CARD_GAP);
        row.addView(cardVod.card, lp2);
        return row;
    }

    /** 单张选择卡：标题 + 副标题 + 胶囊行 + 底部 CTA，焦点态放大并点亮描边。 */
    private final class Card {

        final LinearLayout card;
        private final TextView cta;
        private final Icon icon;
        private final LinearLayout body;
        private final Runnable action;

        Card(String title, String tagEn, String tagCn, int iconKind, String sub,
                String[] chips, String ctaText, String note, Runnable action) {
            this.action = action;

            card = new LinearLayout(GateActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setFocusableInTouchMode(false);
            card.setClickable(true);
            card.setPadding(Ui.px(38), Ui.px(34), Ui.px(38), Ui.px(32));
            card.setBackground(Ui.focusBgGlow(CARD_FILL, 24, 0xB3FFB300));

            body = new LinearLayout(GateActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            card.addView(body, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

            // 头部：英文标签 + 中文补充 + 右上角图标
            LinearLayout head = new LinearLayout(GateActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout tag = new LinearLayout(GateActivity.this);
            tag.setOrientation(LinearLayout.HORIZONTAL);
            tag.setGravity(Gravity.CENTER_VERTICAL);
            tag.setPadding(Ui.px(14), Ui.px(7), Ui.px(16), Ui.px(7));
            tag.setBackground(Ui.round(0x1AFFB300, 18, 1, 0x4DFFB300));
            View dot = new View(GateActivity.this);
            dot.setBackground(Ui.round(Ui.ACCENT, 5, 0, 0));
            tag.addView(dot, new LinearLayout.LayoutParams(Ui.px(8), Ui.px(8)));
            TextView en = Ui.bold(GateActivity.this, tagEn, 11, Ui.ACCENT);
            en.setLetterSpacing(0.16f);
            LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            enLp.leftMargin = Ui.px(9);
            tag.addView(en, enLp);
            head.addView(tag);

            TextView cn = Ui.text(GateActivity.this, tagCn, 12, Ui.FG_DIM);
            LinearLayout.LayoutParams cnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cnLp.leftMargin = Ui.px(14);
            head.addView(cn, cnLp);

            head.addView(hSpacer());

            icon = new Icon(GateActivity.this, iconKind);
            head.addView(icon, new LinearLayout.LayoutParams(Ui.px(74), Ui.px(74)));
            body.addView(head);

            body.addView(vSpacer(1f));

            body.addView(Ui.bold(GateActivity.this, title, 30, Ui.FG));
            TextView subTitle = Ui.text(GateActivity.this, sub, 15, Ui.FG_DIM);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Ui.px(12);
            body.addView(subTitle, subLp);

            LinearLayout chipRow = new LinearLayout(GateActivity.this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipRowLp.topMargin = Ui.px(24);
            for (int i = 0; i < chips.length; i++) {
                TextView chip = Ui.text(GateActivity.this, chips[i], 12, Ui.FG_BODY);
                chip.setPadding(Ui.px(14), Ui.px(6), Ui.px(14), Ui.px(6));
                chip.setBackground(i == 0
                        ? Ui.round(0x1AFFB300, 8, 1, 0x33FFB300)
                        : Ui.round(0x0FFFFFFF, 8, 1, 0x1FFFFFFF));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    lp.leftMargin = Ui.px(10);
                }
                chipRow.addView(chip, lp);
            }
            body.addView(chipRow, chipRowLp);

            body.addView(vSpacer(1f));

            // 底部：CTA + 右侧小字
            LinearLayout foot = new LinearLayout(GateActivity.this);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setGravity(Gravity.CENTER_VERTICAL);

            cta = Ui.bold(GateActivity.this, "▶  " + ctaText, 16, Ui.BG);
            cta.setPadding(Ui.px(26), Ui.px(13), Ui.px(26), Ui.px(13));
            cta.setBackground(Ui.round(Ui.ACCENT, 26, 0, 0));
            foot.addView(cta);

            foot.addView(hSpacer());
            foot.addView(Ui.text(GateActivity.this, note, 12, Ui.FG_DIM));
            body.addView(foot);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Card.this.action.run();
                }
            });
            card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    apply(has);
                }
            });
            apply(false);
        }

        /** 焦点态：卡片放大 1.02 + 描边发光（背景 drawable 自动切），未选中整体压暗。 */
        void apply(boolean on) {
            body.setAlpha(on ? 1f : 0.62f);
            icon.setOn(on);
            card.animate().scaleX(on ? 1.02f : 1f).scaleY(on ? 1.02f : 1f)
                    .setDuration(160).start();
            cta.setBackground(on
                    ? Ui.round(Ui.ACCENT, 26, 0, 0)
                    : Ui.round(0x00FFFFFF, 26, 1, 0x26FFFFFF));
            cta.setTextColor(on ? Ui.BG : Ui.FG_DIM);
        }
    }

    /** 两个线性图标：0=电视机，1=胶片。用 Canvas 画，省得进 res。 */
    private static final class Icon extends View {

        private final int kind;
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Icon(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setOn(boolean b) {
            on = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            int col = on ? Ui.ACCENT : Ui.FG_DIM;
            p.setColor(col);
            p.setStrokeWidth(w * 0.055f);

            // 圆角外框（两块图标共用）
            r.set(w * 0.10f, h * 0.16f, w * 0.90f, h * 0.78f);
            cv.drawRoundRect(r, w * 0.14f, w * 0.14f, p);

            if (kind == 0) {
                // 电视：屏幕中间一条扫描线 + 底座
                float y1 = h * 0.34f;
                float y2 = h * 0.58f;
                cv.drawLine(w * 0.24f, y1, w * 0.56f, y1, p);
                cv.drawLine(w * 0.24f, y2, w * 0.72f, y2, p);
                cv.drawLine(w * 0.36f, h * 0.78f, w * 0.36f, h * 0.90f, p);
                cv.drawLine(w * 0.64f, h * 0.78f, w * 0.64f, h * 0.90f, p);
            } else {
                // 胶片：左右齿孔 + 中间两格
                p.setStyle(Paint.Style.FILL);
                float sz = w * 0.075f;
                RectF hole = new RectF();
                for (int i = 0; i < 3; i++) {
                    float y = h * 0.26f + i * h * 0.17f;
                    hole.set(w * 0.16f, y, w * 0.16f + sz, y + sz * 0.7f);
                    cv.drawRoundRect(hole, 2, 2, p);
                    hole.set(w * 0.84f - sz, y, w * 0.84f, y + sz * 0.7f);
                    cv.drawRoundRect(hole, 2, 2, p);
                }
                p.setStyle(Paint.Style.STROKE);
                cv.drawLine(w * 0.46f, h * 0.16f, w * 0.46f, h * 0.78f, p);
            }
        }
    }

    /* ==================== 底栏 ==================== */

    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(48), 0, Ui.px(48), Ui.px(10));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(56)));
        bar.addView(Chrome.keyHintRow(this, new String[][] {
                { "◀ ▶", "选择板块" },
                { "OK", "进入" },
                { "红键", "自检" },
                { "BACK", "退出" }
        }, "OK"));
        bar.addView(hSpacer());
        TextView v = Ui.text(this, "YG 影视TV " + Ver.label(), 11, Ui.FG_DIM);
        v.setAlpha(0.7f);
        bar.addView(v);
        return bar;
    }

    /* ==================== 装饰与按键 ==================== */

    /** 顶部琥珀光：一层竖直渐变就够，10 尺视距看不出径向与线性的差别。 */
    private View glow() {
        View v = new View(this);
        v.setBackground(Ui.fade(true, new int[] { 0x26FFB300, 0x00FFB300 }, new float[] { 0f, 1f }));
        return v;
    }

    private View hSpacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private View vSpacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, weight));
        return v;
    }

    private void moveFocus(boolean right) {
        View target = right ? cardVod.card : cardLive.card;
        View other = right ? cardLive.card : cardVod.card;
        if (target.isFocused()) {
            return;
        }
        target.requestFocus();
        other.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveFocus(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveFocus(false);
                return true;
            case KeyEvent.KEYCODE_PROG_RED:
                startActivity(new Intent(this, SelfTestActivity.class));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                View f = getCurrentFocus();
                if (f != null) {
                    f.performClick();
                    return true;
                }
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }
}

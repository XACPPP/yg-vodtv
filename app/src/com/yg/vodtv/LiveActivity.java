package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 电视直播首页。
 *
 * v2.7 整页重做，对齐 stitch 设计稿（项目 9584627652671101173 的
 * 「YG影视 - Android TV 电视直播首页」）。跟 v2.6 的差别，逐条对应老板的投诉：
 *
 * 1. **「界面 low」** —— v2.6 是纯色底 + 裸字标 + 纯文字卡，确实是 PPT 观感。
 *    现在按设计稿落地：径向渐变背景、40px 琥珀渐变品牌方块、内凹胶囊双板块、
 *    38px 标题 + LIVE 小标签、44px 胶囊分组条、四层信息卡片、
 *    焦点「双环发光 + 呼吸点 + 当前聚焦」、底部键帽提示条。
 *
 * 2. **「全是黑框框，一个海报都没有」** —— v2.6 的卡片只有左侧 6px 彩条 + 台名，
 *    本来就是纯黑卡。v2.7 每张卡左侧嵌真实台标（APK 内置 assets/logo/*.png，
 *    见 {@link Ui#ASSET_PREFIX}），没抓到的台回退成首字色块，不再有纯黑空框。
 *
 * 3. **「好多源特别卡」** —— 首页只负责展示每台还剩几条可用线路（N 源），
 *    真正的筛选在 test/live_scan.py（v2.7 加了吞吐复测）；
 *    播放页加了「卡住自动切下一路」的看门狗，见 {@link LivePlayerActivity}。
 *
 * 布局节奏严格照设计稿：顶栏 72、左右 56、分组条 44 高、卡片 6 列、行距 20。
 */
public final class LiveActivity extends Activity {

    /** 一屏 6 列（设计稿 grid-template-columns: repeat(6, 260px)）。 */
    private static final int COLS = 6;
    /** 分组条一屏露 6 个半，第 7 个被右边缘裁一半，暗示还能往右滑。 */
    private static final float GROUP_VISIBLE = 6.5f;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private HorizontalScrollView groupScroll;
    private LinearLayout groupRow;
    private ScrollView scroll;
    private LinearLayout rows;
    private TextView barStatus;
    private TextView titleSub;
    private TextView statPill;
    private TextView clockView;

    private final List<TextView> groupChips = new ArrayList<TextView>();
    private List<Live.Group> groups = new ArrayList<Live.Group>();
    private int groupIdx = 0;

    private int cardW = 260;
    private int cardH = 150;
    private int cardGap = 24;

    /** 上一次看到的 Live.loadSeq()，用于发现「远端表换过了」。 */
    private int lastSeq = -1;
    private int polls = 0;

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Store.init(this);
        LiveSync.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Proxy.start();

        groups = Live.groups();
        buildUi();
        renderGroups();

        // 回到上次看的那一组；没有记录就停在第一个
        int want = 0;
        int saved = Store.liveGroup();
        if (saved >= 0 && saved < groups.size()) {
            want = saved;
        }
        selectGroup(want);

        LiveSync.refresh();
        ui.postDelayed(poller, 1200);
        ui.post(clockTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barStatus != null) {
            barStatus.setText(Live.channelCount() + " 个台");
        }
        if (statPill != null) {
            statPill.setText(Live.channelCount() + " 频道 · " + Live.groupCount() + " 分组");
        }
        ui.post(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 呼吸点是 INFINITE 的 ObjectAnimator，不主动取消会一直挂在主线程动画队列里，
        // 页面销毁后仍在跑（拿着 View 引用，等于漏一个页面）。
        stopBreath();
        ui.removeCallbacksAndMessages(null);
    }

    /** 停掉所有卡片的呼吸动画。 */
    private void stopBreath() {
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View rowView = rows.getChildAt(i);
            if (!(rowView instanceof LinearLayout)) {
                continue;
            }
            LinearLayout row = (LinearLayout) rowView;
            for (int j = 0; j < row.getChildCount(); j++) {
                Object tag = row.getChildAt(j).getTag();
                if (tag instanceof Card && ((Card) tag).breath != null) {
                    ((Card) tag).breath.cancel();
                    ((Card) tag).breath = null;
                }
            }
        }
    }

    /**
     * 等远端表换完再重画。
     *
     * LiveSync.refresh() 是异步的，正常 1~3 秒回来。这里 1.5 秒探一次、最多探 20 次，
     * 只在 Live.loadSeq() 真变了的时候才重画，避免无谓的重建整页（电视上重排会闪）。
     */
    private final Runnable poller = new Runnable() {
        public void run() {
            if (Live.loadSeq() != lastSeq) {
                lastSeq = Live.loadSeq();
                reload();
                return;
            }
            if (polls++ < 20) {
                ui.postDelayed(this, 1500);
            }
        }
    };

    private final Runnable clockTick = new Runnable() {
        public void run() {
            if (clockView == null) {
                return;
            }
            clockView.setText(new SimpleDateFormat("HH:mm").format(new Date()));
            ui.postDelayed(this, 30000);
        }
    };

    /** 远端表换了：重新取快照、重建分组与网格，尽量停在原来那一组。 */
    private void reload() {
        String keepId = (groupIdx >= 0 && groupIdx < groups.size()) ? groups.get(groupIdx).id : null;
        groups = Live.groups();
        renderGroups();
        int want = 0;
        if (keepId != null) {
            for (int i = 0; i < groups.size(); i++) {
                if (keepId.equals(groups.get(i).id)) {
                    want = i;
                    break;
                }
            }
        }
        selectGroup(want);
    }

    /* ==================== 界面骨架 ==================== */

    private void buildUi() {
        root = new FrameLayout(this);
        Chrome.applyPageBg(root);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        col.addView(buildBar());
        col.addView(buildTitleRow());

        // 分组条：左右各留 52，比正文 56 少 4，让最边上的胶囊缩放后不被裁
        groupScroll = new HorizontalScrollView(this);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setClipChildren(false);
        groupScroll.setPadding(Ui.px(52), 0, Ui.px(52), 0);
        groupRow = new LinearLayout(this);
        groupRow.setOrientation(LinearLayout.HORIZONTAL);
        groupRow.setGravity(Gravity.CENTER_VERTICAL);
        groupRow.setClipChildren(false);
        groupRow.setPadding(0, Ui.px(6), 0, Ui.px(6));
        groupScroll.addView(groupRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        col.addView(groupScroll);

        // 频道网格
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(Ui.px(52), Ui.px(10), Ui.px(52), Ui.px(18));
        scroll.addView(rows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        col.addView(scroll, slp);

        col.addView(buildFooter());

        setContentView(root);
    }

    /**
     * 顶栏（设计稿 72px）：品牌方块 + 「极清影院」+ 竖分隔 + 双板块胶囊
     * + 右侧统计胶囊 + 时钟。
     *
     * 跟 v2.6 的差别：「YG」从裸文字变成 40x40 渐变方块，双板块从两个散 pill
     * 变成塞进一个内凹胶囊，右边多了「N 频道 · N 分组」统计。
     * 这三处是设计稿「有设计感」最直观的来源。
     */
    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(56), Ui.px(20), Ui.px(56), Ui.px(4));
        bar.setMinimumHeight(Ui.px(Chrome.BAR_H));
        bar.setClipChildren(false);

        bar.addView(Chrome.brandBlock(this));

        TextView brand = Chrome.brandText(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.px(14);
        bar.addView(brand, blp);

        bar.addView(Chrome.vDivider(this));

        // 双板块：这是「电视」，实心琥珀；右边「影视」点一下回影视大厅
        LinearLayout capsule = Chrome.boardCapsule(this);
        TextView tvOn = Chrome.boardPill(this, "电视", true);
        capsule.addView(tvOn);

        TextView mvOff = Chrome.boardPill(this, "影视", false);
        mvOff.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(LiveActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = Ui.px(4);
        capsule.addView(mvOff, mlp);
        bar.addView(capsule);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        statPill = Chrome.statPill(this, Live.channelCount() + " 频道 · " + Live.groupCount() + " 分组");
        bar.addView(statPill);

        clockView = Ui.bold(this, "00:00", 24, Ui.FG);
        clockView.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.px(18);
        bar.addView(clockView, clp);

        return bar;
    }

    /**
     * 标题行：38px「电视直播」+ LIVE IP-TV 小标签 + 15px 副标题，
     * 右侧一枚「刷新源表」胶囊按钮（等于设计稿那两个圆形图标按钮的功能位）。
     */
    private View buildTitleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.px(56), Ui.px(6), Ui.px(56), Ui.px(2));

        row.addView(Chrome.title(this, "电视直播"));

        TextView tag = Chrome.inlineTag(this, "LIVE · IP-TV");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.px(12);
        row.addView(tag, tlp);

        titleSub = Chrome.subtitle(this, "");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.px(16);
        row.addView(titleSub, slp);

        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView refresh = Ui.bold(this, "刷新源表", 16, Ui.FG);
        refresh.setGravity(Gravity.CENTER);
        refresh.setFocusable(true);
        refresh.setClickable(true);
        refresh.setPadding(Ui.px(22), Ui.px(8), Ui.px(22), Ui.px(8));
        refresh.setBackground(Ui.focusBgGlow(0x00000000, 999, Ui.ACCENT));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LiveSync.refresh();
                polls = 0;
                ui.removeCallbacks(poller);
                ui.postDelayed(poller, 1200);
                if (barStatus != null) {
                    barStatus.setText("正在刷新源表…");
                }
                barStatus.postDelayed(new Runnable() {
                    public void run() {
                        if (barStatus != null) {
                            barStatus.setText(Live.channelCount() + " 个台");
                        }
                    }
                }, 4000);
            }
        });
        row.addView(refresh);

        // v2.6 里 barStatus 放在顶栏，现在挪到标题行右侧、刷新按钮左边，
        // 这样顶栏更干净，状态文字也不会挤品牌区。
        barStatus = Ui.text(this, Live.channelCount() + " 个台", 14, Ui.FG_DIM);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = Ui.px(16);
        row.addView(barStatus, blp);

        return row;
    }

    /** 底部：左侧键帽提示 + 右侧在线状态点（设计稿 footer 结构）。 */
    private View buildFooter() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.px(56), 0, Ui.px(56), Ui.px(16));

        View line = new View(this);
        line.setBackgroundColor(0x2E252C38);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(1))));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.px(12), 0, 0);

        String[][] keys = new String[][] {
                { "← →", "分组" },
                { "OK", "播放" },
                { "菜单", "换源" },
                { "返回", "退出" },
        };
        row.addView(Chrome.keyHintRow(this, keys, "OK"));

        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        row.addView(Chrome.statusDot(this,
                Live.channelCount() + " 台 · 多源自动切换", Ui.OK_GREEN));

        wrap.addView(row);
        return wrap;
    }

    /* ==================== 分组条 ==================== */

    private void renderGroups() {
        groupRow.removeAllViews();
        groupChips.clear();

        int gap = Ui.px(12);
        int usable = Ui.w() - Ui.px(104) - (int) (gap * (GROUP_VISIBLE - 1));
        int w = Math.max(Ui.px(140), Math.round(usable / GROUP_VISIBLE));

        for (int i = 0; i < groups.size(); i++) {
            final int idx = i;
            Live.Group g = groups.get(i);
            TextView t = Ui.bold(this, g.name + " " + g.chs.size(), 17, Ui.FG_DIM);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setFocusable(true);
            t.setClickable(true);
            t.setHeight(Ui.px(44));
            t.setPadding(Ui.px(10), 0, Ui.px(10), 0);
            t.setBackground(Chrome.pillBg(0x00000000, 0x00000000, 0));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectGroup(idx);
                }
            });
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    // 焦点走到分组条上：缩一点点 + 描边，但不改变「选中」状态
                    v.animate().cancel();
                    v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f)
                            .setDuration(120).start();
                    if (idx != groupIdx) {
                        v.setBackground(has
                                ? Chrome.pillBg(0x33FFFFFF, Ui.ACCENT, 1)
                                : Chrome.pillBg(0x00000000, 0x00000000, 0));
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    w, Ui.px(44));
            lp.rightMargin = gap;
            groupRow.addView(t, lp);
            groupChips.add(t);
        }
        paintGroups();
    }

    /** 只改颜色与背景，不重建 View（选中态 = 琥珀实心 + 外发光）。 */
    private void paintGroups() {
        for (int i = 0; i < groupChips.size(); i++) {
            boolean on = i == groupIdx;
            TextView t = groupChips.get(i);
            t.setTextColor(on ? Ui.BG : Ui.FG_DIM);
            t.setBackground(on
                    ? amberPill()
                    : Chrome.pillBg(0x1F1B2029, 0x26252C38, 1));
            t.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT_BOLD);
        }
    }

    /** 选中态胶囊：琥珀实心 + 一圈半透明外发光（设计稿 .faint-amber-glow）。 */
    private android.graphics.drawable.Drawable amberPill() {
        android.graphics.drawable.GradientDrawable core = Chrome.pillBg(Ui.ACCENT, 0, 0);
        android.graphics.drawable.GradientDrawable halo =
                Chrome.pillBg(0x00000000, 0x55FFB300, 4);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] { halo, core });
        ld.setLayerInset(0, -Ui.px(1), -Ui.px(1), -Ui.px(1), -Ui.px(1));
        return ld;
    }

    private void selectGroup(int idx) {
        if (idx < 0 || idx >= groups.size()) {
            idx = 0;
        }
        if (groups.isEmpty()) {
            return;
        }
        groupIdx = idx;
        paintGroups();
        Store.saveLive(groupIdx, Store.liveChannel() < 0 ? 0 : Store.liveChannel());
        renderChannels();

        // 让选中的那个胶囊滚进视野（横向）
        final TextView t = groupChips.get(idx);
        groupScroll.post(new Runnable() {
            public void run() {
                int x = t.getLeft() - (groupScroll.getWidth() - t.getWidth()) / 2;
                groupScroll.smoothScrollTo(Math.max(0, x), 0);
            }
        });
    }

    /* ==================== 频道网格 ==================== */

    private void renderChannels() {
        rows.removeAllViews();
        if (groups.isEmpty()) {
            TextView empty = Ui.bold(this, "还没有可用的直播源\n\n在影视大厅按「自检」，或稍后重试",
                    26, Ui.FG_DIM);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.px(120), 0, 0);
            rows.addView(empty);
            return;
        }

        Live.Group g = groups.get(groupIdx);
        titleSub.setText(g.name + " · " + g.chs.size() + " 个台 · 卡了自动换下一路");
        if (statPill != null) {
            statPill.setText(Live.channelCount() + " 频道 · " + Live.groupCount() + " 分组");
        }

        int contentW = Ui.w() - Ui.px(104);
        cardGap = Ui.px(24);
        cardW = Math.max(Ui.px(180), (contentW - cardGap * (COLS - 1)) / COLS);
        cardH = Math.round(cardW * 150f / 260f);

        LinearLayout row = null;
        for (int i = 0; i < g.chs.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Ui.px(20);
                rows.addView(row, rlp);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW, cardH);
            clp.rightMargin = cardGap;
            row.addView(channelCard(g.chs.get(i), i), clp);
        }

        // 首卡拿焦点（只在第一次进来，用户已经在按遥控时别抢）
        rows.post(new Runnable() {
            public void run() {
                if (root.findFocus() == null && rows.getChildCount() > 0
                        && rows.getChildAt(0) instanceof LinearLayout) {
                    LinearLayout first = (LinearLayout) rows.getChildAt(0);
                    if (first.getChildCount() > 0) {
                        first.getChildAt(0).requestFocus();
                    }
                }
            }
        });
    }

    /** 卡片里那几块要随焦点换色的文字，打包成一个 holder 省得反复 findView。 */
    private static final class Card {
        TextView chno;
        TextView badge;
        TextView name;
        TextView alias;
        TextView sources;
        TextView focusHint;
        View dot;
        /** 呼吸点动画。用 ObjectAnimator 而不是 ViewPropertyAnimator ——
         *  后者没有 INFINITE_REPEAT / REVERSE 常量，做不了往复。 */
        android.animation.ObjectAnimator breath;
    }

    /**
     * 一张频道卡。设计稿是四层信息 + 左侧彩条；v2.7 在中间层塞进真实台标，
     * 这是「不再全是黑框」的关键。
     *
     *   ┌───────────────────────────────┐
     *   │▌ CH 001                  [HD] │   ← 编号 + 画质角标
     *   │▌ ┌──────┐  CCTV-1             │   ← 台标 + 台名
     *   │▌ │ logo │  综合               │   ←        副名（有映射才显示）
     *   │▌ └──────┘              3 源   │   ←              线路数
     *   └───────────────────────────────┘
     */
    private View channelCard(final Live.Ch ch, final int chIdx) {
        final Card c = new Card();

        FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(Ui.focusBgGlow(Ui.SURFACE, 10, Ui.ACCENT));
        box.setClipChildren(true);

        // 左侧 6px 彩条（设计稿 absolute left-0 w-[6px]）
        View strip = new View(this);
        strip.setBackgroundColor(stripColor(ch.name));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                Ui.px(6), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        box.addView(strip, slp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.px(20), Ui.px(14), Ui.px(16), Ui.px(12));
        box.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        /* --- 第 1 层：CH 编号 + 画质角标 --- */
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        c.chno = Ui.bold(this, "CH " + pad3(chIdx + 1), 12, Ui.FG_DIM);
        c.chno.setLetterSpacing(0.12f);
        top.addView(c.chno);

        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        c.badge = Ui.bold(this, qualityOf(ch.name), 11, Ui.FG);
        c.badge.setGravity(Gravity.CENTER);
        c.badge.setPadding(Ui.px(6), Ui.px(2), Ui.px(6), Ui.px(2));
        c.badge.setBackground(Ui.round(Ui.SURFACE_HIGH, 4, 0, 0));
        top.addView(c.badge);
        col.addView(top);

        /* --- 第 2、3 层：台标 + 台名 / 副名 --- */
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        mlp.topMargin = Ui.px(8);

        // 台标框按 16:9 而不是正方形：电视台标本身就是 16:9（iptv-org 抓的是 96×54），
        // 塞进正方形里 CENTER_INSIDE 只用到 62×35，字都糊了。
        int logoH = Math.round(cardH * 0.40f);
        int logoW = Math.round(logoH * 1.62f);
        FrameLayout logoBox = new FrameLayout(this);
        logoBox.setBackground(Ui.round(Ui.SURFACE_HIGH, 8, 1, 0x1AFFFFFF));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int innerH = Math.round(logoH * 0.80f);
        int innerW = Math.round(logoW * 0.90f);
        iv.setPadding(Ui.px(4), Ui.px(4), Ui.px(4), Ui.px(4));
        logoBox.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // 台标：APK 内置 asset 走本地解码，远端 http 走 Ui 的三跳兜底，
        // 两者都拿不到就画频道短标签块（CCTV-8 → "8"、湖南卫视 → "湖南"），
        // 任何情况下都不会留一块纯黑，也不会十几个台长得一模一样
        Ui.loadPoster(iv, ch.logo, Math.max(8, innerW), Math.max(8, innerH), ch.name, true);
        mid.addView(logoBox, new LinearLayout.LayoutParams(logoW, logoH));

        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.px(12);

        c.name = Ui.bold(this, ch.name, 22, Ui.FG);
        c.name.setSingleLine(true);
        c.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameCol.addView(c.name);

        String alias = Live.aliasOf(ch.name);
        c.alias = Ui.text(this, alias, 13, Ui.FG_DIM);
        c.alias.setSingleLine(true);
        c.alias.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.px(3);
        c.alias.setVisibility(alias.length() > 0 ? View.VISIBLE : View.GONE);
        nameCol.addView(c.alias, alp);

        mid.addView(nameCol, nlp);
        col.addView(mid, mlp);

        /* --- 第 4 层：左下「当前聚焦」+ 右下「N 源」 --- */
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);

        c.dot = new View(this);
        c.dot.setBackground(Ui.round(Ui.ACCENT, 4, 0, 0));
        c.dot.setAlpha(0f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.px(6), Ui.px(6));
        dlp.rightMargin = Ui.px(6);
        bottom.addView(c.dot, dlp);

        c.focusHint = Ui.bold(this, "当前聚焦", 11, 0xCCFFB300);
        bottom.addView(c.focusHint);

        View sp2 = new View(this);
        bottom.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));

        c.sources = Ui.text(this, ch.urls.size() + " 源", 11, Ui.FG_DIM);
        bottom.addView(c.sources);
        col.addView(bottom);

        box.setTag(c);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open(chIdx);
            }
        });
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                focusCard(v, c, has);
            }
        });
        return box;
    }

    /**
     * 焦点态：卡片放大 1.045 + 上移 3px + 呼吸点闪烁 + 文字换琥珀。
     * 设计稿是 scale-1.06 / amber-ring / 台名 text-[27px] text-white /
     * 副名 text-[#FFC107] / 左下「当前聚焦」。
     */
    private void focusCard(View v, Card c, boolean has) {
        v.animate().cancel();
        v.animate()
                .scaleX(has ? 1.045f : 1f)
                .scaleY(has ? 1.045f : 1f)
                .translationY(has ? -Ui.px(3) : 0)
                .setDuration(has ? 130 : 160)
                .start();
        v.setZ(has ? Ui.px(8) : 0);

        c.chno.setTextColor(has ? Ui.ACCENT_HI : Ui.FG_DIM);
        c.badge.setTextColor(has ? Ui.BG : Ui.FG);
        c.badge.setBackground(Ui.round(has ? Ui.ACCENT : Ui.SURFACE_HIGH, 4, 0, 0));
        c.name.setTextColor(has ? 0xFFFFFFFF : Ui.FG);
        c.alias.setTextColor(has ? Ui.ACCENT_HI : Ui.FG_DIM);
        c.sources.setTextColor(has ? Ui.FG_DIM : 0x99A6B5);

        // 呼吸点：alpha 在 1.0 和 0.2 之间往复，700ms 一个来回。
        // 用 ObjectAnimator：ViewPropertyAnimator 只有 setRepeatCount(int)，
        // 没有 REVERSE 语义，做不出「呼吸」。
        if (c.breath != null) {
            c.breath.cancel();
            c.breath = null;
        }
        if (has) {
            c.dot.setAlpha(1f);
            c.breath = android.animation.ObjectAnimator.ofFloat(c.dot, "alpha", 1f, 0.2f);
            c.breath.setDuration(700);
            c.breath.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            c.breath.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
            c.breath.start();
            c.focusHint.setVisibility(View.VISIBLE);
        } else {
            c.dot.setAlpha(0f);
            c.focusHint.setVisibility(View.INVISIBLE);
        }
    }

    /* ==================== 杂项 ==================== */

    /** 左侧彩条颜色：按台名里出现的关键词取设计稿里的品牌色，认不出就按哈希取色。 */
    private int stripColor(String name) {
        if (name.startsWith("CCTV")) {
            if (name.contains("5+")) {
                return 0xFF00B0FF;
            }
            if (name.contains("5")) {
                return 0xFF00C853;
            }
            if (name.contains("6") || name.contains("8")) {
                return 0xFFD4AF37;
            }
            if (name.contains("13") || name.contains("新闻")) {
                return 0xFF00B8D4;
            }
            if (name.contains("3") || name.contains("综艺")) {
                return 0xFF7C4DFF;
            }
            if (name.contains("7") || name.contains("军事")) {
                return 0xFF2E7D32;
            }
            if (name.contains("10") || name.contains("科教")) {
                return 0xFF3D5AFE;
            }
            if (name.contains("14") || name.contains("少儿")) {
                return 0xFFFF9100;
            }
            if (name.contains("16") || name.contains("奥林")) {
                return 0xFFFF3D00;
            }
            if (name.contains("17") || name.contains("农业")) {
                return 0xFF00E676;
            }
            return 0xFFE50914;
        }
        if (name.contains("卫视")) {
            return 0xFF4FC3F7;
        }
        if (name.contains("体育") || name.contains("足球") || name.contains("篮球")) {
            return 0xFF00C853;
        }
        if (name.contains("电影") || name.contains("影院") || name.contains("影视")) {
            return 0xFFD4AF37;
        }
        if (name.contains("少儿") || name.contains("动画") || name.contains("卡通")) {
            return 0xFFFF9100;
        }
        if (name.contains("新闻") || name.contains("资讯")) {
            return 0xFF00B8D4;
        }
        if (name.contains("纪录") || name.contains("纪实")) {
            return 0xFF8D6E63;
        }
        return 0xFF9575CD;
    }

    /** 画质角标：源表里没有分辨率信息，只能从台名里的关键词判断。 */
    private String qualityOf(String name) {
        if (name.contains("4K") || name.contains("4k") || name.contains("超清")) {
            return "4K";
        }
        if (name.contains("8K") || name.contains("8k")) {
            return "8K";
        }
        return "HD";
    }

    private String pad3(int n) {
        if (n < 10) {
            return "00" + n;
        }
        if (n < 100) {
            return "0" + n;
        }
        return String.valueOf(n);
    }

    private void open(int chIdx) {
        Store.saveLive(groupIdx, chIdx);
        Intent i = new Intent(this, LivePlayerActivity.class);
        i.putExtra("groupIdx", groupIdx);
        i.putExtra("chIdx", chIdx);
        startActivity(i);
    }
}

package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播板块：上面一行分组，下面频道墙。
 *
 * 与点播页的关系：点播页是「影视」板块，这里是「电视」板块，两个平级，
 * 靠顶栏那个双板块胶囊互相跳。返回键从直播页直接回点播页（不跨 Activity 栈叠加）。
 *
 * 为什么分组条只露 6~7 个：24 个频道全塞一行会显得又挤又小气（老板原话），
 * 所以这里做定宽胶囊 + 横向滚动，第 7 个自然被右边缘裁一半，就是「还能往右滑」的暗示。
 */
public final class LiveActivity extends Activity {

    /** 频道墙每排几个。Stitch 稿是 6 个 260x150，1920 宽下正好 284x164，比稿子上略宽一点。 */
    private static final int COLS = 6;

    /** 一屏幕只完整露 6 个半分组胶囊。 */
    private static final float GROUP_VISIBLE = 6.5f;

    /** 换源面板侧边留白，与频道墙对齐。 */
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private HorizontalScrollView groupScroll;
    private LinearLayout groupRow;
    private ScrollView scroll;
    private LinearLayout rows;
    /** 频道墙里的空态/错误提示（rows 的第 0 个子 View）。 */
    private TextView status;
    /** 顶栏右侧的源表状态，跟 status 不是一个东西，别混。 */
    private TextView barStatus;
    private TextView titleSub;

    private final List<TextView> groupChips = new ArrayList<TextView>();

    private List<Live.Group> groups = new ArrayList<Live.Group>();
    private int groupIdx = 0;
    private int cardW = 240;
    private int cardH = 138;
    /** 上次渲染用的 Live.loadSeq()：远端表换了才重画，别每轮轮询都白重排一次。 */
    private int lastSeq = -1;
    private int polls = 0;

    private final Runnable poller = new Runnable() {
        public void run() {
            if (Live.loadSeq() != lastSeq) {
                reload();
                return;
            }
            // 最多轮询 20 次（约 30 秒）。再之后就不看了，用户切走或进来时会自己重来。
            if (++polls < 20) {
                ui.postDelayed(this, 1500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Store.init(this);
        LiveSync.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Proxy.start();

        buildUi();

        groups = Live.groups();
        lastSeq = Live.loadSeq();
        int want = getIntent().getIntExtra("groupIdx", 0);
        if (want < 0 || want >= groups.size()) {
            want = 0;
        }
        renderGroups();
        selectGroup(want);

        // 远端表是后台拉的，拉回来了再重画一次
        LiveSync.refresh();
        ui.postDelayed(poller, 1200);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    /** ==================== 界面骨架 ==================== */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        col.addView(buildBar());

        // 标题行
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.px(56), Ui.px(14), Ui.px(56), Ui.px(2));

        TextView t = Ui.bold(this, "电视直播", 34, Ui.FG);
        head.addView(t);

        titleSub = Ui.text(this, "", 18, Ui.FG_DIM);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Ui.px(18);
        head.addView(titleSub, sl);

        col.addView(head);

        // 分组条
        groupScroll = new HorizontalScrollView(this);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setClipChildren(true);
        groupScroll.setPadding(Ui.px(52), 0, Ui.px(52), 0);
        groupScroll.setFocusable(false);

        groupRow = new LinearLayout(this);
        groupRow.setOrientation(LinearLayout.HORIZONTAL);
        groupRow.setPadding(0, Ui.px(8), 0, Ui.px(8));
        groupScroll.addView(groupRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        col.addView(groupScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 频道墙
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.px(52), Ui.px(6), Ui.px(52), Ui.px(30));
        scroll.addView(rows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "", 24, Ui.FG_DIM);
        rows.addView(status);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        col.addView(scroll, clp);

        // 底部遥控提示
        TextView foot = Ui.text(this, "↑↓←→ 移动　　OK 播放　　菜单 切换源　　返回 退出", 18, Ui.FG_DIM);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.px(4), 0, Ui.px(14));
        col.addView(foot);

        int avail = Ui.w() - Ui.px(112) - (COLS - 1) * Ui.px(18);
        cardW = avail / COLS;
        // 260:150 的卡片比例，Stitch 稿定的
        cardH = Math.round(cardW * 150f / 260f);

        setContentView(root);
    }

    /** 顶栏：左边双板块胶囊，右边源表状态 + 刷新。 */
    private LinearLayout buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BG_BAR);
        bar.setPadding(Ui.px(56), Ui.px(18), Ui.px(56), Ui.px(18));

        TextView logo = Ui.bold(this, "YG", 30, Ui.ACCENT);
        bar.addView(logo);

        LinearLayout boards = new LinearLayout(this);
        boards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.leftMargin = Ui.px(26);

        // 「电视」是本页，实心表示选中
        TextView tvOn = pill("电视", true);
        boards.addView(tvOn);

        TextView mvOff = pill("影视", false);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ml.leftMargin = Ui.px(10);
        mvOff.setTextColor(Ui.FG);
        mvOff.setBackground(Ui.focusBg(0x00000000, 8, Ui.ACCENT));
        mvOff.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 点「影视」= 回点播页。点播页可能不在栈里（比如被系统回收），那就开一个新的。
                Intent i = new Intent(LiveActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            }
        });
        boards.addView(mvOff, ml);
        bar.addView(boards, bl);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        barStatus = Ui.text(this, "", 18, Ui.FG_DIM);
        bar.addView(barStatus);

        TextView refresh = chip("刷新源表");
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.leftMargin = Ui.px(18);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                barStatus.setText("正在拉远端源表…");
                LiveSync.refresh();
                polls = 0;
                ui.removeCallbacks(poller);
                ui.postDelayed(poller, 800);
            }
        });
        bar.addView(refresh, rl);

        return bar;
    }

    /** 板块胶囊：选中态实心琥珀 + 深色字。 */
    private TextView pill(String label, boolean on) {
        TextView t = Ui.bold(this, label, 22, on ? 0xFF12161C : Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(26), Ui.px(8), Ui.px(26), Ui.px(8));
        t.setBackground(on ? Ui.round(Ui.ACCENT, 8, 0, 0) : Ui.focusBg(0x00000000, 8, Ui.ACCENT));
        return t;
    }

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 18, Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(18), Ui.px(7), Ui.px(18), Ui.px(7));
        t.setBackground(Ui.focusBg(Ui.BG_CARD, 8, Ui.ACCENT));
        return t;
    }

    /** ==================== 分组条 ==================== */

    /**
     * 定宽胶囊：一屏露 6 个半。
     *
     * 之所以算死宽度而不是 WRAP_CONTENT：WRAP_CONTENT 时 8 个分组正好挤在一屏里，
     * 又变成「全都摆出来」的样子；定宽之后第 7 个自然被裁掉一半，滑动手势一看就懂。
     */
    private void renderGroups() {
        groupRow.removeAllViews();
        groupChips.clear();

        if (groups.isEmpty()) {
            return;
        }
        int gap = Ui.px(12);
        int usable = Ui.w() - Ui.px(104) - (int) (gap * (GROUP_VISIBLE - 1));
        int w = Math.max(Ui.px(140), Math.round(usable / GROUP_VISIBLE));

        for (int i = 0; i < groups.size(); i++) {
            final int idx = i;
            final Live.Group g = groups.get(i);
            TextView t = Ui.bold(this, g.name + " " + g.chs.size(), 22, Ui.FG);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setFocusable(true);
            t.setClickable(true);
            t.setPadding(0, Ui.px(9), 0, Ui.px(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    w, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            t.setBackground(Ui.focusBg(Ui.BG_CARD, 10, Ui.ACCENT));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectGroup(idx);
                }
            });
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                            .setDuration(120).start();
                }
            });
            groupRow.addView(t, lp);
            groupChips.add(t);
        }
        paintGroups();
    }

    /** 只改高亮，不动布局。 */
    private void paintGroups() {
        for (int i = 0; i < groupChips.size(); i++) {
            boolean on = i == groupIdx;
            TextView t = groupChips.get(i);
            t.setTextColor(on ? 0xFF12161C : Ui.FG);
            t.setBackground(on ? Ui.round(Ui.ACCENT, 10, 0, 0)
                    : Ui.focusBg(Ui.BG_CARD, 10, Ui.ACCENT));
        }
    }

    private void selectGroup(int idx) {
        if (idx < 0 || idx >= groups.size()) {
            return;
        }
        // 同一个组再点一次不用重建：画面已经是对的
        if (idx == groupIdx && rows.getChildCount() > 1) {
            return;
        }
        groupIdx = idx;
        paintGroups();
        renderChannels();
        scroll.scrollTo(0, 0);
    }

    /** ==================== 频道墙 ==================== */

    private void renderChannels() {
        rows.removeAllViews();

        if (groupIdx < 0 || groupIdx >= groups.size()) {
            status.setText("没有可用的直播源，点右上角「刷新源表」再试");
            rows.addView(status);
            return;
        }

        Live.Group g = groups.get(groupIdx);
        titleSub.setText(g.name + " · " + g.chs.size() + " 个台 · 卡了就按菜单换源");

        if (g.chs.isEmpty()) {
            status.setText("这个分组暂时没有可用的台");
            rows.addView(status);
            return;
        }
        status.setText("");
        rows.addView(status);

        LinearLayout row = null;
        for (int i = 0; i < g.chs.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Ui.px(16);
                rows.addView(row, rlp);
            }
            final int chIdx = i;
            View card = channelCard(g.chs.get(i), chIdx);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW, cardH);
            clp.rightMargin = Ui.px(18);
            row.addView(card, clp);

            if (i == 0) {
                card.post(new Runnable() {
                    public void run() {
                        // 进来先给个焦点，不然遥控器第一次按方向键不知道从哪起
                        View first = rows.getChildAt(1);
                        if (first instanceof LinearLayout
                                && ((LinearLayout) first).getChildCount() > 0) {
                            ((LinearLayout) first).getChildAt(0).requestFocus();
                        }
                    }
                });
            }
        }
    }

    /** 一张频道卡：左侧 6px 彩色条（按台名 hash 取色）、中间台名、右下「N 源」。 */
    private View channelCard(final Live.Ch ch, final int chIdx) {
        FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(Ui.focusBg(Ui.BG_CARD, 12, Ui.ACCENT));
        box.setPadding(Ui.px(4), Ui.px(4), Ui.px(4), Ui.px(4));

        FrameLayout inner = new FrameLayout(this);
        inner.setBackground(Ui.round(Ui.BG_CARD, 9, 0, 0));
        box.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 左侧彩色条：不同台不同色，扫一眼就能认回来
        View strip = new View(this);
        strip.setBackgroundColor(stripColor(ch.name));
        FrameLayout.LayoutParams stl = new FrameLayout.LayoutParams(
                Ui.px(6), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        stl.leftMargin = Ui.px(2);
        stl.topMargin = Ui.px(10);
        stl.bottomMargin = Ui.px(10);
        inner.addView(strip, stl);

        TextView nm = Ui.bold(this, ch.name, 26, Ui.FG);
        nm.setGravity(Gravity.CENTER);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(Ui.px(16), 0, Ui.px(12), 0);
        inner.addView(nm, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        TextView cnt = Ui.text(this, ch.urls.size() + " 源", 16, Ui.FG_DIM);
        FrameLayout.LayoutParams ctl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        ctl.rightMargin = Ui.px(14);
        ctl.bottomMargin = Ui.px(10);
        inner.addView(cnt, ctl);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open(chIdx);
            }
        });
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                        .translationY(has ? -Ui.px(4) : 0).setDuration(160).start();
                v.setZ(has ? 10f : 0f);
            }
        });
        return box;
    }

    /** 按台名挑一个稳定的色，同一台每次进来颜色都一样。 */
    private int stripColor(String name) {
        int[] pal = {
                0xFFFFB300, 0xFF4FC3F7, 0xFF81C784, 0xFFE57373,
                0xFFBA68C8, 0xFF4DB6AC, 0xFFFF8A65, 0xFF9575CD
        };
        int h = name == null ? 0 : name.hashCode();
        return pal[(h & 0x7fffffff) % pal.length];
    }

    private void open(int chIdx) {
        Intent i = new Intent(this, LivePlayerActivity.class);
        i.putExtra("groupIdx", groupIdx);
        i.putExtra("chIdx", chIdx);
        startActivity(i);
    }

    /** 远端表换了：重拉快照，尽量停在原来那个分组上。 */
    private void reload() {
        String keep = (groupIdx >= 0 && groupIdx < groups.size()) ? groups.get(groupIdx).id : null;
        groups = Live.groups();
        lastSeq = Live.loadSeq();
        renderGroups();
        int want = 0;
        if (keep != null) {
            for (int i = 0; i < groups.size(); i++) {
                if (keep.equals(groups.get(i).id)) {
                    want = i;
                    break;
                }
            }
        }
        groupIdx = -1;
        selectGroup(want);
    }

    @Override
    protected void onResume() {
        super.onResume();
        barStatus.setText(LiveSync.status() + "　" + Live.channelCount() + " 个台");
    }
}

package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 详情页：左海报 + 右信息与选集。
 *
 * 多源之后「片名/海报」从列表页带过来先上屏（不再白屏等接口），
 * 详情接口回来后再补齐导演、主演、简介、选集。
 * 简介为空时会把片名丢去其它源找同名条目回填（Multi.fillIntro）。
 *
 * v2.8：照 Stitch「影视详情页（严格 1080P TV 真实数据版）」重排。
 * 这一版的重点不是加东西，是**把假数据全部拿掉**：
 *   - 评分胶囊（接口 vod_score 恒为 "0.0"，显示出来就是骗人）
 *   - 「已看至 35:20」这种秒级续播（我们只记到第几集 + 集内毫秒，不对外承诺时间点）
 *   - 分级胶囊（TV-MA）、杜比认证条（数据源都没有）
 * 然后把结构对齐设计稿：72px 顶栏 / 380×540 海报 + 三按钮 / 标签胶囊行 /
 * 10 列选集（焦点 scale 1.08 + 3px 琥珀描边 + 「当前选中」）/ 40px 键帽条。
 */
public final class DetailActivity extends Activity {

    /** 选集每行多少个（设计稿 10 列）。 */
    private static final int EP_COLS = 10;
    /** 海报宽度（设计稿 380），高度按 540 走，比 2:3 略瘦，跟设计稿一致。 */
    private static final int POSTER_W = 380;
    private static final int POSTER_H = 540;
    private static final float TOP_H = 72f;
    private static final float FOOT_H = 40f;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int vid;
    private String src = "";
    private String vname = "";
    private String vpic = "";
    private String vrem = "";

    private TextView title;
    private TextView meta;
    private LinearLayout tagRow;
    private TextView intro;
    private LinearLayout epRows;
    private TextView epStatus;
    private TextView epCount;
    private TextView clockView;
    /** 多源下的唯一标识：源_id。 */
    private String key = "";
    private boolean introOpen = false;
    /** 焦点所在集（用于画「当前选中」小字，onResume 重画时要保留）。 */
    private int focusEp = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Store.init(this);
        vid = getIntent().getIntExtra("id", 0);
        src = nz(getIntent().getStringExtra("src"));
        vname = nz(getIntent().getStringExtra("name"));
        vpic = nz(getIntent().getStringExtra("pic"));
        vrem = nz(getIntent().getStringExtra("remarks"));
        key = Store.vid(src, vid);
        buildUi();
        loadDetail();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放器回来时，已看进度可能变了，重画一次选集
        if (epRows != null && epRows.getChildCount() > 0) {
            renderEpisodes();
        }
        tickClock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /* ====================== 骨架 ====================== */

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.pageBg());
        // 设计稿安全边距：左右 56，上下 40
        root.setPadding(Ui.px(56), Ui.px(28), Ui.px(56), Ui.px(20));

        root.addView(buildTopBar());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = Ui.px(22);
        root.addView(body, bodyLp);

        body.addView(buildLeft());

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = Ui.px(48);
        body.addView(right, rlp);

        title = Ui.bold(this, vname, 52, Ui.FG);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLetterSpacing(-0.02f);
        right.addView(title);

        // 元信息行：年份 · 地区 · 类型 · 备注（严格四项，设计稿要求）
        meta = Ui.text(this, nz(vrem), 20, Ui.FG_DIM);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.px(10);
        right.addView(meta, mlp);

        // 标签胶囊行：导演 / 主演 / 语言，没数据的胶囊不出现
        tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.px(14);
        right.addView(tagRow, tlp);

        // 剧情简介：默认 3 行，按 OK 展开/收起
        intro = Ui.text(this, " ", 17, Ui.FG_DIM);
        intro.setMaxLines(3);
        intro.setEllipsize(TextUtils.TruncateAt.END);
        intro.setLineSpacing(Ui.px(7), 1f);
        intro.setFocusable(true);
        intro.setClickable(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.px(14);
        // 设计稿 max-w-[1300px]，但右栏只有 ~1400，直接铺满即可
        right.addView(intro, nlp);

        right.addView(buildActionRow());

        // 选集：标题 + 计数
        LinearLayout epHead = new LinearLayout(this);
        epHead.setOrientation(LinearLayout.HORIZONTAL);
        epHead.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams ehlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ehlp.topMargin = Ui.px(20);
        right.addView(epHead, ehlp);

        epStatus = Ui.bold(this, "选集", 24, Ui.FG);
        epHead.addView(epStatus);

        epCount = Ui.text(this, "加载中…", 15, Ui.FG_DIM);
        LinearLayout.LayoutParams eclp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eclp.leftMargin = Ui.px(12);
        epHead.addView(epCount, eclp);

        ScrollView epScroll = new ScrollView(this);
        epScroll.setVerticalScrollBarEnabled(false);
        epScroll.setClipChildren(false);
        epRows = new LinearLayout(this);
        epRows.setOrientation(LinearLayout.VERTICAL);
        epRows.setClipChildren(false);
        epRows.setPadding(0, Ui.px(12), 0, Ui.px(24));
        epScroll.addView(epRows);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        elp.topMargin = Ui.px(4);
        right.addView(epScroll, elp);

        root.addView(buildFooter());
        setContentView(root);
    }

    /** 72px 顶栏：← 返回 + 分类名 … 时钟。 */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(Ui.px(TOP_H));

        final TextView back = Ui.text(this, "←  返回", 18, 0xE6FFFFFF);
        back.setSingleLine(true);
        back.setFocusable(true);
        back.setClickable(true);
        back.setPadding(Ui.px(22), Ui.px(9), Ui.px(22), Ui.px(9));
        // v2.7 统一焦点语言：描边 + 外发光，跟首页胶囊、直播卡片同一套
        back.setBackground(Ui.focusBgGlow(0x14FFFFFF, 999, Ui.ACCENT));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        // 分类/来源名（设计稿：返回键右边一个灰字，点明当前在哪）
        TextView cat = Ui.text(this, src.length() > 0 ? src : "详情", 18, Ui.FG_DIM);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.px(18);
        bar.addView(cat, clp);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        clockView = Ui.bold(this, "00:00", 26, 0xE6FFFFFF);
        clockView.setLetterSpacing(0.02f);
        bar.addView(clockView);

        back.requestFocus();
        return bar;
    }

    /** 左栏：380×540 海报 + 三个等宽按钮。 */
    private View buildLeft() {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        int pw = Ui.px(POSTER_W);
        int ph = Ui.px(POSTER_H);

        FrameLayout pbox = new FrameLayout(this);
        pbox.setBackground(Ui.round(Ui.BG_CARD, 12, 1, 0x14FFFFFF));
        pbox.setClipChildren(true);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pbox.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Ui.loadPoster(iv, vpic, pw, ph, vname);

        // 左上「4K」/ 右上「VIP」：只有备注里真的写了才贴，不无中生有
        String remark = nz(vrem);
        if (remark.contains("4K") || remark.contains("蓝光") || remark.contains("1080")) {
            pbox.addView(cornerTag("4K", Ui.TAG_4K, Gravity.TOP | Gravity.START));
        }
        if (remark.contains("VIP") || remark.contains("会员")) {
            pbox.addView(cornerTag("VIP", Ui.TAG_VIP, Gravity.TOP | Gravity.END));
        }

        left.addView(pbox, new LinearLayout.LayoutParams(pw, ph));

        // 三个等宽按钮：收藏 / 分享 / 缓存
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(pw,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = Ui.px(16);
        left.addView(btnRow, brlp);
        String[] labels = {"收藏", "分享", "缓存"};
        for (int i = 0; i < labels.length; i++) {
            TextView b = flatButton(labels[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.px(44), 1f);
            if (i > 0) {
                blp.leftMargin = Ui.px(12);
            }
            btnRow.addView(b, blp);
            if ("收藏".equals(labels[i])) {
                final TextView fb = b;
                // 收藏状态在按钮文字上直接体现，省得再弹toast才知道点没点上
                fb.setText(Store.liked(key) ? "已收藏" : "收藏");
                fb.setTextColor(Store.liked(key) ? Ui.ACCENT : 0xE6FFFFFF);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boolean on = Store.toggleLike(key);
                        fb.setText(on ? "已收藏" : "收藏");
                        fb.setTextColor(on ? Ui.ACCENT : 0xE6FFFFFF);
                    }
                });
            } else if ("分享".equals(labels[i])) {
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        showToast("电视上没有分享入口，手机上搜同名即可");
                    }
                });
            } else {
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        showToast("缓存功能暂未开放");
                    }
                });
            }
        }

        return left;
    }

    private View cornerTag(String text, int color, int gravity) {
        TextView t = Ui.bold(this, text, 13, "VIP".equals(text) ? Ui.BG : 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(Ui.px(8), Ui.px(4), Ui.px(8), Ui.px(4));
        t.setBackground(Ui.round(color, 4, 0, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        lp.topMargin = Ui.px(12);
        lp.leftMargin = Ui.px(12);
        lp.rightMargin = Ui.px(12);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView flatButton(String label) {
        TextView t = Ui.text(this, label, 15, 0xE6FFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(Ui.focusBgGlow(0x1AFFFFFF, 8, Ui.ACCENT));
        return t;
    }

    /** 主按钮行：立即播放（琥珀，内嵌 OK 键帽）+ 从头播放（描边）。 */
    private View buildActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.px(18);

        final LinearLayout play = new LinearLayout(this);
        play.setOrientation(LinearLayout.HORIZONTAL);
        play.setGravity(Gravity.CENTER_VERTICAL);
        play.setFocusable(true);
        play.setClickable(true);
        play.setPadding(Ui.px(28), Ui.px(14), Ui.px(20), Ui.px(14));
        play.setBackground(Ui.focusBgGlow(Ui.ACCENT, 8, Ui.ACCENT_HI));
        TextView pt = Ui.bold(this, "▶  立即播放", 18, Ui.BG);
        play.addView(pt);
        TextView ok = Ui.keycap(this, "OK", false);
        ok.setBackground(Ui.round(0x330E1116, 4, 0, 0));
        ok.setTextColor(Ui.ACCENT);
        LinearLayout.LayoutParams oklp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        oklp.leftMargin = Ui.px(10);
        play.addView(ok, oklp);
        // 焦点换文字色，否则琥珀底上「▶ 立即播放」的深色字在发光态下会糊
        play.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                play.setBackground(has ? Ui.glowBg(Ui.ACCENT, 8, Ui.ACCENT_HI)
                        : Ui.focusBgGlow(Ui.ACCENT, 8, Ui.ACCENT_HI));
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startEp(focusEp);
            }
        });
        row.addView(play, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView from = flatButton("从头播放");
        from.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(17));
        from.setPadding(Ui.px(26), Ui.px(14), Ui.px(26), Ui.px(14));
        from.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startEp(0);
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = Ui.px(16);
        row.addView(from, flp);

        row.setLayoutParams(lp);
        return row;
    }

    /** 40px 键帽条：左侧状态点 + 右侧五组键位。 */
    private View buildFooter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.px(10);
        box.setLayoutParams(blp);

        box.addView(Chrome.hairline(this, 0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.px(FOOT_H));
        row.setPadding(0, Ui.px(6), 0, 0);

        row.addView(Chrome.statusDot(this, "YG 影音系统 · 遥控器操控", Ui.OK_GREEN));

        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        row.addView(Chrome.keyHintRow(this, new String[][]{
                {"OK", "播放当前集"},
                {"◀ ▶", "横向选集"},
                {"▲ ▼", "切换功能区"},
                {"菜单", "清晰度"},
                {"返回", "上一页"}
        }, "OK"));

        box.addView(row);
        return box;
    }

    /* ====================== 数据 ====================== */

    private void tickClock() {
        if (clockView == null) {
            return;
        }
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
    }

    /** 演员表太长会把版面撑爆，只留前几个。 */
    private static String shortList(String s, int max) {
        if (s == null || s.length() == 0) {
            return "";
        }
        String[] parts = s.split("[,，、/]");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < parts.length && n < max; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(p);
            n++;
        }
        if (parts.length > n) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    private void loadDetail() {
        io.execute(new Runnable() {
            public void run() {
                final Vod d = Api.detail(Src.byKey(src), vid);
                if (d != null) {
                    // 单一源经常不给简介，去其它源找同名条目补
                    if (d.intro().length() < 20) {
                        Multi.fillIntro(d);
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        applyDetail(d);
                    }
                });
            }
        });
    }

    private void applyDetail(Vod d) {
        if (d == null) {
            epCount.setText("详情拉取失败，返回重进试试");
            return;
        }
        if (d.name != null && d.name.length() > 0) {
            title.setText(d.name);
        }

        // 元信息严格四项：年份 · 地区 · 类型 · 备注
        StringBuilder sb = new StringBuilder();
        if (d.year != null && d.year.trim().length() > 0) {
            sb.append(d.year.trim());
        }
        if (d.area != null && d.area.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.area.trim());
        }
        if (d.typeName != null && d.typeName.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.typeName.trim());
        }
        if (d.remarks != null && d.remarks.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.remarks.trim());
        }
        meta.setText(sb.length() > 0 ? sb.toString() : " ");

        // 标签胶囊：导演 / 主演 / 语言，空的不出现
        tagRow.removeAllViews();
        addTag("导演", d.director);
        addTag("主演", shortList(d.actor, 4));
        addTag("语言", d.lang);

        final String text = d.intro();
        if (text.length() > 0) {
            intro.setText(text);
            intro.setTextColor(Ui.FG_DIM);
            intro.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    introOpen = !introOpen;
                    intro.setMaxLines(introOpen ? 40 : 3);
                    intro.setEllipsize(introOpen ? null : TextUtils.TruncateAt.END);
                }
            });
        } else {
            intro.setText("（各片源都没有提供这部剧的简介）");
            intro.setTextColor(Ui.FG_DIM);
            intro.setFocusable(false);
        }

        if (!d.hasEpisodes()) {
            epCount.setText(d.epUrls.length + " 个地址都不可用，换一部试试");
            epStatus.setText("选集");
            return;
        }
        epStatus.setText("选集");
        epCount.setText("共 " + d.epUrls.length + " 集");
        renderEpisodes(d);
    }

    private void addTag(String label, String val) {
        if (val == null) {
            return;
        }
        String s = val.trim();
        if (s.length() == 0 || "未知".equals(s) || "null".equalsIgnoreCase(s)) {
            return;
        }
        TextView t = Ui.text(this, s.contains("执导") ? s : label + "：" + s, 15, 0xE6FFFFFF);
        t.setSingleLine(true);
        t.setPadding(Ui.px(14), Ui.px(6), Ui.px(14), Ui.px(6));
        t.setBackground(Ui.round(0x0DFFFFFF, 999, 1, 0x1FFFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.px(12);
        tagRow.addView(t, lp);
    }

    private int lastWatched(Vod d) {
        int last = -1;
        for (int k = 0; k < d.epNames.length; k++) {
            if (Store.pos(key, k) > 5000) {
                last = k;
            }
        }
        return last;
    }

    /** 当前展示的剧（onResume 重画选集要用）。 */
    private Vod cur;

    private void renderEpisodes() {
        if (cur != null) {
            renderEpisodes(cur);
        }
    }

    private void renderEpisodes(final Vod d) {
        cur = d;
        epRows.removeAllViews();
        // 设计稿 10 列：右栏约 (1920-112-380-48) = 1380，10 格 + 9 间隙
        int gap = Ui.px(12);
        int right = Ui.w() - Ui.px(56) * 2 - Ui.px(POSTER_W) - Ui.px(48);
        int w = Math.max(Ui.px(64), (right - gap * (EP_COLS - 1)) / EP_COLS);
        int h = Ui.px(56);
        // 上次看到哪一集：焦点直接落过去，省得每次从头翻
        int last = lastWatched(d);
        if (last >= 0) {
            focusEp = last;
        }
        int i = 0;
        while (i < d.epNames.length) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(14);
            for (int c = 0; c < EP_COLS && i < d.epNames.length; c++, i++) {
                final int idx = i;
                int p = Store.pos(key, idx);
                boolean watched = p > 5000;

                FrameLayout cell = new FrameLayout(this);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setBackground(Ui.focusBgGlow(watched ? 0x222A3240 : Ui.BG_CARD, 8, Ui.ACCENT));

                final TextView t = Ui.bold(this, epLabel(d.epNames[i], idx), 18,
                        watched ? Ui.FG_DIM : Ui.FG);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setFocusable(false);
                cell.addView(t, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

                // 已看集：底部一条琥珀进度条（宽度按已看比例走，看着像真的）
                if (watched) {
                    int total = Math.max(1, Store.dur(key, idx));
                    float ratio = Math.min(1f, Math.max(0.06f, (float) p / (float) total));
                    View bar = new View(this);
                    bar.setBackgroundColor(Ui.ACCENT);
                    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                            Math.max(Ui.px(4), Math.round(w * ratio)), Ui.px(3),
                            Gravity.BOTTOM | Gravity.START);
                    cell.addView(bar, blp);
                }

                // 焦点态：设计稿 scale 1.08 + 3px 琥珀描边 + 「当前选中」小字
                final boolean w2 = watched;
                cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    public void onFocusChange(View v, boolean has) {
                        focusEp = idx;
                        v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                                .setDuration(120).start();
                        v.setBackground(has
                                ? Ui.glowBg(Ui.SURFACE_HIGH, 8, Ui.ACCENT)
                                : Ui.focusBgGlow(w2 ? 0x222A3240 : Ui.BG_CARD, 8, Ui.ACCENT));
                        t.setTextColor(has ? 0xFFFFFFFF : (w2 ? Ui.FG_DIM : Ui.FG));
                        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                                Ui.px(has ? 20 : 18));
                    }
                });

                cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        play(d, idx);
                    }
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                lp.rightMargin = gap;
                row.addView(cell, lp);
                if (idx == focusEp) {
                    final View fv = cell;
                    cell.post(new Runnable() {
                        public void run() {
                            fv.requestFocus();
                        }
                    });
                }
            }
            epRows.addView(row, rlp);
        }
        // v2.0：不再自动播放。
        // 之前「只有 1 集就自动播」是给电影用的，结果简介、导演、地区全还没看就跳走了，
        // 遥控器上还得按返回再进来一次。改成一律等用户点选集按钮。
    }

    /** 集名可能是「第01集」也可能是一整条 URL，统一收成可点的短标签。 */
    private static String epLabel(String name, int idx) {
        if (name == null || name.length() == 0) {
            return String.valueOf(idx + 1);
        }
        String s = name.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return String.valueOf(idx + 1);
        }
        // 「第01集」→「01」：10 列格子宽度有限，留「第…集」前缀会全被截成「第」
        int a = s.indexOf("第");
        int b = s.lastIndexOf("集");
        if (a >= 0 && b > a + 1 && b <= a + 6) {
            return s.substring(a + 1, b);
        }
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    private void startEp(int idx) {
        if (cur == null || !cur.hasEpisodes()) {
            showToast("选集还没加载完");
            return;
        }
        int safe = Math.min(Math.max(0, idx), cur.epUrls.length - 1);
        play(cur, safe);
    }

    private void play(Vod d, int idx) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("urls", d.epUrls);
        it.putExtra("names", d.epNames);
        it.putExtra("index", idx);
        it.putExtra("id", d.id);
        it.putExtra("src", d.src.length() > 0 ? d.src : src);
        it.putExtra("title", d.name);
        startActivity(it);
    }

    private void showToast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}

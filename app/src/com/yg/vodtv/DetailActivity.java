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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 详情页：左海报 + 右信息与选集。
 *
 * 多源之后「片名/海报」从列表页带过来先上屏（不再白屏等接口），
 * 详情接口回来后再补齐导演、主演、简介、选集。
 * 简介为空时会把片名丢去其它源找同名条目回填（Multi.fillIntro）。
 */
public final class DetailActivity extends Activity {

    /** 选集每行多少个（设计稿 10 列）。 */
    private static final int EP_COLS = 10;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int vid;
    private String src = "";
    private String vname = "";
    private String vpic = "";
    private String vrem = "";

    private TextView title;
    private TextView meta;
    private TextView info;
    private TextView intro;
    private LinearLayout epRows;
    private TextView epStatus;
    /** 多源下的唯一标识：源_id。 */
    private String key = "";
    private boolean introOpen = false;

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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        // 设计稿安全边距：左右 56，上下 40
        root.setPadding(Ui.px(56), Ui.px(40), Ui.px(56), Ui.px(32));

        /* ---------- 左栏：海报 + 返回 ---------- */
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        int pw = Ui.px(380);
        int ph = Math.round(pw * Cards.RATIO);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.BG_CARD, 12, 0, 0));
        Ui.loadPoster(iv, vpic, pw, ph, vname);
        left.addView(iv, new LinearLayout.LayoutParams(pw, ph));

        TextView back = chip("← 返回列表");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.px(20);
        left.addView(back, blp);

        root.addView(left, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        /* ---------- 右栏：标题 / 元信息 / 简介 / 选集 ---------- */
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = Ui.px(48);

        title = Ui.bold(this, vname, 52, Ui.FG);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(title);

        meta = Ui.text(this, vrem.length() > 0 ? vrem : " ", 20, Ui.ACCENT);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.px(12);
        right.addView(meta, mlp);

        // 刮削信息：导演 / 主演 / 地区 / 语言
        info = Ui.text(this, " ", 20, Ui.FG_DIM);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.px(10);
        right.addView(info, ilp);

        // 剧情简介：默认 3 行，按 OK 展开/收起
        intro = Ui.text(this, " ", 18, Ui.FG_BODY);
        intro.setMaxLines(3);
        intro.setEllipsize(TextUtils.TruncateAt.END);
        intro.setLineSpacing(Ui.px(7), 1f);
        intro.setFocusable(true);
        intro.setClickable(true);
        intro.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.px(14);
        right.addView(intro, nlp);

        epStatus = Ui.text(this, "选集加载中…", 22, Ui.FG_DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.px(24);
        right.addView(epStatus, slp);

        ScrollView epScroll = new ScrollView(this);
        epScroll.setVerticalScrollBarEnabled(false);
        epRows = new LinearLayout(this);
        epRows.setOrientation(LinearLayout.VERTICAL);
        epRows.setPadding(0, Ui.px(10), 0, Ui.px(20));
        epScroll.addView(epRows);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        elp.topMargin = Ui.px(8);
        right.addView(epScroll, elp);

        root.addView(right, rlp);
        setContentView(root);
        back.requestFocus();
    }

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 22, Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(20), Ui.px(11), Ui.px(20), Ui.px(11));
        t.setBackground(Ui.focusBg(Ui.BG_CARD, 8, Ui.ACCENT));
        return t;
    }

    private static void append(StringBuilder sb, String label, String val) {
        if (val == null) {
            return;
        }
        String s = val.trim();
        if (s.length() == 0 || "未知".equals(s) || "null".equalsIgnoreCase(s)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("    ");
        }
        sb.append(label).append("：").append(s);
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
            epStatus.setText("详情拉取失败，返回重进试试；也可以在其它片源里找同名条目");
            return;
        }
        if (d.name.length() > 0) {
            title.setText(d.name);
        }
        StringBuilder sb = new StringBuilder();
        if (d.remarks != null && d.remarks.length() > 0) {
            sb.append(d.remarks);
        }
        if (d.year != null && d.year.length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.year);
        }
        if (d.area != null && d.area.length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.area);
        }
        if (d.typeName != null && d.typeName.length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.typeName);
        }
        if (d.src.length() > 0) {
            sb.append(sb.length() > 0 ? "  ·  " : "").append(d.src);
        }
        meta.setText(sb.length() > 0 ? sb.toString() : " ");

        StringBuilder ib = new StringBuilder();
        append(ib, "导演", d.director);
        append(ib, "主演", shortList(d.actor, 4));
        append(ib, "地区", d.area);
        append(ib, "语言", d.lang);
        info.setText(ib.length() > 0 ? ib.toString() : " ");

        final String text = d.intro();
        if (text.length() > 0) {
            intro.setText(text);
            intro.setTextColor(Ui.FG_BODY);
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
        }

        if (!d.hasEpisodes()) {
            epStatus.setText("这部剧没有可用播放地址，换一部试试");
            return;
        }
        epStatus.setText("共 " + d.epUrls.length + " 集"
                + (lastWatched(d) >= 0 ? " · 上次看到 " + d.epNames[lastWatched(d)] : "")
                + " · 选一集开始播放");
        renderEpisodes(d);
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
        int w = Ui.px(118);
        int h = Ui.px(56);
        // 上次看到哪一集：焦点直接落过去，省得每次从头翻
        int focusIdx = 0;
        for (int k = 0; k < d.epNames.length; k++) {
            if (Store.pos(key, k) > 5000) {
                focusIdx = k;
            }
        }
        int i = 0;
        while (i < d.epNames.length) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(12);
            for (int c = 0; c < EP_COLS && i < d.epNames.length; c++, i++) {
                final int idx = i;
                int p = Store.pos(key, idx);
                boolean watched = p > 5000;

                FrameLayout cell = new FrameLayout(this);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setBackground(Ui.focusBg(watched ? 0xFF23282F : Ui.BG_CARD, 8, Ui.ACCENT));

                TextView t = Ui.text(this, d.epNames[i], 19, watched ? Ui.FG_DIM : Ui.FG);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setFocusable(false);
                cell.addView(t, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

                // 已看集：底部一条琥珀进度条
                if (watched) {
                    View bar = new View(this);
                    bar.setBackgroundColor(Ui.ACCENT);
                    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                            Math.max(Ui.px(4), Math.round(w * 0.36f)), Ui.px(3),
                            Gravity.BOTTOM | Gravity.START);
                    blp.leftMargin = Ui.px(8);
                    blp.bottomMargin = Ui.px(5);
                    cell.addView(bar, blp);
                }

                cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        play(d, idx);
                    }
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                lp.rightMargin = Ui.px(12);
                row.addView(cell, lp);
                if (idx == focusIdx) {
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
        if (d.epNames.length <= 1 && d.epUrls.length == 1) {
            play(d, 0);
        }
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
}

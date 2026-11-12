package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 详情页：左侧信息 + 右侧选集。 */
public final class DetailActivity extends Activity {

    private static final int EP_COLS = 6;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int vid;
    private String vname = "";
    private String vpic = "";
    private String vrem = "";

    private TextView title;
    private TextView meta;
    private TextView info;
    private TextView intro;
    private ScrollView epScroll;
    private LinearLayout epRows;
    private TextView epStatus;
    private boolean introOpen = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Store.init(this);
        vid = getIntent().getIntExtra("id", 0);
        vname = nz(getIntent().getStringExtra("name"));
        vpic = nz(getIntent().getStringExtra("pic"));
        vrem = nz(getIntent().getStringExtra("remarks"));
        buildUi();
        loadDetail();
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
        root.setPadding(Ui.px(48), Ui.px(32), Ui.px(48), Ui.px(24));

        // 左：海报
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        int pw = Ui.px(330);
        int ph = Math.round(pw * 1.4f);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.BG_CARD, 10, 0, 0));
        Ui.loadPoster(iv, vpic, pw, ph);
        left.addView(iv, new LinearLayout.LayoutParams(pw, ph));

        TextView back = chip("← 返回列表");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.px(22);
        left.addView(back, blp);

        root.addView(left, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // 右：信息 + 选集
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = Ui.px(40);

        title = Ui.text(this, vname, 48, Ui.FG);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(title);

        meta = Ui.text(this, vrem.length() > 0 ? vrem : " ", 24, Ui.ACCENT);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.px(10);
        right.addView(meta, mlp);

        // 刮削信息：导演 / 主演 / 地区 / 语言（原来这层数据被丢掉了，详情页只有片名）
        info = Ui.text(this, " ", 22, Ui.FG_DIM);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.px(12);
        right.addView(info, ilp);

        // 剧情简介：默认 3 行，按 OK 展开/收起
        intro = Ui.text(this, " ", 22, 0xFFB9C3CF);
        intro.setMaxLines(3);
        intro.setEllipsize(TextUtils.TruncateAt.END);
        intro.setLineSpacing(Ui.px(6), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.px(10);
        right.addView(intro, nlp);

        epStatus = Ui.text(this, "选集加载中…", 26, Ui.FG_DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.px(26);
        right.addView(epStatus, slp);

        epScroll = new ScrollView(this);
        epScroll.setVerticalScrollBarEnabled(false);
        epRows = new LinearLayout(this);
        epRows.setOrientation(LinearLayout.VERTICAL);
        epRows.setPadding(0, Ui.px(8), 0, Ui.px(20));
        epScroll.addView(epRows);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        elp.topMargin = Ui.px(10);
        right.addView(epScroll, elp);

        root.addView(right, rlp);
        setContentView(root);
        back.requestFocus();
    }

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 24, Ui.FG);
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
                final Vod d = Api.detail(vid);
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
            epStatus.setText("详情拉取失败，按返回键重进试试");
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
        meta.setText(sb.length() > 0 ? sb.toString() : " ");

        // 导演 / 主演 / 地区 / 语言
        StringBuilder ib = new StringBuilder();
        append(ib, "导演", d.director);
        append(ib, "主演", shortList(d.actor, 4));
        append(ib, "地区", d.area);
        append(ib, "语言", d.lang);
        info.setText(ib.length() > 0 ? ib.toString() : " ");

        // 简介（去掉 HTML 标签后再显示）
        final String text = d.intro();
        if (text.length() > 0) {
            intro.setText(text);
            intro.setVisibility(View.VISIBLE);
            intro.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    introOpen = !introOpen;
                    intro.setMaxLines(introOpen ? 40 : 3);
                    intro.setEllipsize(introOpen ? null : TextUtils.TruncateAt.END);
                }
            });
        } else {
            intro.setText("（片源没有提供简介）");
        }

        if (!d.hasEpisodes()) {
            epStatus.setText("这部剧没有可用播放地址");
            return;
        }
        int last = -1;
        for (int k = 0; k < d.epNames.length; k++) {
            if (Store.pos(d.id, k) > 5000) {
                last = k;
            }
        }
        epStatus.setText("共 " + d.epUrls.length + " 集"
                + (last >= 0 ? " · 上次看到 " + d.epNames[last] : "")
                + " · 选一集开始播放");
        renderEpisodes(d);
    }

    private void renderEpisodes(final Vod d) {
        epRows.removeAllViews();
        int w = Ui.px(150);
        int h = Ui.px(78);
        // 上次看到哪一集：焦点直接落过去，省得每次从头翻
        int focusIdx = 0;
        for (int k = 0; k < d.epNames.length; k++) {
            if (Store.pos(d.id, k) > 5000) {
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
                boolean watched = Store.pos(d.id, idx) > 5000;
                TextView t = Ui.text(this, d.epNames[i], 24, watched ? Ui.ACCENT : Ui.FG);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setFocusable(true);
                t.setClickable(true);
                t.setBackground(Ui.focusBg(Ui.BG_CARD, 8, Ui.ACCENT));
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        play(d, idx);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                lp.rightMargin = Ui.px(12);
                row.addView(t, lp);
                if (idx == focusIdx) {
                    final View fv = t;
                    t.post(new Runnable() {
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
        it.putExtra("title", d.name);
        startActivity(it);
    }
}

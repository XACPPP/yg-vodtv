package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 首页：分类浏览 + 拼音搜索键盘 + 海报墙。 */
public final class MainActivity extends Activity {

    private static final int COLS = 7;

    /**
     * 频道改为「统一频道号」（Src.CH_xxx）：各源自己的 type_id 由 Src 内部的映射表翻译，
     * 这里只表达用户想看的类别，不再硬编码某一家的编号。
     */
    private static final int[] TAB_TYPE = { Src.CH_REC, Src.CH_4K, Src.CH_ACTION, Src.CH_COMEDY,
            Src.CH_ROMANCE, Src.CH_SCIFI, Src.CH_HORROR, Src.CH_WAR, Src.CH_CNTV, Src.CH_WESTTV,
            Src.CH_KOREA, Src.CH_JAPAN, Src.CH_HK, Src.CH_TW, Src.CH_THAI, Src.CH_DOC,
            Src.CH_CNVAR, Src.CH_JPVAR, Src.CH_HKVAR, Src.CH_WESTVAR, Src.CH_CNANIME,
            Src.CH_JPANIME, Src.CH_WESTANIME, Src.CH_SUSPENSE };
    private static final String[] TAB_NAME = { "推荐", "4K电影", "动作片", "喜剧片", "爱情片", "科幻片",
            "恐怖片", "战争片", "国产剧", "欧美剧", "韩剧", "日剧", "港剧", "台剧", "泰剧", "纪录片",
            "大陆综艺", "日韩综艺", "港台综艺", "欧美综艺", "国产动漫", "日韩动漫", "欧美动漫",
            "悬疑剧" };

    private static final String[] KEYS = { "A", "B", "C", "D", "E", "F",
            "G", "H", "I", "J", "K", "L",
            "M", "N", "O", "P", "Q", "R",
            "S", "T", "U", "V", "W", "X",
            "Y", "Z", "空格", "删除", "清空", "搜索" };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout rows;
    private TextView status;
    private View nextBtn;
    private final java.util.List<TextView> tabChips = new java.util.ArrayList<TextView>();

    private LinearLayout searchOverlay;
    private LinearLayout keypad;
    private LinearLayout historyRow;
    private ScrollView resultScroll;
    private LinearLayout resultRows;
    private TextView searchInput;
    private TextView resultTitle;
    private String query = "";

    /** 停手多久自动搜。电视遥控器一个字一个字点，太快会把十个源打爆。 */
    private static final int AUTO_SEARCH_MS = 2000;
    /** 左栏放键盘所以右栏列数要少：1920 宽下左 760 + 间距 28，右剩约 1050。 */
    private static final int SEARCH_COLS = 4;
    private int searchCardW = 200;
    private int searchPosterH = 300;
    /** 上一次真搜过的词与结果数，同一个词不重复请求。 */
    private String lastKw = "";
    private int lastCount = -1;

    private TextView updateChip;
    private FrameLayout updateOverlay;
    private TextView upTitle;
    private TextView upNotes;
    private TextView upState;
    private TextView upGo;
    private Update.Info pending;

    private int curType = 0;
    private int curPage = 0;
    private int pageCount = 1;
    private int tabIndex = 0;
    private boolean loading = false;
    private int cardW = 240;
    private int posterH = 336;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Proxy.start();
        buildUi();
        load(TAB_TYPE[0], 1, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    /** ==================== 界面搭建 ==================== */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(56), Ui.px(24), Ui.px(56), Ui.px(6));

        TextView logo = Ui.text(this, "YG影视", 34, Ui.ACCENT);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(logo);

        bar.addView(Ui.text(this, "  多源聚合 · 点开即播", 18, Ui.FG_DIM));

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView tips = Ui.text(this, "方向键移动   OK 确认", 18, Ui.FG_DIM);
        tips.setPadding(0, 0, Ui.px(20), 0);
        bar.addView(tips);

        TextView searchBtn = chip("搜 索", false);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openSearch();
            }
        });
        bar.addView(searchBtn);

        TextView testBtn = chip("自检", false);
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SelfTestActivity.class));
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.px(12);
        bar.addView(testBtn, tlp);

        TextView ver = Ui.text(this, Ver.FULL, 18, Ui.FG_DIM);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = Ui.px(16);
        bar.addView(ver, vlp);

        // 更新按钮：有新版本时变成高亮的「有新版」
        updateChip = chip("检查更新", false);
        updateChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                checkUpdate(true);
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = Ui.px(12);
        bar.addView(updateChip, ulp);
        col.addView(bar);

        // 分类条
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setPadding(Ui.px(52), 0, Ui.px(52), 0);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, Ui.px(10), 0, Ui.px(10));
        for (int i = 0; i < TAB_NAME.length; i++) {
            final int idx = i;
            TextView t = chip(TAB_NAME[i], i == 0);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    if (has) {
                        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.px(12);
            chipRow.addView(t, lp);
            tabChips.add(t);
        }
        chipScroll.addView(chipRow);
        col.addView(chipScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 海报墙
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.px(52), Ui.px(8), Ui.px(52), Ui.px(36));
        scroll.addView(rows);

        // 惰性取图：滚动时只把进入视口（含一屏预取）的海报发出去。
        // 一页 84 张全量请求要排队好几分钟，期间整墙是「载入中」，用户看到就是卡死。
        ScrollView sc = scroll;
        sc.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        Ui.pump(rows);
                    }
                });

        status = Ui.text(this, "", 24, Ui.FG_DIM);
        status.setPadding(Ui.px(8), Ui.px(20), 0, Ui.px(20));
        rows.addView(status);

        col.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int avail = Ui.w() - Ui.px(112) - (COLS - 1) * Ui.px(20);
        cardW = avail / COLS;
        posterH = Math.round(cardW * Cards.RATIO);

        // 搜索页右栏 4 列；左栏 760 + 间距 28 + 两侧内边距 80
        int searchAvail = Ui.w() - Ui.px(80) - Ui.px(760) - Ui.px(28)
                - (SEARCH_COLS - 1) * Ui.px(16);
        searchCardW = searchAvail / SEARCH_COLS;
        searchPosterH = Math.round(searchCardW * Cards.RATIO);

        setContentView(root);
        buildSearch();
        buildUpdatePanel();
        // 开机静默查一次（失败只在 Trace 里留痕，不打扰看电视）
        checkUpdate(false);
    }

    private TextView chip(String label, boolean active) {
        TextView t = Ui.text(this, label, 20, active ? 0xFF12161C : Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(20), Ui.px(9), Ui.px(20), Ui.px(9));
        t.setBackground(Ui.focusBg(active ? Ui.ACCENT : Ui.BG_CARD, 8, Ui.ACCENT));
        return t;
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= TAB_TYPE.length) {
            return;
        }
        tabIndex = idx;
        for (int i = 0; i < tabChips.size(); i++) {
            boolean on = i == idx;
            TextView t = tabChips.get(i);
            t.setTextColor(on ? 0xFF12161C : Ui.FG);
            t.setBackground(Ui.focusBg(on ? Ui.ACCENT : Ui.BG_CARD, 8, Ui.ACCENT));
        }
        scroll.scrollTo(0, 0);
        load(TAB_TYPE[idx], 1, false);
    }

    /** ==================== 数据 ==================== */

    private void load(final int type, final int pg, final boolean append) {
        if (loading) {
            return;
        }
        loading = true;
        curType = type;
        if (!append) {
            status.setText("加载中…");
        } else {
            status.setText("正在加载第 " + pg + " 页…");
        }
        io.execute(new Runnable() {
            public void run() {
                final Multi.Result p = Multi.list(type, pg);
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        if (p.items.isEmpty()) {
                            Trace.add("list", "第 " + pg + " 页没数据 ch=" + type);
                            status.setText(pg == 1
                                    ? "没取到数据，检查网络后按 OK 重试（点分类任意一项）"
                                    : "没有更多了");
                            if (!append && pg == 1) {
                                addRetry(type);
                            }
                            return;
                        }
                        status.setText("");
                        renderPage(p, append);
                    }
                });
            }
        });
    }

    private void addRetry(final int type) {
        TextView t = chip("重新加载", false);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load(type, 1, false);
            }
        });
        rows.addView(t);
        t.requestFocus();
    }

    private void renderPage(final Multi.Result p, boolean append) {
        if (!append) {
            rows.removeAllViews();
            rows.addView(status);
            nextBtn = null;
        } else if (nextBtn != null && nextBtn.getParent() == rows) {
            rows.removeView(nextBtn);
            nextBtn = null;
        }

        int i = 0;
        boolean first = true;
        while (i < p.items.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(14);
            for (int k = 0; k < COLS && i < p.items.size(); k++, i++) {
                final Vod v = p.items.get(i);
                View card = Cards.poster(this, v, cardW, posterH, new View.OnClickListener() {
                    public void onClick(View x) {
                        openDetail(v);
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = Ui.px(20);
                row.addView(card, clp);
                if (first && !append) {
                    first = false;
                    final View fv = card;
                    card.post(new Runnable() {
                        public void run() {
                            fv.requestFocus();
                        }
                    });
                }
            }
            rows.addView(row, rlp);
        }

        curPage = p.page;
        pageCount = p.pagecount;
        if (status.getParent() == null) {
            rows.addView(status, 0);
        }

        if (curPage < pageCount && curPage < 400) {
            nextBtn = chip("加载更多（" + curPage + "/" + pageCount + "）", false);
            nextBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    load(curType, curPage + 1, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.px(4);
            lp.bottomMargin = Ui.px(40);
            rows.addView(nextBtn, lp);
        }

        // 卡片是刚加的，布局还没走完，等一帧再决定哪些海报进视口
        rows.post(new Runnable() {
            public void run() {
                Ui.pump(rows);
            }
        });
        // 预加载：趁用户还在看第一排，把后面几排的图先下到内存/磁盘
        if (p != null && p.items != null) {
            Ui.prefetch(p.items, cardW, posterH);
        }
    }

    private void openDetail(Vod v) {
        Intent it = new Intent(this, DetailActivity.class);
        it.putExtra("id", v.id);
        it.putExtra("name", v.name);
        it.putExtra("pic", v.pic);
        it.putExtra("remarks", v.remarks);
        it.putExtra("src", v.src);
        startActivity(it);
    }

    /** ==================== 搜索 ==================== */

    /**
     * 搜索：一屏搞定，左边键盘 + 历史，右边结果。
     *
     * 旧版是「弹出全屏键盘 → 敲完按「搜索」→ 键盘消失 → 出结果 → 想改词再点「重新输入」」，
     * 光改一个字母就要按四次键。电视上遥控器操作次数是硬成本。
     *
     * 新版：进来就能打字，停手 2 秒自动搜；结果在右边实时刷新，键盘一直看得见，
     * 随时接着改。历史词在左上，一下就能命中最常用的那几个。
     */
    private void buildSearch() {
        searchOverlay = new LinearLayout(this);
        searchOverlay.setOrientation(LinearLayout.HORIZONTAL);
        searchOverlay.setBackgroundColor(Ui.BG);
        searchOverlay.setVisibility(View.GONE);
        searchOverlay.setPadding(Ui.px(40), Ui.px(24), Ui.px(40), Ui.px(24));

        /* ---------- 左栏：输入框 + 历史 + 键盘 ---------- */
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        int leftW = Ui.px(760);

        searchInput = Ui.bold(this, "", 30, Ui.FG);
        searchInput.setSingleLine(true);
        searchInput.setPadding(Ui.px(18), Ui.px(12), Ui.px(18), Ui.px(12));
        searchInput.setBackground(Ui.round(Ui.BG_CARD, 10, 0, 0));
        left.addView(searchInput);

        resultTitle = Ui.text(this, "输入片名，停手 2 秒自动搜", 20, Ui.FG_DIM);
        resultTitle.setPadding(Ui.px(4), Ui.px(10), 0, Ui.px(8));
        left.addView(resultTitle);

        // 历史词：横向一条，点一下直接搜
        historyRow = new LinearLayout(this);
        historyRow.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(historyRow);

        keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        int kw = Ui.px(115);
        int kh = Ui.px(78);
        for (int r = 0; r < 5; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 6; c++) {
                final String key = KEYS[r * 6 + c];
                TextView k = Ui.bold(this, key, 26, Ui.FG);
                k.setGravity(Gravity.CENTER);
                k.setFocusable(true);
                k.setClickable(true);
                k.setBackground(Ui.focusBg(Ui.BG_CARD, 8, Ui.ACCENT));
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(kw, kh);
                klp.rightMargin = Ui.px(10);
                klp.bottomMargin = Ui.px(10);
                k.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        onKey(key);
                    }
                });
                line.addView(k, klp);
            }
            keypad.addView(line);
        }
        left.addView(keypad);

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                leftW, LinearLayout.LayoutParams.MATCH_PARENT);
        llp.rightMargin = Ui.px(28);
        searchOverlay.addView(left, llp);

        /* ---------- 右栏：结果 ---------- */
        resultScroll = new ScrollView(this);
        resultScroll.setVerticalScrollBarEnabled(false);
        resultRows = new LinearLayout(this);
        resultRows.setOrientation(LinearLayout.VERTICAL);
        resultRows.setPadding(0, 0, 0, Ui.px(30));
        resultScroll.addView(resultRows);

        // 结果区也做惰性取图
        resultScroll.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        Ui.pump(resultRows);
                    }
                });
        searchOverlay.addView(resultScroll, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        root.addView(searchOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void openSearch() {
        query = "";
        updateInput();
        renderHistory();
        resultRows.removeAllViews();
        TextView tip = Ui.text(this, "输入片名，停手 2 秒自动搜", 24, Ui.FG_DIM);
        tip.setPadding(0, Ui.px(10), 0, 0);
        resultRows.addView(tip);
        searchOverlay.setVisibility(View.VISIBLE);
        focusFirstKey();
    }

    /** 历史词条：点一下就把词填进去并立刻搜。 */
    private void renderHistory() {
        historyRow.removeAllViews();
        java.util.List<String> hs = Store.history();
        if (hs.isEmpty()) {
            return;
        }
        TextView label = Ui.text(this, "最近：", 20, Ui.FG_DIM);
        label.setPadding(0, Ui.px(8), Ui.px(8), Ui.px(8));
        historyRow.addView(label);
        for (int i = 0; i < hs.size(); i++) {
            final String h = hs.get(i);
            TextView t = Ui.text(this, h, 20, Ui.FG);
            t.setFocusable(true);
            t.setClickable(true);
            t.setBackground(Ui.focusBg(Ui.BG_CARD, 16, Ui.ACCENT));
            t.setPadding(Ui.px(16), Ui.px(7), Ui.px(16), Ui.px(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.px(8);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    query = h;
                    updateInput();
                    searchNow();
                }
            });
            historyRow.addView(t, lp);
        }
    }

    /** keypad 的直接子节点是“行”，行本身不可聚焦，必须落到行里的第一颗按键。 */
    private void focusFirstKey() {
        keypad.post(new Runnable() {
            public void run() {
                ViewGroup first = (ViewGroup) keypad.getChildAt(0);
                if (first != null && first.getChildCount() > 0) {
                    first.getChildAt(0).requestFocus();
                }
            }
        });
    }

    private void closeSearch() {
        ui.removeCallbacks(autoSearch);
        searchOverlay.setVisibility(View.GONE);
        if (!tabChips.isEmpty()) {
            tabChips.get(tabIndex).requestFocus();
        }
    }

    private void updateInput() {
        searchInput.setText(query.length() == 0 ? "搜索片名…" : query + "▌");
    }

    private void onKey(String key) {
        if ("删除".equals(key)) {
            if (query.length() > 0) {
                query = query.substring(0, query.length() - 1);
            }
            updateInput();
            scheduleAutoSearch();
            return;
        }
        if ("清空".equals(key)) {
            query = "";
            updateInput();
            ui.removeCallbacks(autoSearch);
            resultRows.removeAllViews();
            TextView tip = Ui.text(this, "输入片名，停手 2 秒自动搜", 24, Ui.FG_DIM);
            tip.setPadding(0, Ui.px(10), 0, 0);
            resultRows.addView(tip);
            return;
        }
        if ("搜索".equals(key)) {
            searchNow();
            return;
        }
        if ("空格".equals(key)) {
            return;
        }
        if (query.length() >= 20) {
            return;
        }
        query = query + key.toLowerCase();
        updateInput();
        scheduleAutoSearch();
    }

    /**
     * 停手 2 秒自动搜。
     *
     * 电视遥控器一个字一个字点，每点一次就发一次搜索会打爆片源（十源并发），
     * 所以要等手停下来。2 秒是手感与请求量的折中。
     */
    private void scheduleAutoSearch() {
        ui.removeCallbacks(autoSearch);
        if (query.trim().length() == 0) {
            return;
        }
        resultTitle.setText("停手 2 秒自动搜…");
        ui.postDelayed(autoSearch, AUTO_SEARCH_MS);
    }

    private final Runnable autoSearch = new Runnable() {
        public void run() {
            searchNow();
        }
    };

    /** 立刻搜（点「搜索」键、点历史词、自动搜到点都走这里）。 */
    private void searchNow() {
        ui.removeCallbacks(autoSearch);
        final String kw = query.trim();
        if (kw.length() == 0) {
            return;
        }
        if (kw.equals(lastKw) && lastCount >= 0) {
            resultTitle.setText("「" + kw + "」" + lastCount + " 个结果");
            return; // 同一个词不重复打片源
        }
        resultTitle.setText("搜索中…");
        io.execute(new Runnable() {
            public void run() {
                final java.util.List<Vod> list = Multi.search(kw);
                ui.post(new Runnable() {
                    public void run() {
                        if (!kw.equals(query.trim())) {
                            return; // 用户已经改了词，这批结果作废
                        }
                        showResult(list, kw);
                    }
                });
            }
        });
    }

    private void showResult(java.util.List<Vod> list, String kw) {
        lastKw = kw;
        lastCount = list.size();
        Store.addHistory(kw);
        renderHistory();

        resultRows.removeAllViews();
        resultTitle.setText("「" + kw + "」" + list.size() + " 个结果");

        if (list.isEmpty()) {
            TextView t = Ui.text(this, "没找到，换个全拼或中文试试（首字母缩写搜不到）", 24, Ui.FG_DIM);
            t.setPadding(0, Ui.px(20), 0, 0);
            resultRows.addView(t);
            return;
        }

        int i = 0;
        while (i < list.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(14);
            for (int k = 0; k < SEARCH_COLS && i < list.size(); k++, i++) {
                final Vod v = list.get(i);
                View card = Cards.poster(this, v, searchCardW, searchPosterH,
                        new View.OnClickListener() {
                            public void onClick(View x) {
                                openDetail(v);
                            }
                        });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(searchCardW,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = Ui.px(16);
                row.addView(card, clp);
            }
            resultRows.addView(row, rlp);
        }

        resultRows.post(new Runnable() {
            public void run() {
                Ui.pump(resultRows);
            }
        });
        Ui.prefetch(list, searchCardW, searchPosterH);
    }

    /** ==================== 更新 ==================== */

    /** 居中弹层：新版本信息 + 下载/安装。 */
    private void buildUpdatePanel() {
        updateOverlay = new FrameLayout(this);
        updateOverlay.setBackgroundColor(0xF20A0D12);
        updateOverlay.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.BG_CARD, 14, 0, 0));
        box.setPadding(Ui.px(48), Ui.px(40), Ui.px(48), Ui.px(36));

        upTitle = Ui.text(this, "发现新版本", 38, Ui.ACCENT);
        upTitle.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(upTitle);

        upNotes = Ui.text(this, "", 22, Ui.FG);
        upNotes.setLineSpacing(Ui.px(6), 1f);
        upNotes.setMaxLines(8);
        upNotes.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Ui.px(18);
        box.addView(upNotes, nl);

        upState = Ui.text(this, "", 22, Ui.FG_DIM);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.px(18);
        box.addView(upState, sl);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.px(26);

        upGo = chip("下载并安装", true);
        upGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pending == null) {
                    hideUpdate();
                    return;
                }
                doDownloadAndInstall();
            }
        });
        btns.addView(upGo);

        TextView later = chip("以后再说", false);
        later.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideUpdate();
            }
        });
        LinearLayout.LayoutParams lpl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpl.leftMargin = Ui.px(16);
        btns.addView(later, lpl);
        box.addView(btns, bl);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                Ui.px(900), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        updateOverlay.addView(box, boxLp);
        root.addView(updateOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void hideUpdate() {
        updateOverlay.setVisibility(View.GONE);
        if (!tabChips.isEmpty()) {
            tabChips.get(tabIndex).requestFocus();
        }
    }

    /**
     * @param manual 用户主动点的：没有任何新版本时也要给个反馈；
     *               开机自动查的：只有真的有新版本才弹，免得每次开机都打扰。
     */
    private void checkUpdate(final boolean manual) {
        updateChip.setText("检查中…");
        Update.check(this, new Update.Cb() {
            public void done(Update.Info info) {
                if (!info.ok) {
                    updateChip.setText(manual ? "检查失败" : "检查更新");
                    if (manual) {
                        upTitle.setText("检查更新失败");
                        upNotes.setText(info.error + "\n\n可以稍后再试；也能去 GitHub 手动下载 APK。");
                        pending = null;
                        upState.setText("");
                        showUpdatePanel(false);
                    }
                    return;
                }
                boolean newer = info.version != null && info.version.length() > 0;
                updateChip.setText(newer ? "有新版 " + info.version : "检查更新");
                updateChip.setTextColor(newer ? Ui.BG : Ui.FG);
                updateChip.setBackground(Ui.focusBg(newer ? Ui.ACCENT : Ui.BG_CARD, 8, Ui.ACCENT));
                if (!newer) {
                    if (manual) {
                        upTitle.setText("已是最新版本");
                        upNotes.setText("当前 " + Ver.FULL);
                        pending = null;
                        upState.setText("");
                        showUpdatePanel(false);
                    }
                    return;
                }
                pending = info;
                upTitle.setText("发现新版本 " + info.version);
                upNotes.setText(info.notes.length() > 0 ? info.notes : "（这次没有写更新说明）");
                upState.setText("当前 " + Ver.NAME
                        + (info.size > 0 ? "　安装包 " + (info.size / 1024 / 1024) + " MB" : ""));
                showUpdatePanel(true);
            }
        });
    }

    private void showUpdatePanel(boolean canInstall) {
        updateOverlay.setVisibility(View.VISIBLE);
        upGo.setVisibility(canInstall ? View.VISIBLE : View.GONE);
        upGo.setText("下载并安装");
        upGo.setEnabled(true);
        upGo.requestFocus();
    }

    private void doDownloadAndInstall() {
        upGo.setEnabled(false);
        upGo.setText("下载中…");
        upState.setText("正在下载安装包…");
        final Update.Info info = pending;
        new Thread(new Runnable() {
            public void run() {
                final java.io.File f = Update.download(MainActivity.this, info,
                        new Update.Progress() {
                            public void onProgress(final int percent) {
                                ui.post(new Runnable() {
                                    public void run() {
                                        upGo.setText("下载中 " + percent + "%");
                                        upState.setText("正在下载安装包… " + percent + "%");
                                    }
                                });
                            }
                        });
                ui.post(new Runnable() {
                    public void run() {
                        if (f == null) {
                            upGo.setEnabled(true);
                            upGo.setText("重试下载");
                            upState.setText("下载失败，检查网络后再试；也可以去 GitHub 手动下载。");
                            return;
                        }
                        upState.setText("下载完成，正在打开系统安装器…");
                        boolean ok = Update.install(MainActivity.this, f);
                        upGo.setEnabled(true);
                        upGo.setText("重新安装");
                        if (!ok) {
                            upState.setText("打不开安装器：请到「设置 → 安全」允许本应用安装未知来源，"
                                    + "或去 GitHub 手动下载 APK。");
                        }
                    }
                });
            }
        }, "update-download").start();
    }

    /** ==================== 按键 ==================== */

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (searchOverlay.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                closeSearch();
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }
}

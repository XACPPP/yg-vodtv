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
     * 频道条一屏完整露几个。
     *
     * 6.5 是特意留半个：整数个会让人以为「就这些了」，半个才会去按右键。
     * 老板的原话是「没必要把 24 个频道都显示出来，显得拥挤小气」。
     */
    private static final float TAB_VISIBLE = 6.5f;

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
    /** 加载中又被点了别的频道：先记下，等这一轮回来立刻换过去，别把这次点击吞掉。 */
    private int wantType = -1;
    private int cardW = 240;
    private int posterH = 336;

    /** 底部焦点信息 dock（设计稿 footer：150px 高、#11141A 底、上沿一条 hairline）。 */
    private LinearLayout dock;
    private TextView dockTitle;
    private TextView dockMeta;
    private TextView dockIntro;
    private TextView dockDetailBtn;
    private TextView dockPlayBtn;
    /** dock 当前展示的片子（焦点落在哪张卡就换成哪一部）。 */
    private Vod dockVod;
    /**
     * v2.10：dock 是否已经被用户「武装」。
     * 首页刚渲染完会自动 requestFocus 到第一张卡，Cards 的焦点回调会把第一部片
     * 直接灌进 dock —— 用户根本没选片就看到简介，以为界面卡住了。
     * 现在只有用户真的按了方向键/OK，才置 true；切分类会重置回 false。
     */
    private boolean dockArmed = false;

    /** 顶栏右侧时钟（设计稿 headline-sm 26px）。 */
    private TextView clockView;
    private final Runnable clockTick = new Runnable() {
        public void run() {
            tickClock();
            ui.postDelayed(this, 30000);
        }
    };

    private void tickClock() {
        if (clockView == null) {
            return;
        }
        clockView.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date()));
    }

    private TextView banner;
    private final Runnable hideBanner = new Runnable() {
        public void run() {
            if (banner != null) {
                banner.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Store.init(this);
        Cache.init(this);
        // 拼音首字母索引（wddcz → 伟大的长征）：只建目录，桶等用户真搜了才按需下
        PyIndex.init(this);
        // 直播表在点播页就预热好：点「电视」那一刻直接有分组可画，
        // 进去才 init 的话首屏会先空一下再填。
        LiveSync.init(this);
        LiveSync.refresh();
        // 源表现在可以从远端覆盖：先拿本地缓存的那份同步生效（几十 KB，几十毫秒），
        // 再后台默默拉最新。拉失败就一直是内置的 14 个源，不影响看电视。
        SrcSync.init(this);
        SrcSync.refresh();
        Ui.immersive(getWindow());
        Http.trustAll();
        Proxy.start();
        buildUi();
        // 上次停在哪个频道就回哪个，冷启动少按几次遥控器
        int startTab = Store.tab();
        if (startTab < 0 || startTab >= TAB_TYPE.length) {
            startTab = 0;
        }
        syncChips(startTab);
        load(TAB_TYPE[startTab], 1, false);
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
        // v2.7：纯色底 → 径向渐变（设计稿 body 是
        // radial-gradient(ellipse at 50% 30%, #151A22, #0E1116)）。
        // 纯色底在 10 尺距离上看就是一块灰板，渐变才有「屏幕发光」的层次。
        Chrome.applyPageBg(root);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(56), Ui.px(18), Ui.px(56), Ui.px(6));
        bar.setMinimumHeight(Ui.px(Chrome.BAR_H));
        bar.setClipChildren(false);

        // 品牌：v2.6 是个裸「YG」文字，设计稿是 40x40 琥珀渐变圆角方块里嵌 YG，
        // 右边再跟一行「极清影院」字标 —— 这是「有设计感」最直观的一处。
        bar.addView(Chrome.brandBlock(this));

        TextView brandTxt = Chrome.brandText(this);
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btlp.leftMargin = Ui.px(14);
        bar.addView(brandTxt, btlp);

        bar.addView(Chrome.vDivider(this));

        // 双板块：这是「影视」，实心琥珀；左边「电视」点一下去直播板块。
        // 两个板块平级、各占一屏，不再把直播塞进这里当第 25 个频道 ——
        // 那样频道条会更长，正是老板说的「拥挤小气」。
        LinearLayout boards = Chrome.boardCapsule(this);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        TextView tvOff = Chrome.boardPill(this, "电视", false);
        tvOff.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // v2.8：点「电视」直接进播放页，恢复上次看的台。
                // 旧的频道网格页（LiveActivity）已删掉 —— 电视直播就该开机即看，
                // 要挑台用播放页里的右侧抽屉（OK 键呼出）。
                startActivity(new Intent(MainActivity.this, LivePlayerActivity.class));
            }
        });
        boards.addView(tvOff);

        TextView mvOn = Chrome.boardPill(this, "影视", true);
        LinearLayout.LayoutParams mvl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mvl.leftMargin = Ui.px(4);
        boards.addView(mvOn, mvl);
        bar.addView(boards, blp2);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        // 统计胶囊（设计稿右上角「24 分类 · 14 源」那个位置）。
        // 原来这里写的是版本号 + 「极清影院」，可左边品牌区已经写了「极清影院」，
        // 同一行重复两遍纯属浪费版面；换成真实数据（分类数 / 源数）才有信息量。
        int srcCount = Src.all() == null ? 0 : Src.all().size();
        bar.addView(Chrome.statPill(this, TAB_NAME.length + " 分类 · " + srcCount + " 源"));

        // 时钟：设计稿右上角是 headline-sm 26px，10 尺视距下这是唯一「活着」的时间信息
        clockView = Ui.bold(this, "00:00", 26, 0xE6FFFFFF);
        LinearLayout.LayoutParams cklp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cklp.leftMargin = Ui.px(20);
        bar.addView(clockView, cklp);
        tickClock();
        ui.postDelayed(clockTick, 30000);

        TextView searchBtn = chip("搜 索", false);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openSearch();
            }
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sblp.leftMargin = Ui.px(12);
        bar.addView(searchBtn, sblp);

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

        // 清缓存：海报磁盘缓存有 64MB 硬上限，正常不用手动清；
        // 但万一某天缓存目录被别的东西撑满，这里能一键清干净（只删海报，不动播放记录）。
        TextView clearBtn = chip("清缓存", false);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearCacheConfirm();
            }
        });
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp2.leftMargin = Ui.px(12);
        bar.addView(clearBtn, clp2);

        TextView ver = Ui.text(this, Ver.FULL, 16, Ui.FG_DIM);
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

        // 标题行：设计稿在顶栏下面还有一层「大标题 + 内联小标签 + 副标题」，
        // v2.6 是直接从顶栏跳到分类条，整页没有任何「主标题」，看着就像一个
        // 没做完的列表页。这一行是这套设计语言里最便宜的层次感来源。
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(Ui.px(56), Ui.px(6), Ui.px(56), Ui.px(2));

        titleRow.addView(Chrome.title(this, "影视大厅"));

        TextView inline = Chrome.inlineTag(this, "VOD · 多源聚合");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.leftMargin = Ui.px(12);
        titleRow.addView(inline, ilp);

        TextView sub = Chrome.subtitle(this, "14 个采集源 · 换源自动兜底 · 支持拼音首字母");
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp2.leftMargin = Ui.px(16);
        titleRow.addView(sub, slp2);
        col.addView(titleRow);

        // 提示条（默认隐藏）：清缓存、刷新被限流这类「刚做完一件事」的反馈放这儿，
        // 比弹窗轻，不打断遥控器操作。
        banner = Ui.text(this, "", 20, 0xFF12161C);
        banner.setBackground(Ui.round(Ui.ACCENT, 8, 0, 0));
        banner.setPadding(Ui.px(20), Ui.px(10), Ui.px(20), Ui.px(10));
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.px(56);
        blp.topMargin = Ui.px(4);
        col.addView(banner, blp);

        // 分类条
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipChildren(false);
        chipScroll.setPadding(Ui.px(52), 0, Ui.px(52), 0);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setClipChildren(false);
        chipRow.setPadding(0, Ui.px(10), 0, Ui.px(10));
        // v2.8 照设计稿改回「自适应宽 + 20px 间距 + 8px 圆角」。
        // v2.7 用的是定宽 999 全胶囊，一屏 6.5 个：虽然整齐，但胶囊串在一起
        // 像一排按钮条，设计稿那种「标签云」的轻量感全没了。
        int tabGap = Ui.px(20);
        for (int i = 0; i < TAB_NAME.length; i++) {
            final int idx = i;
            TextView t = tabChip(TAB_NAME[i]);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    if (has) {
                        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start();
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = tabGap;
            chipRow.addView(t, lp);
            tabChips.add(t);
        }
        chipScroll.addView(chipRow);

        // 分类条左右两端渐隐：横向滚动没有滚动条，右端被硬切一半时
        // 看着像「内容坏了」，加一道 96px 的渐隐就变成「还有更多」。
        // 设计稿用 mask-image，Android 没有对应 API，用两层渐变 View 盖上来等效。
        final HorizontalScrollView cs = chipScroll;
        final FrameLayout chipWrap = new FrameLayout(this);
        chipWrap.setClipChildren(false);
        chipWrap.addView(chipScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        // 渐变层的高度必须给确定值，不能用 MATCH_PARENT。
        // 原因：chipWrap 自己是 WRAP_CONTENT 高，父 LinearLayout 量它时给的是
        // AT_MOST(整屏高)；FrameLayout 会把 height=MATCH_PARENT 的子 View 量成
        // AT_MOST(整屏高)，而 View.getDefaultSize() 对 AT_MOST 直接返回上限值，
        // 于是两层渐隐各占整屏高，chipWrap 被撑成整屏高，下面 weight=1 的
        // 海报墙只分到 0 高 —— v2.8 首页「只剩顶栏、底下一片黑」就是这么来的。
        // 先给 0 高不占位，布局完成后按 chipScroll 实测高度校正一次。
        final View fadeL = new View(this);
        fadeL.setBackground(Ui.railFade(true, Ui.px(32), Ui.BG));
        fadeL.setClickable(false);
        fadeL.setFocusable(false);
        final FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(Ui.px(32), 0, Gravity.START);
        chipWrap.addView(fadeL, flp);
        final View fadeR = new View(this);
        fadeR.setBackground(Ui.railFade(false, Ui.px(96), Ui.BG));
        fadeR.setClickable(false);
        fadeR.setFocusable(false);
        final FrameLayout.LayoutParams frp = new FrameLayout.LayoutParams(Ui.px(96), 0, Gravity.END);
        chipWrap.addView(fadeR, frp);
        cs.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        int h = cs.getHeight();
                        if (h <= 0 || (flp.height == h && frp.height == h)) {
                            return;
                        }
                        flp.height = h;
                        frp.height = h;
                        fadeL.setLayoutParams(flp);
                        fadeR.setLayoutParams(frp);
                    }
                });
        col.addView(chipWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 海报墙
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        // 焦点卡的外发光会溢出到 ScrollView 之外，放开裁剪免得被切边
        scroll.setClipChildren(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
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

        // 底部焦点信息 dock：设计稿 footer 的主体。v2.7 只做了它最下面那行键帽，
        // 等于把设计稿里信息密度最高的一块整个丢了 —— 一屏 14 张海报，
        // 遥控器移到哪张都只能看到片名，年份、地区、类型、简介全得进详情页才知道。
        col.addView(buildDock());

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

    /**
     * 底部键帽条。
     *
     * 设计稿每个页面底部都有这一行：左边几组「键帽 + 说明」，右边一个在线状态点。
     * 这一行不只是版权声明 —— 它是「这是个体面的产品」和「这是堆控件的 demo」
     * 的区别之一，同时也是唯一告诉用户遥控器怎么按的地方（v2.6 完全没有）。
     */
    private View buildDock() {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(0xFF11141A);
        dock.setPadding(Ui.px(56), Ui.px(10), Ui.px(56), Ui.px(4));
        dock.setMinimumHeight(Ui.px(150));

        // 上沿 hairline：设计稿 border-t border-white/[0.08]
        View line = new View(this);
        line.setBackgroundColor(0x14252C38);
        dock.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(1))));

        // 主行：左信息 / 右按钮
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        // 注意：这里必须 WRAP_CONTENT，不能 height=0 + weight。
        // dock 自身高度是 WRAP_CONTENT，LinearLayout 在 WRAP_CONTENT 下
        // 没有「剩余空间」可分，weight 子 View 会被量成 0 高，整行直接消失。
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.px(8);
        dock.addView(main, mlp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.rightMargin = Ui.px(32);
        main.addView(left, llp);

        dockTitle = Ui.bold(this, " ", 40, 0xFFFFFFFF);
        dockTitle.setSingleLine(true);
        dockTitle.setEllipsize(TextUtils.TruncateAt.END);
        dockTitle.setLetterSpacing(-0.01f);
        left.addView(dockTitle);

        dockMeta = Ui.text(this, " ", 15, Ui.FG_DIM);
        dockMeta.setSingleLine(true);
        dockMeta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metlp.topMargin = Ui.px(4);
        left.addView(dockMeta, metlp);

        dockIntro = Ui.text(this, " ", 17, Ui.FG_DIM);
        dockIntro.setMaxLines(1);
        dockIntro.setEllipsize(TextUtils.TruncateAt.END);
        dockIntro.setLineSpacing(Ui.px(4), 1f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.px(6);
        left.addView(dockIntro, ilp);

        // 右栏两钮：影片详情（描边）/ 立即播放（琥珀实心 + [OK] 键帽）
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        main.addView(btns);

        dockDetailBtn = dockButton("影片详情", false);
        dockDetailBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (dockVod != null) {
                    openDetail(dockVod);
                }
            }
        });
        btns.addView(dockDetailBtn);

        dockPlayBtn = dockButton("立即播放", true);
        dockPlayBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (dockVod == null) {
                    return;
                }
                // 直接进播放器，不等详情页：dock 上的「立即播放」就是要一步到位。
                // 剧集没有 epUrls 时（列表接口通常不带）退回详情页选集，别让用户点了没反应。
                if (dockVod.hasEpisodes()) {
                    Intent it = new Intent(MainActivity.this, PlayerActivity.class);
                    it.putExtra("urls", dockVod.epUrls);
                    it.putExtra("names", dockVod.epNames);
                    it.putExtra("index", 0);
                    it.putExtra("id", dockVod.id);
                    it.putExtra("src", dockVod.src);
                    it.putExtra("title", dockVod.name);
                    startActivity(it);
                } else {
                    openDetail(dockVod);
                }
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = Ui.px(20);
        btns.addView(dockPlayBtn, plp);

        // 底行：键帽提示 + 版本状态（原来 buildFooter() 的全部内容）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.px(4), 0, 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.px(6);
        dock.addView(row, rlp);

        row.addView(Chrome.keyHintRow(this, new String[][]{
                {"← →", "分类"},
                {"OK", "详情"},
                {"菜单", "搜索"},
                {"返回", "退出"}
        }, "OK"));

        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        // 设计稿这里只有「绿点 + 遥控器就绪」。
        // v2.7 写的是「v2.8 · 极清影院 · 遥控器信号 良好」——
        // 「信号 良好」是编的（我们读不到遥控器信号强度），版本号在顶栏已经有一份，
        // 双份都是噪音，删掉。
        row.addView(Chrome.statusDot(this, "遥控器就绪", Ui.OK_GREEN));

        // v2.10：dock 初始是「还没选片」的占位态，不预填第一部片。
        setDockIdle(true);
        return dock;
    }

    /**
     * v2.10：dock 占位态 / 正常态。
     * idle 时把标题换成提示语、两个按钮置灰且不可聚焦——遥控器焦点不会落到
     * 「点了没反应」的按钮上，这是老板抱怨"以为界面卡了"的第二层原因。
     */
    private void setDockIdle(boolean idle) {
        if (dockTitle == null) {
            return;
        }
        if (idle) {
            dockVod = null;
            dockTitle.setText("还没选片");
            dockMeta.setText("方向键选片 · OK 看详情 · 菜单搜索");
            dockIntro.setText(" ");
            setDockBtnEnabled(dockDetailBtn, false);
            setDockBtnEnabled(dockPlayBtn, false);
        } else {
            setDockBtnEnabled(dockDetailBtn, true);
            setDockBtnEnabled(dockPlayBtn, true);
        }
    }

    private void setDockBtnEnabled(TextView b, boolean on) {
        if (b == null) {
            return;
        }
        b.setAlpha(on ? 1f : 0.35f);
        b.setEnabled(on);
        b.setClickable(on);
        b.setFocusable(on);
    }

    /**
     * v2.10：用户第一次按方向键/OK 才把 dock 打开，并且顺手把当前焦点卡的内容
     * 填进去（Cards 里给每张卡 setTag(v)，这里用 tag 反查，不用另外记焦点索引）。
     */
    private void armDock() {
        if (dockArmed) {
            return;
        }
        dockArmed = true;
        View f = getCurrentFocus();
        Object tag = f == null ? null : f.getTag();
        if (tag instanceof Vod) {
            updateDock((Vod) tag);
        }
    }

    /**
     * dock 上的按钮。
     *
     * 主按钮（立即播放）= 琥珀实心 + 深色字 + 外发光，键帽 `[OK]` 嵌在文字里；
     * 次按钮（影片详情）= 半透明白底 + 20% 白描边。
     * 设计稿里这两个按钮是 footer 唯一的可聚焦元素，所以都得带焦点态。
     */
    private TextView dockButton(String label, boolean primary) {
        TextView t = primary
                ? Ui.bold(this, label, 18, Ui.BG)
                : Ui.text(this, label, 18, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(primary ? 28 : 24), Ui.px(12), Ui.px(primary ? 28 : 24), Ui.px(12));
        if (primary) {
            t.setBackground(Ui.focusBgGlow(Ui.ACCENT, 8, Ui.ACCENT_HI));
        } else {
            t.setBackground(Ui.focusBgGlow(0x14FFFFFF, 8, Ui.ACCENT));
        }
        return t;
    }

    /**
     * 焦点换到另一张卡时刷新 dock。
     *
     * 只改文字不动背景 —— 一行 7 张卡，每次焦点移动都换一次背景会闪。
     */
    private void updateDock(Vod v) {
        if (v == null || dock == null) {
            return;
        }
        // v2.10：用户还没动过遥控器时不填内容（首屏 autoFocus 会把第一部片灌进来）。
        if (!dockArmed) {
            return;
        }
        dockVod = v;
        dockTitle.setText(v.name);
        StringBuilder sb = new StringBuilder();
        if (v.year != null && v.year.trim().length() > 0) {
            sb.append(v.year.trim());
        }
        if (v.area != null && v.area.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "   ·   " : "").append(v.area.trim());
        }
        if (v.typeName != null && v.typeName.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "   ·   " : "").append(v.typeName.trim());
        }
        if (v.remarks != null && v.remarks.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "   ·   " : "").append(v.remarks.trim());
        }
        if (v.director != null && v.director.trim().length() > 0) {
            sb.append(sb.length() > 0 ? "   ·   " : "").append(v.director.trim()).append(" 导演");
        }
        dockMeta.setText(sb.length() > 0 ? sb.toString() : " ");
        String intro = v.intro();
        dockIntro.setText(intro.length() > 0 ? intro : " ");
        // 有片可看了才把两个按钮点亮/允许聚焦。
        setDockBtnEnabled(dockDetailBtn, true);
        setDockBtnEnabled(dockPlayBtn, true);
    }

    /**
     * 板块胶囊（v2.7 迁到 {@link Chrome#boardPill}，这里保留是为了兼容旧调用点）。
     * 单独留一层而不是删掉，是因为以后可能还有「选中态但不带发光」的场合。
     */
    private TextView pill(String label, boolean on) {
        return Chrome.boardPill(this, label, on);
    }

    /**
     * 小胶囊按钮。
     *
     * v2.6 是 8px 圆角 + 实心底，在一堆「满屏方块」里毫无层级感。
     * v2.7 改成设计稿的胶囊形（圆角 999）+ 焦点时外发光，
     * 跟顶栏、分组条、播放页的按钮统一成同一套语言。
     */
    /**
     * 分类胶囊（v2.8 照 Stitch 设计稿）。
     *
     * 设计稿是 `px-space-lg(32) py-space-xs(6) rounded-lg(8) font-label-lg(18)`，
     * 未选 `bg-white/[0.08] text-[#EEF2F7]`，选中 `bg-primary-container +
     * text-on-primary-container font-bold tv-tab-glow scale-105`。
     *
     * 和上面 {@link #chip} 的区别只有三点：圆角 8 而不是全胶囊、内边距更宽、
     * 字号固定 18 —— 分类条是「成排的标签」，不是「一排按钮」。
     */
    private TextView tabChip(String label) {
        TextView t = Ui.text(this, label, 18, Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(32), Ui.px(6), Ui.px(32), Ui.px(6));
        t.setBackground(Ui.focusBgGlow(0x14FFFFFF, 8, Ui.ACCENT));
        return t;
    }

    private TextView chip(String label, boolean active) {
        TextView t = Ui.text(this, label, 18, active ? Ui.BG : Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(20), Ui.px(8), Ui.px(20), Ui.px(8));
        t.setBackground(active
                ? Ui.glowBg(Ui.ACCENT, 999, Ui.ACCENT_HI)
                : Ui.focusBgGlow(0x1F1B2029, 999, Ui.ACCENT));
        return t;
    }

    /** 只切频道条的高亮，不触发加载（冷启动恢复上次频道时用）。 */
    private void syncChips(int idx) {
        tabIndex = idx;
        for (int i = 0; i < tabChips.size(); i++) {
            boolean on = i == idx;
            TextView t = tabChips.get(i);
            t.setTextColor(on ? Ui.BG : Ui.FG);
            t.setBackground(on
                    ? Ui.glowBg(Ui.ACCENT, 8, Ui.ACCENT_HI)
                    : Ui.focusBgGlow(0x14FFFFFF, 8, Ui.ACCENT));
        }
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= TAB_TYPE.length) {
            return;
        }
        // 点的就是当前频道：画面已经是对的，别推倒重来
        if (idx == tabIndex && rows.getChildCount() > 1) {
            return;
        }
        syncChips(idx);
        Store.saveTab(idx);
        // v2.10：换频道等于换一批海报，之前的「已武装」作废，dock 回到占位态。
        // 用户在新分类里再按方向键才会重新武装，不会新分类一出场就显示第一部。
        dockArmed = false;
        setDockIdle(true);
        // 先滚到顶，再加载。真正让人看到「闪一下」的不是这两行的顺序，
        // 是渲染本身异步 —— 现在 load() 里缓存命中会同步铺满画面，
        // 清空和重建落在同一帧里，中间那个空白状态根本不会被画出来。
        scroll.scrollTo(0, 0);
        load(TAB_TYPE[idx], 1, false);
        Prefetch.warm(TAB_TYPE, idx);
    }

    /** ==================== 数据 ==================== */

    private void load(final int type, final int pg, final boolean append) {
        if (loading) {
            // 上一轮还在跑就又点了别的频道：记下来，等它回来立刻补一刀。
            // 以前这里直接 return，用户连点两下频道，后点的那个完全没反应。
            if (!append) {
                wantType = type;
            }
            return;
        }
        loading = true;
        wantType = -1;
        curType = type;

        // 第一页先看磁盘：命中就同步把画面铺上去，一个「加载中」都不给看。
        // 铺完照样发网络请求，回来了比一比，内容没变就不重画（防闪烁）。
        String cached = pg == 1 ? Cache.get(type, pg) : null;
        Multi.Result cr = cached == null ? null : Multi.decode(cached, type);
        if (cr != null && !cr.items.isEmpty()) {
            status.setText(append ? "正在加载第 " + pg + " 页…" : "");
            renderPage(cr, append);
            // 缓存里的海报多半已在磁盘上。等一帧让布局跑完，再一次性把首屏铺出来。
            rows.post(new Runnable() {
                public void run() {
                    Ui.primeNow(rows);
                }
            });
        } else if (!append) {
            status.setText("加载中…");
        } else {
            status.setText("正在加载第 " + pg + " 页…");
        }

        final String before = cached;
        io.execute(new Runnable() {
            public void run() {
                final Multi.Result p = Multi.list(type, pg);
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        if (p.items.isEmpty()) {
                            Trace.add("list", "第 " + pg + " 页没数据 ch=" + type);
                            // 后台没数据时绝不清空已有画面 —— 网络抖一下就把满屏海报
                            // 换成一句「没取到数据」，比慢难受得多。
                            if (rows.getChildCount() <= 1) {
                                status.setText(pg == 1
                                        ? "没取到数据，检查网络后按 OK 重试（点分类任意一项）"
                                        : "没有更多了");
                                if (!append && pg == 1) {
                                    addRetry(type);
                                }
                            }
                            if (wantType >= 0) {
                                int t = wantType;
                                wantType = -1;
                                load(t, 1, false);
                            }
                            return;
                        }
                        // 磁盘上的内容跟这次拿到的一模一样：画面已经是它了，重画只会闪
                        if (pg == 1 && before != null && before.equals(p.raw)) {
                            Trace.add("list", "第 1 页内容未变，跳过重画 ch=" + type);
                        } else {
                            status.setText("");
                            renderPage(p, append);
                        }
                        // 这一页落盘，下次切回来（甚至下次开机）直接上屏。
                        // 只存第一页：翻页是临时行为，存了基本用不上，白占空间。
                        if (pg == 1 && p.raw != null && p.raw.length() > 20) {
                            Cache.put(type, 1, p.raw);
                        }
                        Prefetch.warm(TAB_TYPE, tabIndex);
                        if (wantType >= 0) {
                            int t = wantType;
                            wantType = -1;
                            load(t, 1, false);
                        }
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

    /**
     * 清缓存。只在内存里做，不弹系统对话框 —— 电视上没有触摸，
     * 二次确认用一个 toast 式的开关太别扭，所以直接清 + 提示，清掉的只是海报缓存，
     * 播放记录、搜索历史都在 SharedPreferences 里，不受影响。
     */
    private void clearCacheConfirm() {
        long before = Ui.diskBytes();
        Ui.clearDisk();
        // 列表 JSON 也一起清，否则「清了缓存」之后切频道还是秒出旧列表，
        // 用户会以为没清干净。
        Cache.clear();
        // 首字母表也清掉：一个桶几百 KB，不清的话「已释放」的数字会明显偏小
        PyIndex.clear();
        Trace.add("cache", "手动清缓存，释放 " + (before / 1024) + "KB");
        showBanner("缓存已清空（释放 " + (before / 1024) + "KB），海报会重新下载");
        if (rows != null && rows.getChildCount() > 0) {
            load(curType, 1, false);
        }
    }

    /** 顶栏下方飘一条提示，2.5 秒后自己消失。 */
    private void showBanner(String msg) {
        if (banner == null) {
            return;
        }
        banner.setText(msg);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 2500);
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
            // 焦点卡放大 1.06 + 2px 外发光，行容器不放开裁剪会把上沿辉光切一条，
            // 描边看起来就变成「缺了个口」。
            row.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(14);
            for (int k = 0; k < COLS && i < p.items.size(); k++, i++) {
                final Vod v = p.items.get(i);
                View card = Cards.poster(this, v, cardW, posterH, new View.OnClickListener() {
                    public void onClick(View x) {
                        openDetail(v);
                    }
                }, true, new Cards.OnCardFocus() {
                    public void onCardFocus(Vod dv) {
                        updateDock(dv);
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
                            // v2.10：只给焦点，不再顺手把第一部片的简介填进 dock。
                            // 老板反馈「还没选影片，下面就显示第一部简介，以为界面卡了」。
                            // dock 的文案由卡片真正的焦点回调（onCardFocus）来填。
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

        // 卡片是刚加的，布局还没走完，等一帧再决定哪些海报进视口。
        // primeNow 会把首屏这一批图在同一个后台任务里解完，再一次性贴上屏 ——
        // 一张一张地贴，用户看到的就是「海报一张张跳出来」。
        rows.post(new Runnable() {
            public void run() {
                Ui.primeNow(rows);
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
        searchOverlay.setBackground(Ui.pageBg());
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
            TextView t = Ui.text(this, "没找到，换个中文名或全拼试试", 24, Ui.FG_DIM);
            t.setPadding(0, Ui.px(20), 0, 0);
            resultRows.addView(t);
            return;
        }

        int i = 0;
        while (i < list.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
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
        // v2.10：方向键/OK 一按，就算用户开始选片了，dock 从占位态切成真实内容。
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                armDock();
                break;
            default:
                break;
        }
        return super.onKeyDown(code, e);
    }
}

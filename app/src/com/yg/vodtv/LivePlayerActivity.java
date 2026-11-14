package com.yg.vodtv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 直播播放页 —— 进来就是画面，不是频道墙。
 *
 * ==================== v2.8：直播入口改成「直接播」 ====================
 *
 * v2.7 点「电视」先看到一页 6 列频道海报（照影视首页抄的网格），要再点一次才出画面。
 * 那是点播 App 的骨架，不是电视的习惯：电视直播就是「开机即看、上下键换台」。
 * v2.8 起 MainActivity 点「电视」直达本页并恢复上次看的台，
 * 频道列表降级成右侧抽屉（菜单键 / OK 键呼出），换台不再离开画面。
 *
 * 遥控器约定（对齐电视直播的通用习惯）：
 *   ↑↓        换台（抽屉收起时）/ 列表内移动焦点（抽屉展开时）
 *   ← →       切分组（抽屉展开时）／切线路（抽屉收起时）
 *   OK        呼出频道列表（抽屉展开时 = 播放焦点台）
 *   菜单       线路列表（同一个台的几路源）
 *   红键       循环画面比例：自动 / 适应 / 铺满
 *   返回       收起浮层 → 再按退出
 *
 * ==================== v2.8：比例算法整体换掉 ====================
 *
 * v2.7 是把默认模式改成 MODE_FIT 来躲「截断」，那只是绕着走：
 * 16:9 的频道明明可以满屏，也被迫左右留两条黑边。
 * 根因在 FitVideoView.wantFill()：它比的是「铺满后裁掉多少 ≤34%」，
 * 这个语义本身就不对（详见 PlayerActivity.MAX_AR_DEV 的注释）。
 *
 * v2.8 换成比「视频比例 vs 屏幕比例的偏差」：
 *   偏差 ≤4% → 铺满（16:9 频道在 16:9 电视上就是这一档，真满屏、不裁）
 *   偏差 >4% → 适应（4:3 / 2.35:1 之类，宁可留黑也不切画面）
 * 所以这里默认回到 MODE_AUTO —— 现在它才是「最省事又最对」的那一档。
 *
 * ==================== v2.8：黑屏检测 ====================
 *
 * 「能起播但黑屏」（湖南卫视那类源）骗过了所有旧的判活逻辑：
 * prepared 成功、position 在走、isPlaying() 为 true，就是没画面。
 * 现在起播 6 秒后检查一次 mp.getVideoWidth()，为 0 说明解码器根本没拿到画面，
 * 直接判死切下一路。
 */
public final class LivePlayerActivity extends Activity {

    /**
     * 遥控器「频道 +」「频道 -」。国产电视/盒子基本都映射这两个码。
     * 注意：这两个码不是所有 ROM 都发，所以换台的主要入口是 ↑↓，这两个只是顺手支持。
     */
    private static final int KEY_CH_UP = 166;
    private static final int KEY_CH_DOWN = 167;
    /** 遥控器红键：循环画面比例。国产遥控器普遍有这四个彩色键。 */
    private static final int KEY_RED = 183;

    /* 看门狗阈值 */
    /**
     * v2.10：15s → 12s。
     * Proxy 拉播放列表的超时已经从 12s/20s 收到 5s/8s，一路源 5 秒建不上连
     * 基本就是死了；再给 15 秒只是让用户多盯着转圈 3 秒。
     */
    private static final long START_TIMEOUT_MS = 12000;
    private static final long BUFFER_TIMEOUT_MS = 9000;
    private static final long STALL_TIMEOUT_MS = 12000;

    /**
     * 黑屏检测窗口。起播后这么久仍然 videoWidth==0，就认为这一路「有声无画」。
     *
     * 湖南卫视那类源就是典型：prepared 成功、position 在走、isPlaying() 也是 true，
     * 但解码器一个像素都没解出来，屏幕上纯黑。旧的三种判活全都放它过去。
     * 6 秒是折中：给的短了会把「起播慢但最终能出画」的好源误杀，给太长用户已经骂完了。
     */
    private static final long BLANK_TIMEOUT_MS = 6000;

    /** 顶栏高度（设计稿 84px）。 */
    private static final float TOP_H = 84f;
    /** 底部键帽条高度（设计稿 56px）。 */
    private static final float FOOT_H = 56f;
    /** 右侧频道抽屉宽度（设计稿 560px）。 */
    private static final float DRAWER_W = 560f;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private PlayerActivity.FitVideoView vv;
    private ProgressBar busy;
    private TextView busyText;
    private View topBar;
    private TextView topName;
    private TextView topSub;
    private TextView topBadge;
    private TextView topClock;
    private View liveDot;
    private TextView bigMsg;
    private TextView toast;
    private LinearLayout bottomWrap;
    private LinearLayout panel;
    private LinearLayout panelSources;

    /* 音量 HUD */
    private View hud;
    private View hudBar;
    private TextView hudValue;
    private static final int HUD_BAR_W = 120;
    /* 音量护栏（v2.20）：记住上一次的音量 index 和用户最近一次按音量键的时间 */
    private int lastVol = -1;
    private long lastVolAt = 0;
    private long lastResyncAt = 0;

    /* 右侧频道抽屉 */
    private LinearLayout drawer;
    private TextView drawerCount;
    private LinearLayout groupStrip;
    private ScrollView drawerScroll;
    private LinearLayout drawerList;

    private List<Live.Group> groups = new ArrayList<Live.Group>();
    private Live.Group group;
    private Live.Ch ch;
    private List<String> urls = new ArrayList<String>();

    private int groupIdx = 0;
    private int chIdx = 0;
    private int urlIdx = 0;
    /** 抽屉里正在浏览的分组。跟正在播的 groupIdx 分开，避免「翻列表把正在看的台换掉」。 */
    private int browseGroupIdx = 0;

    /** 这一轮已经自动跳过的次数。到 urls.size()*2 就认为这个台整体不可用。 */
    private int autoSkips = 0;
    private boolean panelVisible = false;
    private boolean drawerVisible = false;
    private boolean dead = false;
    private boolean paused = false;

    /* 看门狗状态 */
    private long playAt = 0;
    private long bufAt = 0;
    private int lastPos = -1;
    private long posAt = 0;
    /** onPrepared 的时刻，用来给「黑屏检测」计时。0 表示还没 prepared。 */
    private long preparedAt = 0;
    /** 最近一次成功起播的耗时（ms），给顶栏「接入 1.2s」用；0 = 还没量过。 */
    private long startMs = 0;
    /** 播放器报上来的视频宽度。0 = 解码器还没解出画面。 */
    private int videoW = 0;
    /**
     * 本路源累计卡顿次数。前面的四条判据都只抓「一次性卡死」，
     * 抓不到「每 10 秒卡 3 秒、卡完自己恢复」的烂源 —— 那种源在用户眼里就是
     * 「10 个台 7 个都在转圈」。累计 4 次就直接判死换下一路。
     */
    private int stalls = 0;
    /** 最近一次 BUFFERING_START 的时刻，用来给上面的累计做去抖（8 秒内不重复记）。 */
    private long lastStallAt = 0;
    private static final int MAX_STALLS = 4;
    private static final long STALL_DEBOUNCE_MS = 8000;

    /**
     * 画面比例。v2.8 起默认「自动」，因为自动的判据换了：
     *
     * v2.7 默认自适应是为了躲开旧的 AUTO —— 旧 AUTO 比的是「铺满后裁掉多少 ≤34%」，
     * 4:3 频道裁 25% 就判铺满了，台标字幕直接被切。
     * v2.8 的 AUTO 比的是「视频比例和屏幕比例差多少」，差得少才铺满：
     *   16:9 频道 + 16:9 电视 → 偏差 0 → 铺满（真正满屏，一分不裁）
     *   4:3 频道  + 16:9 电视 → 偏差 25% → 适应（两侧留黑，完整显示）
     */
    private int scaleMode = PlayerActivity.MODE_AUTO;
    private static final double MAX_AR_DEV = 0.04;

    private final Runnable hideInfo = new Runnable() {
        public void run() {
            if (topBar != null) {
                topBar.animate().alpha(0f).setDuration(260).start();
            }
        }
    };

    /** 抽屉滑出动画结束后才真正 GONE，否则会看到半截抽屉挂在右边。 */
    private final Runnable hideDrawerAnim = new Runnable() {
        public void run() {
            if (!drawerVisible && drawer != null) {
                drawer.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable hidePanel = new Runnable() {
        public void run() {
            if (panelVisible) {
                togglePanel();
            }
        }
    };

    private final Runnable hideHud = new Runnable() {
        public void run() {
            if (hud != null) {
                hud.animate().alpha(0f).setDuration(220).start();
            }
        }
    };

    private final Runnable clockTick = new Runnable() {
        public void run() {
            if (topClock != null) {
                topClock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
            }
            ui.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Trace.attach(this);
        Ui.init(this);
        Store.init(this);
        LiveSync.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Proxy.start();

        // v2.16：播放页用 playerGroups() —— 内置江西电信 5 组在前，远端公共表合并成
        // 末尾一个「公共源」组。远端那批在电信家宽上实测成片连不上，摆在主路径上
        // 只会让人点进去看黑屏。
        groups = Live.playerGroups();
        // v2.12：老用户升级后第一次进直播，把默认分组挪到「江西电信」。
        // 不迁的话记忆里还是公共表的下标 —— 老板升级完点进直播，看到的仍是那个
        // 三路源全在转圈的 CCTV-8，等于这次换源白做。迁一次就够，之后按他自己的选择走。
        if (!getIntent().hasExtra("groupIdx") && !Store.liveJxMigrated()) {
            int jx = Live.jiangxiGroup();
            if (jx >= 0) {
                Store.saveLive(jx, 0);
                Store.clearLiveName();
            }
            Store.markLiveJxMigrated();
            Trace.add("live", "已把默认分组迁到江西电信 idx=" + jx);
        }
        // 入口不再要求「先选组再选台」：没带参数就恢复上次看的台
        groupIdx = getIntent().getIntExtra("groupIdx", Store.liveGroup());
        chIdx = getIntent().getIntExtra("chIdx", Store.liveChannel());
        if (groupIdx < 0 || groupIdx >= groups.size()) {
            groupIdx = 0;
        }
        // 先把「上次看的那个台」按台名找回来。源表每天巡检一次，台的顺序天天变，
        // 只靠下标恢复一定会播成别的台 —— 存名字才靠得住。
        // 带了 groupIdx/chIdx 参数（从别处点进来）时以参数为准，不去覆盖它。
        if (!getIntent().hasExtra("groupIdx") && !getIntent().hasExtra("chIdx")) {
            restoreByName(Store.liveName());
        }
        browseGroupIdx = groupIdx;

        buildUi();

        // 记忆的台可能已经被新一版 live.json 删掉了（v2.8 起源表筛得更狠，整台会消失），
        // 这时候不能直接报「没有可用的直播源」，得往后找第一个真能播的台。
        if (groups.isEmpty() || !pickChannel()) {
            if (!firstPlayable()) {
                showDead("没有可用的直播源");
                return;
            }
        }
        ui.post(new Runnable() {
            public void run() {
                playPreferred();
            }
        });
        ui.postDelayed(watchdog, 2000);
        ui.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 直播没有「暂停」这回事，切走就停掉，回来重新连
        if (vv != null) {
            try {
                vv.pause();
            } catch (Throwable t) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v2.20：从别的应用切回来，固件可能已经把扬声器增益改过了，重新对齐一次
        resyncVolume("回到前台");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vv != null) {
            try {
                vv.stopPlayback();
            } catch (Throwable t) {
            }
        }
        if (liveDot != null && liveDot.getTag() instanceof android.animation.ObjectAnimator) {
            ((android.animation.ObjectAnimator) liveDot.getTag()).cancel();
        }
        ui.removeCallbacksAndMessages(null);
    }

    /* ==================== 界面 ==================== */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(true);

        vv = new PlayerActivity.FitVideoView(this);
        vv.setFocusable(false);
        vv.setMode(scaleMode, MAX_AR_DEV);
        // 铺满时视图会比容器大，必须靠 CENTER 让它居中溢出，
        // 否则默认 START|TOP 会贴左上，看起来就是「只截掉右下角」
        root.addView(vv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        // 四边压暗：设计稿是左右 + 上下两道渐变叠出来的「中间最亮」。
        // 没有这层，顶条/底条/右侧抽屉压上去就像贴纸，台标水印也会撞节目字幕。
        View veil = new View(this);
        veil.setBackground(Ui.playerVeil());
        veil.setFocusable(false);
        veil.setClickable(false);
        root.addView(veil, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 右下角斜体水印：真实频道右下角常年挂着台标与字幕，15% 白的字不会抢戏
        TextView mark = Ui.bold(this, "YG", 28, 0x26FFFFFF);
        mark.setTypeface(mark.getTypeface(), android.graphics.Typeface.ITALIC);
        mark.setLetterSpacing(0.06f);
        mark.setFocusable(false);
        FrameLayout.LayoutParams mkp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        mkp.rightMargin = Ui.px(24);
        mkp.bottomMargin = Ui.px(76);
        root.addView(mark, mkp);

        // 缓冲中：转圈 + 一句说明，别让用户对着纯黑猜
        FrameLayout loading = new FrameLayout(this);
        busy = new ProgressBar(this);
        loading.addView(busy, new FrameLayout.LayoutParams(Ui.px(84), Ui.px(84), Gravity.CENTER));
        busyText = Ui.text(this, "正在缓冲…", 18, Ui.FG_DIM);
        FrameLayout.LayoutParams btl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        btl.topMargin = Ui.px(140);
        loading.addView(busyText, btl);
        root.addView(loading, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bigMsg = Ui.bold(this, "", 30, Ui.FG);
        bigMsg.setGravity(Gravity.CENTER);
        bigMsg.setVisibility(View.GONE);
        root.addView(bigMsg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        topBar = buildTopBar();
        root.addView(topBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        hud = buildHud();
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        hlp.leftMargin = Ui.px(48);
        hlp.bottomMargin = Ui.px(72);
        hud.setVisibility(View.GONE);
        root.addView(hud, hlp);

        // 底部：线路面板 + 键帽条
        bottomWrap = new LinearLayout(this);
        bottomWrap.setOrientation(LinearLayout.VERTICAL);
        buildPanel();
        bottomWrap.addView(panel);
        bottomWrap.addView(buildBottomBar());
        root.addView(bottomWrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        buildDrawer();
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                Ui.px(DRAWER_W), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        dlp.topMargin = Ui.px(TOP_H);
        dlp.bottomMargin = Ui.px(FOOT_H);
        root.addView(drawer, dlp);

        // 轻提示放最后，压在所有浮层上
        toast = Ui.bold(this, "", 18, Ui.FG);
        toast.setGravity(Gravity.CENTER);
        toast.setPadding(Ui.px(20), Ui.px(9), Ui.px(20), Ui.px(9));
        toast.setBackground(Ui.round(0xE61B2029, 10, 1, 0x33FFFFFF));
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        tlp.topMargin = Ui.px(200);
        root.addView(toast, tlp);

        setContentView(root);
    }

    /**
     * 顶部信息条（设计稿 84px）：品牌块 + 台名 + 清晰度角标 + 副行；右侧「正在直播」胶囊 + 时钟。
     *
     * 背景用「上深下透明」的渐变，贴在视频上不会压出一条硬边。
     */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(56), 0, Ui.px(56), 0);
        bar.setMinimumHeight(Ui.px(TOP_H));
        // 设计稿是 rgba(14,17,22,0.92) → 0.5 → transparent 的三段深蓝黑；
        // 纯黑两段渐变在真实画面上会显出一条灰边
        bar.setBackground(Ui.fade(true,
                new int[] { 0xEB0E1116, 0x800E1116, 0x000E1116 },
                new float[] { 0f, 0.7f, 1f }));

        bar.addView(Chrome.brandBlock(this));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.px(16);

        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);

        topName = Ui.bold(this, "", 26, 0xFFFFFFFF);
        topName.setSingleLine(true);
        topName.setEllipsize(TextUtils.TruncateAt.END);
        tagRow.addView(topName);

        topBadge = Ui.bold(this, "HD", 11, 0xE6FFFFFF);
        topBadge.setGravity(Gravity.CENTER);
        topBadge.setPadding(Ui.px(7), Ui.px(2), Ui.px(7), Ui.px(2));
        topBadge.setBackground(Ui.round(0x1AFFFFFF, 4, 1, 0x26FFFFFF));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.px(10);
        tagRow.addView(topBadge, blp);

        col.addView(tagRow);

        topSub = Ui.text(this, "", 13, Ui.FG_DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.px(2);
        col.addView(topSub, slp);

        bar.addView(col, clp);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        // 「正在直播」红色胶囊：白点呼吸 + 文字
        LinearLayout live = new LinearLayout(this);
        live.setOrientation(LinearLayout.HORIZONTAL);
        live.setGravity(Gravity.CENTER_VERTICAL);
        live.setPadding(Ui.px(14), Ui.px(6), Ui.px(14), Ui.px(6));
        live.setBackground(Ui.round(0xE0E50914, 999, 1, 0x66FF6B6B));

        liveDot = new View(this);
        liveDot.setBackground(Ui.round(0xFFFFFFFF, 6, 0, 0));
        live.addView(liveDot, new LinearLayout.LayoutParams(Ui.px(10), Ui.px(10)));

        TextView liveTxt = Ui.bold(this, "正在直播", 13, 0xFFFFFFFF);
        LinearLayout.LayoutParams ltl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ltl.leftMargin = Ui.px(8);
        live.addView(liveTxt, ltl);

        bar.addView(live);

        // 呼吸动画：设计稿是 opacity 1→0.4 同时 scale 1→0.85、1.8s ease-in-out。
        // 只做透明度会像「灯坏了」，加上轻微缩放才像心跳。
        android.animation.PropertyValuesHolder a =
                android.animation.PropertyValuesHolder.ofFloat("alpha", 1f, 0.4f);
        android.animation.PropertyValuesHolder sx =
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 0.85f);
        android.animation.PropertyValuesHolder sy =
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 0.85f);
        android.animation.ObjectAnimator breath = android.animation.ObjectAnimator
                .ofPropertyValuesHolder(liveDot, a, sx, sy);
        breath.setDuration(1800);
        breath.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breath.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breath.start();
        liveDot.setTag(breath);

        topClock = Ui.bold(this, "00:00", 26, 0xFFFFFFFF);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.leftMargin = Ui.px(24);
        bar.addView(topClock, klp);

        return bar;
    }

    /** 左下角音量 HUD：喇叭 + 轨道 + 数字，按音量键才出现，2 秒后淡出。 */
    private View buildHud() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.px(16), Ui.px(12), Ui.px(16), Ui.px(12));
        // 设计稿 rgba(18,22,29,0.75)：半透深蓝黑，压在节目画面上还能透出一点底色
        box.setBackground(Ui.round(0xBF12161D, 12, 1, 0x1AFFFFFF));
        box.setMinimumWidth(Ui.px(230));

        SpeakerIcon icon = new SpeakerIcon(this, Ui.ACCENT);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.px(20), Ui.px(20)));

        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.round(0x33FFFFFF, 4, 0, 0));
        hudBar = new View(this);
        hudBar.setBackground(Ui.round(Ui.ACCENT, 4, 0, 0));
        track.addView(hudBar, new FrameLayout.LayoutParams(Ui.px(HUD_BAR_W / 2), Ui.px(6)));
        LinearLayout.LayoutParams tkl = new LinearLayout.LayoutParams(
                Ui.px(HUD_BAR_W), Ui.px(6));
        tkl.leftMargin = Ui.px(12);
        tkl.rightMargin = Ui.px(12);
        box.addView(track, tkl);

        hudValue = Ui.bold(this, "50", 15, 0xFFFFFFFF);
        box.addView(hudValue);

        return box;
    }

    private void adjustVolume(int delta) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        try {
            lastVolAt = System.currentTimeMillis();
            // v2.20：改成系统的相对步进（ADJUST_RAISE/LOWER）。旧版自己算 cur+delta 再
            // setStreamVolume 绝对值写回，和电视固件自己的音量表（0~30 档）对不上，
            // 会把固件扬声器拉到一个和 Android index 不一致的位置 —— 表现为「声音自己飙到最大」。
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            showVolume(cur, max);
            Trace.add("live", "调音量 " + (delta > 0 ? "+" : "-") + " 当前 " + cur + "/" + max);
        } catch (Throwable t) {
            Trace.err("live", "调音量失败", t);
        }
    }

    /**
     * v2.20：音量回写（治「声音经常自己飙到最大、按一下音量键又正常」）。
     *
     * 现象能复现的机制：媒体解码器重建（换台 / 自动换源 / Dolby↔PCM 切换）之后，
     * 这块海信固件会把它自己的扬声器增益推满，而 Android 侧的 STREAM_MUSIC index
     * 一个数都没变 —— 所以 App 这边看音量还是 13/100，耳朵里已经是最大声。
     * 老板按一下音量键之所以「又恢复正常」，是因为那次按键触发了一次
     * setStreamVolume，固件拿 index 重新对了一遍扬声器。
     *
     * 既然按键能纠正，就把这个动作在「解码器刚重建完」这个时间点主动做一次，
     * 而且写的是**当前 index 本身**（音量值不变，只让固件重新对齐），
     * 用户不会听到音量被改，也不会看到 OSD。
     */
    private void resyncVolume(String why) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, cur, 0);
            lastVol = cur;
            Trace.add("live", "音量回写 " + cur + "/" + max + "（" + why + "）");
        } catch (Throwable t) {
            Trace.err("live", "音量回写失败", t);
        }
    }

    /**
     * v2.20：音量护栏。看门狗每 2 秒顺手看一眼 index。
     *
     * 只要「没人按音量键」却出现一次大跳（往上 ≥1/8 满量程），就退回上一档并记日志；
     * 往下的大跳只记日志不退（用户可能用电视自己的按键或手机 App 调过，别硬掰）。
     * 这样即使固件在别的地方推满，最多也就响 2 秒，而且下次能从 ygtv.log 里
     * 查到「几点几分、从多少跳到多少」，比只凭耳朵判断靠谱。
     */
    private void guardVolume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            long now = System.currentTimeMillis();
            if (lastVol < 0 || max <= 0) {
                lastVol = cur;
                return;
            }
            int jump = cur - lastVol;
            boolean byUser = now - lastVolAt < 2500;
            if (jump > 0 && jump >= Math.max(6, max / 8) && !byUser) {
                Trace.add("live", "音量被顶上去了 " + lastVol + "→" + cur + "/" + max + "，回退");
                am.setStreamVolume(AudioManager.STREAM_MUSIC, lastVol, 0);
                showVolume(lastVol, max);
                return;
            }
            if (jump < 0 && -jump >= Math.max(8, max / 4) && !byUser) {
                Trace.add("live", "音量被外部调低 " + lastVol + "→" + cur + "/" + max);
            }
            lastVol = cur;
        } catch (Throwable t) {
            Trace.err("live", "音量护栏异常", t);
        }
    }

    private void showVolume(int cur, int max) {
        if (hud == null || max <= 0) {
            return;
        }
        int w = Math.round(Ui.px(HUD_BAR_W) * (cur / (float) max));
        hudBar.getLayoutParams().width = Math.max(0, w);
        hudBar.requestLayout();
        hudValue.setText(String.valueOf(cur * 100 / max));
        hud.setVisibility(View.VISIBLE);
        hud.setAlpha(1f);
        ui.removeCallbacks(hideHud);
        ui.postDelayed(hideHud, 2000);
    }

    /** 底部键帽条（设计稿 56px）；红键用红底，其他用半透明白。 */
    private View buildBottomBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.px(56), 0, Ui.px(56), 0);
        wrap.setMinimumHeight(Ui.px(FOOT_H));
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        // 设计稿：rgba(14,17,22,0.85) + 顶部 1px 白线。
        // 纯黑渐变贴到视频底部会「整条黑了」，深蓝黑半透才像玻璃条
        wrap.setBackground(Ui.fade(true,
                new int[] { 0xD90E1116, 0xD90E1116 },
                new float[] { 0f, 1f }));

        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(1))));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(keyCaps(new String[][]{
                { "↑ ↓", "换台" },
                { "← →", "切线路" },
                { "OK", "频道列表" },
                { "菜单", "线路" },
                { "红键", "画面比例" },
                { "返回", "退出" },
        }));

        // 右侧状态字：跟设计稿一致，说明这版是 1080P60 直出
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView os = Ui.text(this, "YG TV OS · 1080P 60Hz", 12, 0xB39AA6B5);
        row.addView(os);

        wrap.addView(row);
        return wrap;
    }

    /** 键帽 + 说明。红键单独给红底，跟遥控器上的实体键对上。 */
    private View keyCaps(String[][] items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < items.length; i++) {
            String key = items[i][0];
            boolean red = key.contains("红");
            boolean main = "OK".equals(key);

            TextView cap = Ui.bold(this, key, 13, red ? 0xFFFFFFFF : 0xFFFFFFFF);
            cap.setGravity(Gravity.CENTER);
            cap.setPadding(Ui.px(8), Ui.px(3), Ui.px(8), Ui.px(3));
            if (red) {
                cap.setBackground(Ui.round(0xCCE50914, 5, 1, 0x88FF6B6B));
            } else if (main) {
                cap.setBackground(Ui.round(Ui.ACCENT, 5, 0, 0));
                cap.setTextColor(Ui.BG);
            } else {
                cap.setBackground(Ui.round(0x1AFFFFFF, 5, 1, 0x26FFFFFF));
            }
            row.addView(cap);

            TextView desc = Ui.text(this, items[i][1], 13, Ui.FG_DIM);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = Ui.px(7);
            dlp.rightMargin = Ui.px(22);
            row.addView(desc, dlp);
        }
        return row;
    }

    /** 换源面板：底部一排源，当前那个实心琥珀。 */
    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF20B0E13);
        panel.setPadding(Ui.px(56), Ui.px(20), Ui.px(56), Ui.px(22));
        panel.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Chrome.title(this, "切换线路"));
        TextView tip = Chrome.subtitle(this, "　同一个台有多路源，挑能播的就行");
        head.addView(tip);
        panel.addView(head);

        panelSources = new LinearLayout(this);
        panelSources.setOrientation(LinearLayout.HORIZONTAL);
        panelSources.setClipChildren(false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Ui.px(14);
        panel.addView(panelSources, pl);
    }

    private void renderPanel() {
        panelSources.removeAllViews();
        for (int i = 0; i < urls.size(); i++) {
            final int idx = i;
            boolean on = i == urlIdx;
            TextView c = on
                    ? Ui.bold(this, "线路 " + (i + 1), 19, Ui.BG)
                    : Ui.bold(this, "线路 " + (i + 1), 19, Ui.FG);
            c.setGravity(Gravity.CENTER);
            c.setSingleLine(true);
            c.setFocusable(true);
            c.setClickable(true);
            c.setPadding(Ui.px(26), Ui.px(10), Ui.px(26), Ui.px(10));
            if (on) {
                c.setBackground(Ui.glowBg(Ui.ACCENT, 999, Ui.ACCENT_HI));
            } else {
                c.setBackground(Ui.focusBgGlow(0x00000000, 999, Ui.ACCENT));
            }
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    manual();
                    play(idx);
                    if (panelVisible) {
                        togglePanel();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.px(12);
            panelSources.addView(c, lp);
        }
        if (urls.isEmpty()) {
            panelSources.addView(Ui.text(this, "这个台没有可用的线路", 18, Ui.FG_DIM));
        }
    }

    private void togglePanel() {
        panelVisible = !panelVisible;
        panel.setVisibility(panelVisible ? View.VISIBLE : View.GONE);
        if (panelVisible && drawerVisible) {
            toggleDrawer();
        }
        ui.removeCallbacks(hidePanel);
        if (panelVisible) {
            renderPanel();
            if (panelSources.getChildCount() > 0) {
                panelSources.getChildAt(0).requestFocus();
            }
            // 12 秒没动就自己收起来，免得一直压着画面
            ui.postDelayed(hidePanel, 12000);
        } else {
            vv.requestFocus();
        }
    }

    /* ==================== 右侧频道抽屉 ==================== */

    private void buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        // 设计稿 rgba(18,22,29,0.95)：比顶条再亮一档的深蓝黑，抽屉才不会和视频糊在一起
        bg.setColor(0xF212161D);
        // 只圆左侧两个角：抽屉是贴右边缘的
        bg.setCornerRadii(new float[] {
                Ui.px(20), Ui.px(20), 0f, 0f, 0f, 0f, Ui.px(20), Ui.px(20)
        });
        bg.setStroke(Math.max(1, Ui.px(1)), 0x1FFFFFFF);
        drawer.setBackground(bg);
        drawer.setPadding(Ui.px(32), Ui.px(32), Ui.px(32), Ui.px(20));
        drawer.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // 抽屉标题用 26px 而不是 Chrome.title 的 38px：抽屉只有 560 宽，38 会顶掉「共 N 台」
        head.addView(Ui.bold(this, "频道列表", 26, Ui.FG));

        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        drawerCount = Ui.text(this, "", 13, Ui.FG_DIM);
        head.addView(drawerCount);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.px(6);
        drawer.addView(head, hlp);

        TextView hint = Ui.text(this, "↑↓ 换台　·　← → 切分组　·　OK 直接播放", 13, 0xB39AA6B5);
        hint.setPadding(0, 0, 0, Ui.px(16));
        drawer.addView(hint);

        View line = Chrome.hairline(this, 0);
        drawer.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(1))));

        // v2.15：分组条改成「按宽度自动换行」。
        // 原来套在 HorizontalScrollView 里，可抽屉里 ←→ 被用来切分组，
        // 用户没有任何办法把这条横向滚起来 —— 5 个分组时最后一个「付费专业 64」
        // 就永远卡在右边缘外面，看着像只有 4 个分组。改成换行后 5 个分组两行排完。
        groupStrip = new LinearLayout(this);
        groupStrip.setOrientation(LinearLayout.VERTICAL);
        groupStrip.setClipChildren(false);
        // 设计稿分组条上下各 16px
        groupStrip.setPadding(0, Ui.px(16), 0, Ui.px(8));
        drawer.addView(groupStrip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        drawerScroll = new ScrollView(this);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerScroll.setClipChildren(false);
        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        drawerList.setClipChildren(false);
        drawerScroll.addView(drawerList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 列表上下各盖一道 32px 渐隐：滚到边界时行不会「齐刷刷被切一半」
        FrameLayout listWrap = new FrameLayout(this);
        listWrap.setClipChildren(true);
        listWrap.addView(drawerScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View topFade = new View(this);
        topFade.setBackground(Ui.fade(true,
                new int[] { 0xF212161D, 0x0012161D },
                new float[] { 0f, 1f }));
        topFade.setFocusable(false);
        listWrap.addView(topFade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.px(32), Gravity.TOP));

        View botFade = new View(this);
        botFade.setBackground(Ui.fade(true,
                new int[] { 0x0012161D, 0xF212161D },
                new float[] { 0f, 1f }));
        botFade.setFocusable(false);
        listWrap.addView(botFade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.px(32), Gravity.BOTTOM));

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        drawer.addView(listWrap, dlp);
    }

    private void toggleDrawer() {
        drawerVisible = !drawerVisible;
        ui.removeCallbacks(hideDrawerAnim);
        if (drawerVisible) {
            if (panelVisible) {
                togglePanel();
            }
            browseGroupIdx = groupIdx;
            renderDrawer();
            // 先从右边滑进来：10 尺视距下，抽屉「啪」地出现会让人找不到焦点落在哪。
            drawer.setVisibility(View.VISIBLE);
            drawer.setTranslationX(Ui.px(DRAWER_W));
            drawer.animate().translationX(0f).setDuration(180).start();
            focusRow(chIdx);
        } else {
            // 滑出去再 GONE；动画期间焦点先还给画面，不然方向键还在列表里
            drawer.animate().translationX(Ui.px(DRAWER_W)).setDuration(150)
                    .withEndAction(hideDrawerAnim).start();
            vv.requestFocus();
        }
    }

    /** 分组条的一行：横向排 chip，行间留 8px。 */
    private LinearLayout newGroupRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.px(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void renderDrawer() {
        if (groupStrip == null) {
            return;
        }
        groupStrip.removeAllViews();
        // 抽屉内可用宽度（drawer 两侧各 32 内边距），超出就换一行，不把分组挤到屏幕外
        int avail = Ui.px(DRAWER_W) - Ui.px(32) * 2 - Ui.px(4);
        LinearLayout row = newGroupRow();
        groupStrip.addView(row);
        int used = 0;
        for (int i = 0; i < groups.size(); i++) {
            final int gi = i;
            Live.Group g = groups.get(i);
            boolean on = i == browseGroupIdx;
            TextView chip = on
                    ? Ui.bold(this, g.name + " " + g.chs.size(), 14, Ui.BG)
                    : Ui.text(this, g.name + " " + g.chs.size(), 14, Ui.FG_DIM);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setPadding(Ui.px(14), Ui.px(6), Ui.px(14), Ui.px(6));
            chip.setBackground(on ? Ui.glowBg(Ui.ACCENT, 999, Ui.ACCENT_HI)
                    : Ui.focusBgGlow(0x14FFFFFF, 999, Ui.ACCENT));
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    browseGroupIdx = gi;
                    renderDrawer();
                    focusRow(0);
                }
            });
            // wrap_content 的宽度只有 measure 之后才知道，这里先量一次再决定放哪一行
            chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = chip.getMeasuredWidth() + Ui.px(8);
            if (used > 0 && used + w > avail) {
                row = newGroupRow();
                groupStrip.addView(row);
                used = 0;
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.px(8);
            row.addView(chip, clp);
            used += w;
        }

        Live.Group g = groups.get(browseGroupIdx);
        drawerCount.setText("共 " + g.chs.size() + " 台");

        drawerList.removeAllViews();
        // v2.20：把「这一行属于哪个分组」一起传进去。旧代码在点击时用 groupIdx（正在播的组）
        // 覆盖 browseGroupIdx，于是「抽屉里切到央视选集」会按央视列表里的行号去取江西组里的台
        // ——老板实测：选 CCTV-1 播出来是江西少儿。分组下标必须取渲染时的快照。
        for (int i = 0; i < g.chs.size(); i++) {
            drawerList.addView(drawerRow(g.chs.get(i), i, browseGroupIdx));
        }
    }

    /** 抽屉里的一行频道：指示条 + 台标 + 台名/别名 + 源数。 */
    private View drawerRow(final Live.Ch c, final int idx, final int gi) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.px(14), 0, Ui.px(14), 0);
        row.setFocusable(true);
        row.setClickable(true);

        final View bar = new View(this);
        bar.setBackground(Ui.round(0x00000000, 2, 0, 0));
        row.addView(bar, new LinearLayout.LayoutParams(Ui.px(4), Ui.px(30)));

        final FrameLayout logoBox = new FrameLayout(this);
        // 非焦点是「半透明白底 + 白描边」，焦点变白底 + 琥珀描边（设计稿）
        logoBox.setBackground(Ui.round(0x1AFFFFFF, 6, 1, 0x1AFFFFFF));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int lw = Ui.px(44);
        final int lh = Ui.px(28);
        logoBox.addView(logo, new FrameLayout.LayoutParams(lw, lh, Gravity.CENTER));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(lw, lh);
        llp.leftMargin = Ui.px(12);
        row.addView(logoBox, llp);
        Ui.loadPoster(logo, c.logo, Math.max(8, lw - Ui.px(6)), Math.max(8, lh - Ui.px(6)),
                c.name, true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.px(12);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView name = Ui.bold(this, c.name, 18, 0xE6FFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(name);

        // 「正在播」只在当前这台、且聚焦时出现——不是的话没有信息量
        final TextView playing = Ui.bold(this, "正在播", 11, Ui.BG);
        playing.setGravity(Gravity.CENTER);
        playing.setPadding(Ui.px(6), Ui.px(1), Ui.px(6), Ui.px(1));
        playing.setBackground(Ui.round(Ui.ACCENT, 3, 0, 0));
        playing.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = Ui.px(8);
        nameRow.addView(playing, plp);

        final TextView tag4k = Ui.bold(this, qualityOf(c.name), 10, 0xFF66E39A);
        tag4k.setGravity(Gravity.CENTER);
        tag4k.setPadding(Ui.px(6), Ui.px(1), Ui.px(6), Ui.px(1));
        tag4k.setBackground(Ui.round(0x3322C55E, 3, 1, 0x4D22C55E));
        tag4k.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.px(6);
        nameRow.addView(tag4k, tlp);

        col.addView(nameRow);

        String alias = Live.aliasOf(c.name);
        final String subNormal = (alias.length() > 0 ? alias + " · " : "") + qualityOf(c.name);
        // 焦点行改显示「别名 · 第 n/m 路源」，跟设计稿一致
        final String subFocus = (alias.length() > 0 ? alias + " · " : "")
                + "第 " + (idx == chIdx ? urlIdx + 1 : 1) + "/" + Math.max(1, c.urls.size()) + " 路源";
        final TextView sub = Ui.text(this, subNormal, 13, Ui.FG_DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.px(2);
        col.addView(sub, slp);

        row.addView(col, clp);

        final TextView count = Ui.text(this, c.urls.size() + " 源", 12, Ui.FG_DIM);
        count.setPadding(Ui.px(8), Ui.px(2), Ui.px(8), Ui.px(2));
        count.setBackground(Ui.round(0x0DFFFFFF, 4, 1, 0x1AFFFFFF));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = Ui.px(10);
        row.addView(count, nlp);

        row.setBackground(rowBg(false));
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                row.setBackground(rowBg(has));
                bar.setBackground(Ui.round(has ? Ui.ACCENT : 0x00000000, 2, 0, 0));
                // 设计稿焦点态：指示条变成 4×32 的发光琥珀块
                LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                blp.height = Ui.px(has ? 32 : 30);
                bar.setLayoutParams(blp);

                logoBox.setBackground(has
                        ? Ui.round(0xFFFFFFFF, 6, 2, Ui.ACCENT)
                        : Ui.round(0x1AFFFFFF, 6, 1, 0x1AFFFFFF));

                name.setTextColor(has ? 0xFFFFFFFF : 0xE6FFFFFF);
                name.setTextSize(Ui.TYPE * (has ? 20 : 18));
                playing.setVisibility(has && idx == chIdx ? View.VISIBLE : View.GONE);
                tag4k.setVisibility(has ? View.VISIBLE : View.GONE);
                sub.setText(has ? subFocus : subNormal);
                sub.setTextColor(has ? 0xE6FFB300 : Ui.FG_DIM);
                count.setTextColor(has ? Ui.ACCENT : Ui.FG_DIM);
                count.setBackground(has
                        ? Ui.round(0x33FFB300, 4, 1, 0x66FFB300)
                        : Ui.round(0x0DFFFFFF, 4, 1, 0x1AFFFFFF));
                if (has) {
                    scrollRowIntoView(v);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // v2.20：用渲染时记下的分组 gi，不要用 groupIdx（那是「正在播」的组）
                browseGroupIdx = gi;
                selectChannel(idx);
            }
        });

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(64));
        rlp.bottomMargin = Ui.px(8);
        row.setLayoutParams(rlp);
        return row;
    }

    private GradientDrawable rowBg(boolean on) {
        GradientDrawable g = new GradientDrawable();
        // 设计稿：普通行 rgba(255,255,255,0.03)，焦点行 rgba(255,179,0,0.16) + 2px 琥珀描边
        g.setColor(on ? 0x29FFB300 : 0x08FFFFFF);
        g.setCornerRadius(Ui.px(10));
        g.setStroke(Math.max(1, Ui.px(on ? 2 : 1)), on ? Ui.ACCENT : 0x00000000);
        return g;
    }

    private void scrollRowIntoView(View row) {
        if (drawerScroll == null) {
            return;
        }
        final int top = row.getTop();
        final int h = row.getHeight();
        final int vh = drawerScroll.getHeight();
        drawerScroll.post(new Runnable() {
            public void run() {
                int y = drawerScroll.getScrollY();
                if (top < y) {
                    drawerScroll.smoothScrollTo(0, Math.max(0, top - Ui.px(8)));
                } else if (top + h > y + vh - Ui.px(8)) {
                    drawerScroll.smoothScrollTo(0, top + h - vh + Ui.px(8));
                }
            }
        });
    }

    private void focusRow(int idx) {
        if (drawerList == null || drawerList.getChildCount() == 0) {
            return;
        }
        int i = idx;
        if (i < 0) {
            i = 0;
        }
        if (i >= drawerList.getChildCount()) {
            i = drawerList.getChildCount() - 1;
        }
        final View v = drawerList.getChildAt(i);
        v.post(new Runnable() {
            public void run() {
                v.requestFocus();
            }
        });
    }

    /** 在抽屉里点了某个台：切真正在播的台，然后收抽屉。 */
    private void selectChannel(int idx) {
        if (groups.isEmpty()) {
            return;
        }
        manual();
        groupIdx = browseGroupIdx;
        chIdx = idx;
        if (!pickChannel()) {
            showDead("这个台暂时没有可用的线路，换个台试试");
            return;
        }
        Store.saveLive(groupIdx, chIdx, ch == null ? "" : ch.name);
        if (drawerVisible) {
            toggleDrawer();
        }
        playPreferred();
    }

    /* ==================== 播放 ==================== */

    /**
     * 按台名把上次看的台找回来。找不到就什么都不做，让后面的 firstPlayable() 兜底。
     *
     * 之所以不靠下标：live.json 是每日巡检产出的，分组内顺序会随「当天哪条源快」
     * 重排，昨天第 3 个是湖南卫视、今天第 3 个可能是广东卫视。只存下标的话
     * 「开机直接播上次那个台」就变成了随机换台。
     */
    private void restoreByName(String name) {
        if (name == null || name.length() == 0) {
            return;
        }
        for (int g = 0; g < groups.size(); g++) {
            Live.Group gr = groups.get(g);
            if (gr == null || gr.chs == null) {
                continue;
            }
            for (int i = 0; i < gr.chs.size(); i++) {
                Live.Ch c = gr.chs.get(i);
                if (c != null && name.equals(c.name)) {
                    groupIdx = g;
                    chIdx = i;
                    return;
                }
            }
        }
        Trace.add("live", "上次看的台「" + name + "」不在新源表里，回落到下标");
    }

    private boolean pickChannel() {
        if (groups.isEmpty()) {
            return false;
        }
        if (groupIdx < 0 || groupIdx >= groups.size()) {
            groupIdx = 0;
        }
        group = groups.get(groupIdx);
        if (group.chs.isEmpty()) {
            return false;
        }
        if (chIdx < 0 || chIdx >= group.chs.size()) {
            chIdx = 0;
        }
        ch = group.chs.get(chIdx);
        urls = ch.urls;
        urlIdx = 0;
        autoSkips = 0;
        dead = false;
        return !urls.isEmpty();
    }

    /**
     * 从当前组往后找第一个「有 URL」的台；当前组没有就换下一个组。
     *
     * 为什么需要：Store 里记的是上次看的台，而 live.json 每天凌晨会被巡检重写，
     * 昨晚能播的台今天可能整台被筛掉。这时如果死守记忆里的下标，用户点进直播
     * 就是一句「没有可用的直播源」，明明别的台好好的。
     *
     * @return 找到并落位成功返回 true
     */
    private boolean firstPlayable() {
        for (int g = 0; g < groups.size(); g++) {
            int gi = (groupIdx + g) % groups.size();
            Live.Group gr = groups.get(gi);
            if (gr == null || gr.chs.isEmpty()) {
                continue;
            }
            for (int i = 0; i < gr.chs.size(); i++) {
                Live.Ch c = gr.chs.get(i);
                if (c != null && c.urls != null && !c.urls.isEmpty()) {
                    groupIdx = gi;
                    chIdx = i;
                    browseGroupIdx = gi;
                    return pickChannel();
                }
            }
        }
        return false;
    }

    /** 用户主动操作：把「自动跳过」的计数清零，不然几次换台之后就误判为全挂。 */
    private void manual() {
        autoSkips = 0;
        dead = false;
    }

    /**
     * v2.10：从这个台「上次真的播通过的那一路」起播。
     *
     * 以前所有换台入口都写死 play(0)，等于每次都从巡检排序第一的源开始。
     * 但排序第一的源未必在这台电视/这条宽带上通 —— 用户看到的就是「每个台都在转圈」。
     * 记住成功过的下标，第二次进同一个台直接命中。
     */
    private void playPreferred() {
        int idx = 0;
        if (ch != null) {
            idx = Store.liveUrlIdx(ch.name);
        }
        if (idx < 0 || idx >= urls.size()) {
            idx = 0;
        }
        play(idx);
    }

    /**
     * @param idx 走第几个源。越界就夹回 0。
     */
    private void play(int idx) {
        if (urls.isEmpty()) {
            showDead("这个台没有可用的线路");
            return;
        }
        if (idx < 0 || idx >= urls.size()) {
            idx = 0;
        }
        // 已经真的起播了，防抖队列里那一次就没必要再跑
        cancelPendingSwitch();
        urlIdx = idx;
        dead = false;
        paused = false;
        Trace.add("live", "起播请求 " + (ch == null ? "?" : ch.name)
                + " 线路" + (idx + 1) + "/" + urls.size());
        bigMsg.setVisibility(View.GONE);
        busy.setVisibility(View.VISIBLE);
        // v2.19：以前只有一句「正在缓冲…」，用户不知道在等哪个台、第几路线。
        // 换成「台名 + 线路 i/n」，等的时候心里有数，也方便判断是不是这条源在赌运气。
        busyText.setText("正在接入 " + (ch == null ? "直播" : ch.name)
                + "　·　线路 " + (idx + 1) + "/" + urls.size());
        busyText.setVisibility(View.VISIBLE);
        updateInfo();

        playAt = System.currentTimeMillis();
        bufAt = System.currentTimeMillis();
        lastPos = -1;
        posAt = 0;
        preparedAt = 0;
        startMs = 0;
        videoW = 0;
        stalls = 0;
        lastStallAt = 0;

        final String raw = urls.get(idx);
        final String local = Proxy.wrapPlaylist(raw);

        vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                busy.setVisibility(View.GONE);
                busyText.setVisibility(View.GONE);
                bufAt = 0;
                paused = false;
                mp.setScreenOnWhilePlaying(true);
                // 关键一步：把播放器给的**真实**视频尺寸喂给视图。
                // 不喂就只能反射读上一帧的 mVideoWidth，直播起播时它经常还是 0，
                // 于是第一次测量按容器铺满，之后没人再量 → 一直铺满 = 放大截断。
                try {
                    vv.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                } catch (Throwable t) {
                }
                preparedAt = System.currentTimeMillis();
                videoW = safeWidth(mp);
                vv.setMode(scaleMode, MAX_AR_DEV);
                vv.start();
                vv.post(new Runnable() {
                    public void run() {
                        vv.requestLayout();
                    }
                });
                restInfo();
                posAt = System.currentTimeMillis();
                lastPos = -1;
                // v2.10：这一路真的起来了，记下台名 → 线路下标，下次进这个台直接从这里起播。
                // 只在 onPrepared 记，不在 play() 里记 —— 记的是「能播」，不是「试过」。
                if (ch != null) {
                    Store.saveLiveUrlIdx(ch.name, urlIdx);
                }
                Trace.add("live", "直播起播 " + ch.name + " 线路" + (urlIdx + 1)
                        + "/" + urls.size() + " 耗时" + (preparedAt - playAt) + "ms "
                        + describeVideo(mp));
                // v2.19：把起播耗时也写到顶栏。电视上没有 adb 看日志时，
                // 老板自己「这台怎么比刚才那台慢」只能靠感觉 —— 给个数字就有据可依。
                startMs = preparedAt - playAt;
                updateInfo();
                // v2.20：解码器刚重建完，正是固件把扬声器推满的时机 —— 立刻对齐一次
                resyncVolume("起播");
            }
        });
        vv.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    busy.setVisibility(View.VISIBLE);
                    busyText.setVisibility(View.VISIBLE);
                    bufAt = System.currentTimeMillis();
                    // 间歇卡顿累计：8 秒内反复 start/end 只算一次
                    if (bufAt - lastStallAt > STALL_DEBOUNCE_MS) {
                        lastStallAt = bufAt;
                        stalls++;
                    }
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    busy.setVisibility(View.GONE);
                    busyText.setVisibility(View.GONE);
                    bufAt = 0;
                } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
                    // 部分 ROM 只在画面尺寸变化后补发这个，顺手再喂一次尺寸
                    try {
                        vv.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                    } catch (Throwable t) {
                    }
                    vv.requestLayout();
                } else if (what == 3 /* MEDIA_INFO_VIDEO_RENDERING_START */) {
                    // 第一帧真正上屏：尺寸这时候才是准的，再喂一次 + 重新量
                    try {
                        vv.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                    } catch (Throwable t) {
                    }
                    // 出画了就作废黑屏计时（videoW 刻意不改回 0）
                    videoW = safeWidth(mp);
                    preparedAt = 0;
                    busy.setVisibility(View.GONE);
                    busyText.setVisibility(View.GONE);
                    bufAt = 0;
                    vv.requestLayout();
                    // v2.20：第一帧上屏时音轨才真正开始出音，这时再对齐一次音量
                    resyncVolume("出画");
                }
                return false;
            }
        });
        vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                busy.setVisibility(View.GONE);
                busyText.setVisibility(View.GONE);
                if (autoSkips + 1 < urls.size() * 2 + 1) {
                    Trace.add("live", "线路" + (urlIdx + 1) + " 挂了（" + what + "/" + extra
                            + "），自动切下一个");
                    autoNext("线路 " + (urlIdx + 1) + " 打不开，换下一路");
                } else {
                    showDead("该频道暂时看不了，试试别的台（菜单键可手动换源）");
                }
                return true;
            }
        });
        // 直播流一般不回调 onCompletion，但个别源会「播完一小段就结束」，
        // 这种情况下当作一次失败，交给上面的自动换源逻辑处理。
        vv.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                if (dead || paused) {
                    return;
                }
                autoNext("这一路提前结束了，换下一路");
            }
        });

        try {
            vv.setVideoURI(Uri.parse(local));
            vv.requestFocus();
        } catch (Throwable t) {
            Trace.err("live", "直播起播失败 " + ch.name, t);
            busy.setVisibility(View.GONE);
            busyText.setVisibility(View.GONE);
            showDead("起播失败：" + t.getClass().getSimpleName());
        }
    }

    /** 「1920x1080 25fps」这种描述，写日志用，出问题能一眼看出是不是分辨率不对。 */
    private String describeVideo(MediaPlayer mp) {
        try {
            return mp.getVideoWidth() + "x" + mp.getVideoHeight();
        } catch (Throwable t) {
            return "尺寸未知";
        }
    }

    /**
     * 自动切下一路。带计数上限，避免「每一路都在 3 秒内报错」时无脑转圈：
     * 跳过次数到 urls.size()*2 就停手，让用户自己决定。
     */
    private void autoNext(String why) {
        if (urls.isEmpty()) {
            return;
        }
        autoSkips++;
        Trace.add("live", "自动换源 #" + autoSkips + " " + why);
        if (autoSkips > urls.size() * 2) {
            showDead("该频道暂时看不了，试试别的台");
            return;
        }
        showToast(why);
        play((urlIdx + 1) % urls.size());
    }

    /**
     * 看门狗：巡检脚本只能证明「这一秒连得上」，证明不了「能一直播」。
     * 这五种情况一律换源：起播超时、缓冲卡住、画面位置不再前进、解码器出不了画、
     * 以及间歇性反复卡顿（每 10 秒卡 3 秒、卡完自己恢复的那种）。
     */
    private final Runnable watchdog = new Runnable() {
        public void run() {
            checkAlive();
            ui.postDelayed(this, 2000);
        }
    };

    private void checkAlive() {
        // 音量护栏跟播放状态无关：抽屉/面板开着、暂停着，固件一样会出问题，所以放最前面
        guardVolume();
        if (dead || paused || urls.isEmpty() || panelVisible || drawerVisible) {
            return;
        }
        long now = System.currentTimeMillis();

        // 1. 起播超时：点了之后 15 秒还没 onPrepared
        boolean prepared = false;
        try {
            prepared = vv.isPlaying() || vv.getCurrentPosition() > 0;
        } catch (Throwable t) {
        }
        if (!prepared && playAt > 0 && now - playAt > START_TIMEOUT_MS) {
            Trace.add("live", "线路" + (urlIdx + 1) + " 起播超时");
            autoNext("这一路起播太慢，换下一路");
            return;
        }

        // 2. 缓冲卡住：BUFFERING_START 之后 9 秒还没 END
        if (bufAt > 0 && now - bufAt > BUFFER_TIMEOUT_MS) {
            Trace.add("live", "线路" + (urlIdx + 1) + " 缓冲卡住");
            autoNext("这一路太卡，换下一路");
            return;
        }

        // 3. 画面不前进：正在播但位置 12 秒没变过（有的源会「假播放」）
        try {
            if (vv.isPlaying()) {
                int pos = vv.getCurrentPosition();
                if (pos != lastPos) {
                    lastPos = pos;
                    posAt = now;
                } else if (posAt > 0 && now - posAt > STALL_TIMEOUT_MS) {
                    Trace.add("live", "线路" + (urlIdx + 1) + " 画面停住 @" + pos);
                    autoNext("画面停住了，换下一路");
                    return;
                }
            }
        } catch (Throwable t) {
        }

        // 4. 黑屏：prepared 之后 6 秒还一个像素都没解出来。
        //    这一类源骗过上面三条（position 在走、isPlaying 为 true），但屏幕纯黑。
        if (preparedAt > 0 && videoW <= 0 && now - preparedAt > BLANK_TIMEOUT_MS) {
            Trace.add("live", "线路" + (urlIdx + 1) + " 黑屏无画面");
            autoNext("这一路只有声音没有画面，换下一路");
            return;
        }

        // 5. 间歇卡顿：每 10 秒卡 3 秒、卡完自己恢复的源。
        //    上面四条都只抓「一次性卡死」，这种「细水长流地卡」全部漏网，
        //    但它才是「10 个台 7 个都在转圈」的主要来源。
        if (stalls >= MAX_STALLS) {
            Trace.add("live", "线路" + (urlIdx + 1) + " 反复卡顿 " + stalls + " 次");
            autoNext("这一路老是在转圈，换下一路");
            return;
        }

        // 6. 周期性音量回写：每 10 秒让固件按当前 index 重新对一次扬声器。
        //    间隔不能再长 —— 老板反馈里「声音自己飙到最大」是随机的，响十几秒就够烦人了；
        //    一次回写就是一次 setStreamVolume(同值)，不出 OSD、不改音量，代价可以忽略。
        if (now - lastResyncAt > 10000) {
            lastResyncAt = now;
            resyncVolume("定时对齐");
        }
    }

    /** 拿视频宽度，取不到就当 0（意味着还没出画）。 */
    private int safeWidth(MediaPlayer mp) {
        try {
            return mp == null ? 0 : mp.getVideoWidth();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * v2.19：换台防抖窗口。
     *
     * 遥控器方向键是「按住连发」（每秒 8~10 次）。旧逻辑每收到一次就 play() 一轮：
     * 重建 MediaPlayer、让 Proxy 重新拉一次 m3u8、再起一遍解码器。
     * 按住两秒等于连开十几路流，最后停住的那台反而排在一堆废弃连接后面起播，
     * 画面上就是连续黑闪 + 越来越慢的「正在缓冲…」——这是「换台很卡」的主要来源。
     *
     * 现在只把「目标台」和顶栏文案立刻推上去（手感上已经换台了），
     * 停手 260ms 后才真正起播最后一台。260ms 在遥控器连发间隔之内、
     * 又是人眼感觉不到等待的量级。
     */
    private static final long SWITCH_DEBOUNCE_MS = 260;
    private boolean switchPending = false;

    private final Runnable commitSwitch = new Runnable() {
        public void run() {
            if (!switchPending) {
                return;
            }
            switchPending = false;
            if (ch == null) {
                return;
            }
            playPreferred();
        }
    };

    private void queuePlay() {
        switchPending = true;
        // 先把这台的顶栏信息（台号/台名/线路）显示出来，
        // 别让画面挂着上一个台的台标、顶栏却已经换了名字
        int idx = ch == null ? 0 : Store.liveUrlIdx(ch.name);
        if (idx < 0 || idx >= urls.size()) {
            idx = 0;
        }
        urlIdx = idx;
        updateInfo();
        ui.removeCallbacks(commitSwitch);
        ui.postDelayed(commitSwitch, SWITCH_DEBOUNCE_MS);
    }

    /** 用户又按了别的键（换源 / 进列表 / 退出）：把还没落地的换台取消掉。 */
    private void cancelPendingSwitch() {
        switchPending = false;
        ui.removeCallbacks(commitSwitch);
    }

    /** 上一台 / 下一台：只在当前分组里循环。 */
    private void switchChannel(int delta) {
        if (group == null || group.chs.isEmpty()) {
            return;
        }
        manual();
        int n = group.chs.size();
        int step = delta > 0 ? 1 : -1;
        // 跳过没有 URL 的台：源表每天会被巡检重写，偶尔会留下空壳台，
        // 按一下上下键就撞上去、弹一句「没有可用的线路」，体验很差。
        for (int k = 0; k < n; k++) {
            chIdx = ((chIdx + step) % n + n) % n;
            if (pickChannel()) {
                Store.saveLive(groupIdx, chIdx, ch == null ? "" : ch.name);
                // v2.19：不在这里直接起播，交给防抖队列（见 commitSwitch 的注释）。
                queuePlay();
                return;
            }
        }
        showDead("这个分组里没有能播的台了");
    }

    /** 切分组：抽屉没开就直接换台播，开着就只换列表（别把正在看的台弄没了）。 */
    private void switchGroup(int delta) {
        if (groups.isEmpty()) {
            return;
        }
        int n = groups.size();
        if (drawerVisible) {
            browseGroupIdx = ((browseGroupIdx + delta) % n + n) % n;
            renderDrawer();
            focusRow(0);
            return;
        }
        manual();
        groupIdx = ((groupIdx + delta) % n + n) % n;
        chIdx = 0;
        // 整组都被筛空的情况在新版源表里很常见（巡检把无效台整台删掉），
        // 直接跳到下一个有台的分组，而不是弹一句「这个分组没有可用的台」就停住。
        if (!pickChannel() && !firstPlayable()) {
            showToast("这个分组没有可用的台");
            return;
        }
        Store.saveLive(groupIdx, chIdx, ch == null ? "" : ch.name);
        playPreferred();
    }

    private void showDead(String msg) {
        dead = true;
        busy.setVisibility(View.GONE);
        busyText.setVisibility(View.GONE);
        bigMsg.setText(msg);
        bigMsg.setVisibility(View.VISIBLE);
        if (ch != null) {
            topName.setText(ch.name);
            topSub.setText(urls.isEmpty() ? "无线路" : "全部线路都不可用");
        }
        setTopVisible();
    }

    private void updateInfo() {
        if (ch == null) {
            return;
        }
        topName.setText(ch.name);
        String alias = Live.aliasOf(ch.name);
        StringBuilder sb = new StringBuilder();
        sb.append("第 ").append(pad2(chIdx + 1)).append(" 台");
        if (alias.length() > 0) {
            sb.append("　·　").append(alias);
        }
        sb.append("　·　").append(qualityOf(ch.name));
        sb.append("　·　第 ").append(urlIdx + 1).append("/").append(urls.size()).append(" 路源");
        if (startMs > 0) {
            sb.append("　·　接入 ").append(oneDecimal(startMs / 1000.0)).append("s");
        }
        topSub.setText(sb.toString());
        topBadge.setText(qualityOf(ch.name).equals("HD") ? "FHD" : qualityOf(ch.name));
        setTopVisible();
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String oneDecimal(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private void setTopVisible() {
        ui.removeCallbacks(hideInfo);
        topBar.setAlpha(1f);
        topBar.setVisibility(View.VISIBLE);
    }

    /** 起播成功后：信息条先显示，5 秒后淡出，别一直压在画面上。 */
    private void restInfo() {
        ui.removeCallbacks(hideInfo);
        topBar.setAlpha(1f);
        topBar.setVisibility(View.VISIBLE);
        ui.postDelayed(hideInfo, 5000);
    }

    private void showToast(String msg) {
        toast.setText(msg);
        toast.setVisibility(View.VISIBLE);
        toast.setAlpha(1f);
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 1600);
    }

    private final Runnable hideToast = new Runnable() {
        public void run() {
            toast.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                public void run() {
                    toast.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    /* ==================== 画面比例 ==================== */

    /**
     * 循环 自动 / 适应 / 铺满。
     *
     * v2.8 起顺序改成以「自动」开头：新算法下自动已经是大多数人想要的那档
     * （16:9 频道满屏、4:3 频道留黑），用户按红键多半是想手动覆盖它。
     */
    private void cycleScale() {
        scaleMode = (scaleMode + 1) % 3;
        vv.setMode(scaleMode, MAX_AR_DEV);
        String name;
        if (scaleMode == PlayerActivity.MODE_FIT) {
            name = "画面：适应（完整显示，两侧留黑）";
        } else if (scaleMode == PlayerActivity.MODE_FILL) {
            name = "画面：铺满（超出部分裁掉）";
        } else {
            name = "画面：自动（按频道比例判断）";
        }
        showToast(name);
        Trace.add("live", name);
    }

    private String qualityOf(String name) {
        if (name.contains("4K") || name.contains("4k") || name.contains("超清")) {
            return "4K";
        }
        if (name.contains("8K") || name.contains("8k")) {
            return "8K";
        }
        return "HD";
    }

    /* ==================== 按键 ==================== */

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (panelVisible) {
                togglePanel();
                return true;
            }
            if (drawerVisible) {
                toggleDrawer();
                return true;
            }
            finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (drawerVisible) {
                toggleDrawer();
            }
            togglePanel();
            return true;
        }
        if (code == KEY_RED) {
            cycleScale();
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP) {
            adjustVolume(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            adjustVolume(-1);
            return true;
        }
        // 抽屉开着：↑↓ 交给列表自己走焦点，←→ 用来切分组
        if (drawerVisible) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                switchGroup(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switchGroup(1);
                return true;
            }
            return super.onKeyDown(code, e);
        }
        if (panelVisible) {
            // 面板开着的时候方向键交给面板自己走焦点，别在这一层截
            return super.onKeyDown(code, e);
        }
        // 遥控器「频道 +/-」按电视的常规习惯是换台，不是换源 —— 换源用菜单键。
        if (code == KEY_CH_UP) {
            switchChannel(-1);
            return true;
        }
        if (code == KEY_CH_DOWN) {
            switchChannel(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            switchChannel(-1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            switchChannel(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            manual();
            if (urls.size() > 1) {
                int n = urls.size();
                play(((urlIdx - 1) % n + n) % n);
            } else {
                showToast("这个台只有一路源");
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            manual();
            if (urls.size() > 1) {
                play((urlIdx + 1) % urls.size());
            } else {
                showToast("这个台只有一路源");
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            // OK：呼出频道列表（电视直播里最常用的动作）
            manual();
            toggleDrawer();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (vv.isPlaying()) {
                vv.pause();
                paused = true;
            } else {
                vv.start();
                paused = false;
                posAt = System.currentTimeMillis();
                lastPos = -1;
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /** 音量 HUD 里的小喇叭。系统没有现成 drawable，自己画三角 + 两道弧。 */
    private static final class SpeakerIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SpeakerIcon(Context c, int color) {
            super(c);
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float u = w / 5f;
            Path path = new Path();
            path.moveTo(u * 0.4f, h * 0.36f);
            path.lineTo(u * 1.4f, h * 0.36f);
            path.lineTo(u * 2.5f, h * 0.16f);
            path.lineTo(u * 2.5f, h * 0.84f);
            path.lineTo(u * 1.4f, h * 0.64f);
            path.lineTo(u * 0.4f, h * 0.64f);
            path.close();
            cv.drawPath(path, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, w * 0.07f));
            cv.drawArc(new RectF(u * 2.2f, h * 0.30f, u * 3.6f, h * 0.70f), -60, 120, false, p);
            cv.drawArc(new RectF(u * 2.2f, h * 0.12f, u * 4.7f, h * 0.88f), -55, 110, false, p);
        }
    }
}

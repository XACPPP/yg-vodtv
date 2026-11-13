package com.yg.vodtv;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播播放页。
 *
 * 为什么不复用 PlayerActivity：那边一整套都是为点播写的——进度条、断点续播、
 * 播完自动下一集、选集浮层。直播没有「总时长」，`onCompletion` 一触发就会去播下一集，
 * 反而把正常播放打断。所以这里单独写一个薄的：只要 VideoView + 换源 + 换台 + 看门狗。
 *
 * ==================== v2.7 修「画面放大截断」 ====================
 *
 * 三个原因叠在一起，缺一个都还会截：
 *
 * 1. **默认模式是 AUTO**。v2.6 从头到尾没调过 setMode()，FitVideoView 停在
 *    `MODE_AUTO + maxCrop=0.34`：只要「铺满时被裁掉的部分 ≤ 34%」就铺满。
 *    16:9 频道本来裁 0%，看着没事；可源表里混着大量 4:3 / 544x576 的老 SD 频道，
 *    4:3 铺到 16:9 上要裁 25%，正好在 34% 容差里 → 一律被拉成铺满 → 台标和字幕被切。
 *    现在默认 {@code MODE_FIT}：完整显示、两侧留黑，绝不裁。
 *
 * 2. **视频尺寸拿不到**。原来只能反射读 VideoView 的私有 mVideoWidth，
 *    那是上一帧的值；直播起播快、尺寸晚到，onPrepared 时经常还是 0，
 *    第一次测量就退回「按容器铺满」，之后没人再触发 requestLayout。
 *    现在 onPrepared 里直接把 {@code mp.getVideoWidth()/getVideoHeight()}
 *    喂给 {@code FitVideoView.setVideoSize()}，尺寸一定是最新的。
 *
 * 3. **没有比例开关**。4:3 频道想看大一点只能忍着。现在红键循环
 *    适应 / 铺满 / 自动，跟影视播放页同一套语义。
 *
 * ==================== v2.7 修「源特别卡」 ====================
 *
 * 巡检脚本只能证明「这一秒能连上」，不能证明「能一直播」。所以这一页自己判活：
 * 起播超时 15s、缓冲卡住 9s、画面位置 12s 不前进，任一命中就自动切下一路。
 * 手动操作（换台、按 OK 重连）会重置这个计数，避免它把用户的意图覆盖掉。
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
    private static final long START_TIMEOUT_MS = 15000;
    private static final long BUFFER_TIMEOUT_MS = 9000;
    private static final long STALL_TIMEOUT_MS = 12000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private PlayerActivity.FitVideoView vv;
    private ProgressBar busy;
    private TextView busyText;
    private View topBar;
    private TextView topName;
    private TextView topSub;
    private TextView topBadge;
    private TextView bottomHint;
    private TextView bigMsg;
    private TextView toast;
    private LinearLayout bottomWrap;
    private LinearLayout panel;
    private LinearLayout panelSources;

    private List<Live.Group> groups = new ArrayList<Live.Group>();
    private Live.Group group;
    private Live.Ch ch;
    private List<String> urls = new ArrayList<String>();

    private int groupIdx = 0;
    private int chIdx = 0;
    private int urlIdx = 0;

    /** 这一轮已经自动跳过的次数。到 urls.size()*2 就认为这个台整体不可用。 */
    private int autoSkips = 0;
    private boolean panelVisible = false;
    private boolean dead = false;
    private boolean paused = false;

    /* 看门狗状态 */
    private long playAt = 0;
    private long bufAt = 0;
    private int lastPos = -1;
    private long posAt = 0;

    /**
     * 画面比例。为什么默认适应：直播频道混着 4:3 和 16:9，
     * 铺满会把 4:3 的台标、字幕直接切掉，观感就是「放大截断」。
     */
    private int scaleMode = PlayerActivity.MODE_FIT;
    private static final double MAX_CROP = 0.12;

    private final Runnable hideInfo = new Runnable() {
        public void run() {
            if (topBar != null) {
                topBar.animate().alpha(0f).setDuration(260).start();
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
        groupIdx = getIntent().getIntExtra("groupIdx", 0);
        chIdx = getIntent().getIntExtra("chIdx", 0);
        if (groupIdx < 0 || groupIdx >= groups.size()) {
            groupIdx = 0;
        }

        buildUi();

        if (groups.isEmpty() || !pickChannel()) {
            showDead("没有可用的直播源");
            return;
        }
        ui.post(new Runnable() {
            public void run() {
                play(0);
            }
        });
        ui.postDelayed(watchdog, 2000);
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
    protected void onDestroy() {
        super.onDestroy();
        if (vv != null) {
            try {
                vv.stopPlayback();
            } catch (Throwable t) {
            }
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
        // 默认适应（完整显示不裁切）；红键可切铺满 / 自动
        vv.setMode(scaleMode, MAX_CROP);
        // 铺满时视图会比容器大，必须靠 CENTER 让它居中溢出，
        // 否则默认 START|TOP 会贴左上，看起来就是「只截掉右下角」
        root.addView(vv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

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

        // 轻提示（切比例、自动换源都往这儿写一句，1.6 秒消失）
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

        topBar = buildTopBar();
        root.addView(topBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // 底部：面板 + 键帽提示，两者叠在一起放在底部
        bottomWrap = new LinearLayout(this);
        bottomWrap.setOrientation(LinearLayout.VERTICAL);
        buildPanel();
        bottomWrap.addView(panel);
        bottomWrap.addView(buildBottomBar());
        root.addView(bottomWrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
    }

    /**
     * 顶部信息条：品牌方块 + 台名 + 副名/线路 + 画质角标。
     *
     * 背景用「上深下透明」的渐变，贴在视频上不会像 v2.6 那样压出一条硬边。
     */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.px(48), Ui.px(18), Ui.px(48), Ui.px(26));
        bar.setBackground(Ui.roundGrad(0xCC000000, 0x00000000, 0));

        bar.addView(Chrome.brandBlock(this));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.px(16);

        topName = Ui.bold(this, "", 30, Ui.FG);
        topName.setSingleLine(true);
        topName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(topName);

        topSub = Ui.text(this, "", 15, Ui.FG_DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.px(2);
        col.addView(topSub, slp);

        bar.addView(col, clp);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        topBadge = Ui.bold(this, "HD", 12, Ui.BG);
        topBadge.setGravity(Gravity.CENTER);
        topBadge.setPadding(Ui.px(8), Ui.px(3), Ui.px(8), Ui.px(3));
        topBadge.setBackground(Ui.round(Ui.ACCENT, 4, 0, 0));
        bar.addView(topBadge);

        return bar;
    }

    /** 底部键帽提示条 + 右侧在线状态点。 */
    private View buildBottomBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.px(48), 0, Ui.px(48), Ui.px(18));
        wrap.setBackground(Ui.roundGrad(0x00000000, 0xB3000000, 0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.px(14), 0, 0);

        String[][] keys = new String[][] {
                { "↑ ↓", "换台" },
                { "← →", "换源" },
                { "OK", "重连" },
                { "菜单", "源列表" },
                { "红键", "比例" },
                { "返回", "退出" },
        };
        row.addView(Chrome.keyHintRow(this, keys, "OK"));

        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(Chrome.statusDot(this, "直播 · 卡了自动换下一路", Ui.OK_GREEN));

        wrap.addView(row);
        bottomHint = null;
        return wrap;
    }

    /** 换源面板：底部一排源，当前那个实心琥珀。 */
    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF20B0E13);
        panel.setPadding(Ui.px(48), Ui.px(20), Ui.px(48), Ui.px(22));
        panel.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Chrome.title(this, "切换线路"));
        TextView tip = Chrome.subtitle(this, "　同一频道有多路源，挑能播的就行");
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
        root.post(new Runnable() {
            public void run() {
                // 面板展开时把键帽条藏掉，避免两条信息叠在一起
                View v = bottomWrap.getChildCount() > 1 ? bottomWrap.getChildAt(1) : null;
                if (v != null) {
                    v.setVisibility(panelVisible ? View.GONE : View.VISIBLE);
                }
            }
        });
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

    /* ==================== 播放 ==================== */

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

    /** 用户主动操作：把「自动跳过」的计数清零，不然几次换台之后就误判为全挂。 */
    private void manual() {
        autoSkips = 0;
        dead = false;
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
        urlIdx = idx;
        dead = false;
        paused = false;
        bigMsg.setVisibility(View.GONE);
        busy.setVisibility(View.VISIBLE);
        busyText.setText("正在缓冲…");
        busyText.setVisibility(View.VISIBLE);
        updateInfo();

        playAt = System.currentTimeMillis();
        bufAt = System.currentTimeMillis();
        lastPos = -1;
        posAt = 0;

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
                vv.setMode(scaleMode, MAX_CROP);
                vv.start();
                vv.post(new Runnable() {
                    public void run() {
                        vv.requestLayout();
                    }
                });
                restInfo();
                posAt = System.currentTimeMillis();
                lastPos = -1;
                Trace.add("live", "直播起播 " + ch.name + " 线路" + (urlIdx + 1)
                        + "/" + urls.size() + " " + describeVideo(mp));
            }
        });
        vv.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    busy.setVisibility(View.VISIBLE);
                    busyText.setVisibility(View.VISIBLE);
                    bufAt = System.currentTimeMillis();
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
                    busy.setVisibility(View.GONE);
                    busyText.setVisibility(View.GONE);
                    bufAt = 0;
                    vv.requestLayout();
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
        if (autoSkips > urls.size() * 2) {
            showDead("该频道暂时看不了，试试别的台");
            return;
        }
        showToast(why);
        play((urlIdx + 1) % urls.size());
    }

    /**
     * 看门狗：巡检脚本只能证明「这一秒连得上」，证明不了「能一直播」。
     * 这三种情况一律换源：起播超时、缓冲卡住、画面位置不再前进。
     */
    private final Runnable watchdog = new Runnable() {
        public void run() {
            checkAlive();
            ui.postDelayed(this, 2000);
        }
    };

    private void checkAlive() {
        if (dead || paused || urls.isEmpty() || panelVisible) {
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
                }
            }
        } catch (Throwable t) {
        }
    }

    /** 上一台 / 下一台：只在同一个分组里循环，跨组用分组长按成本太高，先不做。 */
    private void switchChannel(int delta) {
        if (group == null || group.chs.isEmpty()) {
            return;
        }
        manual();
        int n = group.chs.size();
        chIdx = ((chIdx + delta) % n + n) % n;
        if (!pickChannel()) {
            showDead("这个台没有可用的线路");
            return;
        }
        Store.saveLive(groupIdx, chIdx);
        play(0);
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
        if (alias.length() > 0) {
            sb.append(alias).append("　·　");
        }
        sb.append("线路 ").append(urlIdx + 1).append("/").append(urls.size());
        sb.append("　·　↑↓ 换台");
        topSub.setText(sb.toString());
        topBadge.setText(qualityOf(ch.name));
        setTopVisible();
    }

    private void setTopVisible() {
        ui.removeCallbacks(hideInfo);
        topBar.setAlpha(1f);
        topBar.setVisibility(View.VISIBLE);
    }

    /** 起播成功后：信息条 5 秒淡出。 */
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
     * 循环 适应 / 铺满 / 自动。
     *
     * 直播场景里「适应」应当是默认，「铺满」只在用户明确想看大画面、
     * 且频道本来就是 16:9 的时候才用得上。
     */
    private void cycleScale() {
        scaleMode = (scaleMode + 1) % 3;
        vv.setMode(scaleMode, MAX_CROP);
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
            finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            togglePanel();
            return true;
        }
        if (code == KEY_RED) {
            cycleScale();
            return true;
        }
        // 遥控器「频道 +/-」按电视的常规习惯是换台，不是换源 —— 换源用 ←→ 和菜单键。
        if (code == KEY_CH_UP) {
            switchChannel(-1);
            return true;
        }
        if (code == KEY_CH_DOWN) {
            switchChannel(1);
            return true;
        }
        if (panelVisible) {
            // 面板开着的时候方向键交给面板自己走焦点，别在这一层截
            return super.onKeyDown(code, e);
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
            // OK 键：画面卡住时手动重连当前线路
            manual();
            play(urlIdx);
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
}

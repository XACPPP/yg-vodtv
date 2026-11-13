package com.yg.vodtv;

import android.app.Activity;
import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;

/** 播放页：VideoView + 本地代理 + 自绘控制条 + 选集浮层。 */
public final class PlayerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private VideoView vv;
    private ProgressBar busy;
    private ProgressBar seekBar;
    private TextView toast;
    private TextView timeLabel;
    private LinearLayout bottomBar;
    /** 顶部标题条。以前是局部变量，导致它永远不会隐藏，一直压着画面顶部。 */
    private LinearLayout topBar;
    private FrameLayout root;
    private TextView titleView;

    private LinearLayout epPanel;
    private LinearLayout epRows;
    private boolean epVisible = false;
    private boolean controlsVisible = true;

    private String[] urls = new String[0];
    private String[] names = new String[0];
    private int index = 0;
    private int vid = 0;
    /** 多源唯一标识：源_id，播放位置按它存。 */
    private String key = "";
    private String title = "";
    private boolean prepared = false;
    private boolean retried = false;
    /** updateProgress 每秒跑一次，用它控制落盘频率。 */
    private int tickCount = 0;

    /**
     * 画面模式。
     *
     * 0 = 自动（默认）：裁得少就铺满，裁得多就退回适应
     * 1 = 适应：等比完整显示，多余留黑
     * 2 = 铺满：等比放大到盖满屏幕，超出部分裁掉
     *
     * 为什么加「自动」：老板在手机上装完反馈「画面正常了但没全屏」。
     * 手机是 20:9 左右，16:9 的片子按「适应」只能占中间那一块，左右留黑边 ——
     * 这不是 bug，是「完整显示」的必然结果。但铺满只裁掉上下各一小条，观感完全能接受；
     * 而竖屏短剧（9:16）铺满要裁掉七成以上，那就不能铺。所以按「裁切比例」判断：
     * 裁得少就铺满，裁得多就适应。
     */
    /**
     * 画面比例三档。改成包内可见，是因为直播页 {@link LivePlayerActivity}
     * 复用同一个 FitVideoView，也要用同一套语义（v2.7 起直播页支持红键切比例）。
     */
    static final int MODE_AUTO = 0;
    static final int MODE_FIT = 1;
    static final int MODE_FILL = 2;
    private int scaleMode = MODE_AUTO;

    /**
     * 自动模式下「视频比例」与「容器比例」允许的偏差。
     *
     * v2.7 及以前这里是个「裁切比例 ≤ 34% 就铺满」的阈值，语义是错的：
     * 它比较的是「铺满后被裁掉多少」，而不是「这个视频跟屏幕是不是同一种比例」。
     * 结果是 4:3 的老剧在 16:9 电视上裁掉 30% 就算「能接受」，上下直接被切；
     * 而 2.35:1 的电影只裁 24% 也判铺满 —— 老板说的「铺满又很奇怪」就是它。
     *
     * 现在改成比「宽高比偏差」：δ = |视频AR - 容器AR| / max(两者)。
     *   16:9 片源 + 16:9 屏幕 → δ = 0        → 铺满（本来就是满屏）
     *   1920×1088（H.264 宏块对齐）→ δ ≈ 0.7% → 铺满（视觉上就是满屏）
     *   4:3 片源   + 16:9 屏幕 → δ = 25%     → 适应（两侧留黑，不切人脸）
     *   2.35:1     + 16:9 屏幕 → δ = 25%     → 适应（上下加黑，保留完整画幅）
     *   9:16 竖屏短剧          → δ = 78%     → 适应
     * 4% 的容差刚好能吃掉 1088 这种编码取整误差，又不会把 4:3 误判成满屏。
     */
    private static final double MAX_AR_DEV = 0.04;

    private Runnable hideToast;
    private Runnable hideControls;
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        Proxy.start();
        Store.init(this);

        urls = getIntent().getStringArrayExtra("urls");
        names = getIntent().getStringArrayExtra("names");
        index = getIntent().getIntExtra("index", 0);
        vid = getIntent().getIntExtra("id", 0);
        key = Store.vid(getIntent().getStringExtra("src"), vid);
        title = getIntent().getStringExtra("title");
        if (urls == null) {
            urls = new String[0];
        }
        if (names == null) {
            names = new String[0];
        }
        if (title == null) {
            title = "";
        }

        buildUi();
        if (urls.length == 0) {
            showToast("没有可播放的地址");
            return;
        }
        ui.post(new Runnable() {
            public void run() {
                playAt(index);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNow();
        if (vv != null && vv.isPlaying()) {
            vv.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveNow();
        if (vv != null) {
            try {
                vv.stopPlayback();
            } catch (Throwable t) {
            }
        }
        ui.removeCallbacksAndMessages(null);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // 原生 VideoView 会把控件按视频比例测量（API22 的兼容逻辑，见类尾注释），
        // 这是我们要的行为——4:3、竖屏短剧都能等比完整显示。
        // 真正的 bug 是 FrameLayout 默认把子 View 摆在左上角，缩小的控件就贴左边了，
        // 右边/下边留一大片黑。所以这一行 Gravity.CENTER 才是修截断的关键。
        vv = new FitVideoView(this);
        vv.setFocusable(true);
        vv.setFocusableInTouchMode(true);
        root.addView(vv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        busy = new ProgressBar(this);
        root.addView(busy, new FrameLayout.LayoutParams(Ui.px(90), Ui.px(90), Gravity.CENTER));

        // 顶部标题。用字段而不是局部变量：局部变量的话 hideControlsNow() 拿不到它，
        // 控制条收起时这条黑条会永远压在画面上沿。
        //
        // v2.7：顶/底控制条不再用「一块半透明黑」，改成上下渐隐 scrim ——
        // 一块纯色黑条压在画面上，边缘是硬切的一条直线，观感很廉价；
        // 渐隐是设计稿里所有浮层的统一做法，画面边缘过渡自然，文字也更清楚。
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackground(Ui.roundGrad(0xE6000000, 0x00000000, 0));
        topBar.setPadding(Ui.px(48), Ui.px(22), Ui.px(48), Ui.px(26));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        titleView = Ui.text(this, "", 32, 0xFFFFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(Chrome.keyHintRow(this, new String[][]{
                {"OK", "暂停"}, {"↑", "选集"}, {"← →", "快进退"}, {"菜单", "画面比例"}
        }, "OK"));
        topBar.addView(topRow);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        // 让 scrim 自己盖住状态栏高度那一截，避免开机第一帧出现一条没渲染的黑边
        tlp.topMargin = -Ui.px(4);
        root.addView(topBar, tlp);

        // 底部进度
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackground(Ui.roundGrad(0x00000000, 0xE6000000, 0));
        bottomBar.setPadding(Ui.px(48), Ui.px(30), Ui.px(48), Ui.px(20));
        timeLabel = Ui.bold(this, "00:00 / 00:00", 22, 0xFFFFFFFF);
        timeLabel.setPadding(0, Ui.px(6), 0, Ui.px(6));
        bottomBar.addView(timeLabel);
        seekBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        seekBar.setMax(1000);
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        toast = Ui.bold(this, "", 24, 0xFFFFFFFF);
        // v2.7：提示条跟顶栏用同一套「浮层语言」—— 实底 surface + 琥珀细描边，
        // 比半透明纯黑更像设计稿里的 pill。
        toast.setBackground(Ui.glowBg(Ui.SURFACE, 12, Ui.ACCENT));
        toast.setPadding(Ui.px(28), Ui.px(14), Ui.px(28), Ui.px(14));
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        olp.bottomMargin = Ui.px(150);
        root.addView(toast, olp);

        buildEpPanel();
        setContentView(root);
        updateTitle();

        ticker = new Runnable() {
            public void run() {
                updateProgress();
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(ticker, 1000);
        showControls();
    }

    private void buildEpPanel() {
        epPanel = new LinearLayout(this);
        epPanel.setOrientation(LinearLayout.VERTICAL);
        // v2.7：面板底色从「半透明纯黑」换成设计稿的 surface_lowest 实底 + 上沿一道细线，
        // 半透明黑在深色画面上会显得发灰、跟画面糊在一起。
        epPanel.setBackgroundColor(Ui.SURFACE_LOWEST);
        epPanel.setPadding(Ui.px(56), Ui.px(32), Ui.px(56), Ui.px(24));
        epPanel.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Chrome.title(this, "选集"));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagLp.leftMargin = Ui.px(14);
        head.addView(Chrome.inlineTag(this, "EPISODES"), tagLp);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.leftMargin = Ui.px(16);
        head.addView(Chrome.subtitle(this, "按 返回 / 上键 收起"), subLp);
        epPanel.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        epRows = new LinearLayout(this);
        epRows.setOrientation(LinearLayout.VERTICAL);
        epRows.setClipChildren(false);
        epRows.setPadding(0, Ui.px(20), 0, Ui.px(8));
        sv.addView(epRows);
        epPanel.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(epPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void fillEpisodes() {
        epRows.removeAllViews();
        if (names.length == 0) {
            return;
        }
        int w = Ui.px(170);
        int h = Ui.px(80);
        int i = 0;
        while (i < names.length) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // 焦点格放大 + 外发光，行/列容器不放开裁剪会把辉光切掉一圈
            row.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(12);
            for (int c = 0; c < 6 && i < names.length; c++, i++) {
                final int idx = i;
                boolean cur = idx == index;
                String label = (cur ? "▶ " : "") + names[i];
                TextView t = Ui.bold(this, label, 22, cur ? Ui.BG : Ui.FG);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setFocusable(true);
                t.setClickable(true);
                // v2.7：当前集用琥珀实心（一眼能看出正在看哪一集），其余用「描边 + 外发光」，
                // 跟首页海报、直播卡同一套焦点语言。
                t.setBackground(cur
                        ? Ui.focusBgGlow(Ui.ACCENT, 10, Ui.ACCENT_HI)
                        : Ui.focusBgGlow(Ui.BG_CARD, 10, Ui.ACCENT));
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        playAt(idx);
                        toggleEpisodes(false);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                lp.rightMargin = Ui.px(12);
                row.addView(t, lp);
                if (cur) {
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
    }

    private void toggleEpisodes(boolean show) {
        epVisible = show;
        if (show) {
            fillEpisodes();
            epPanel.setVisibility(View.VISIBLE);
            hideControlsNow();
        } else {
            epPanel.setVisibility(View.GONE);
            showControls();
            vv.requestFocus();
        }
    }

    private void updateTitle() {
        String ep = (index >= 0 && index < names.length) ? names[index] : "";
        if (titleView != null) {
            titleView.setText(title + (ep.length() > 0 ? "   " + ep : ""));
        }
    }

    private void updateProgress() {
        if (!prepared || vv == null) {
            return;
        }
        try {
            int p = vv.getCurrentPosition();
            int d = vv.getDuration();
            if (d > 0) {
                seekBar.setProgress((int) (p * 1000L / d));
            }
            timeLabel.setText(fmt(p) + " / " + fmt(d));
            // 每 5 秒落一次盘就够了，写太勤对闪存不友好
            if (++tickCount % 5 == 0) {
                Store.savePos(key, index, p);
                // 顺手把总时长也记下：详情页选集格子的进度条要靠它算比例
                Store.saveDur(key, index, d);
            }
        } catch (Throwable t) {
        }
    }

    /** 退出/切后台时把位置存下来。 */
    private void saveNow() {
        if (!prepared || vv == null) {
            return;
        }
        try {
            Store.savePos(key, index, vv.getCurrentPosition());
        } catch (Throwable t) {
        }
    }

    private void playAt(int idx) {
        if (idx < 0 || idx >= urls.length) {
            return;
        }
        // 切集之前，先把当前这一集看到哪儿存下来
        saveNow();
        index = idx;
        prepared = false;
        retried = false;
        tickCount = 0;
        updateTitle();
        busy.setVisibility(View.VISIBLE);

        final String raw = urls[idx];
        final String local = Proxy.wrapPlaylist(raw);

        vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                busy.setVisibility(View.GONE);
                mp.setScreenOnWhilePlaying(true);
                // v2.8：把播放器报的真实尺寸直接喂给 FitVideoView。
                // 原来这里只 post 一个 requestLayout，靠反射读 mVideoWidth —— 那是上一帧的值，
                // 第一次测量时通常还是 0，于是先按容器铺满；等尺寸到了又没人再触发测量，
                // 就一直停在「按容器铺满」上。这就是「比例算错」的第二层。
                if (vv instanceof FitVideoView) {
                    try {
                        ((FitVideoView) vv).setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                    } catch (Throwable t) {
                    }
                }
                applyScaling();
                vv.start();
                // VideoView.onPrepared 是先回调我们、之后才把 mVideoWidth/mVideoHeight 赋上去的，
                // 所以这里不能直接读尺寸。等这一轮消息走完再量一次，
                // 否则 4:3/竖屏片子会一直停在「还没尺寸」时的铺满状态。
                vv.post(new Runnable() {
                    public void run() {
                        vv.requestLayout();
                    }
                });
                // 上次看到一半的，接着看：只在保存位置离片头和片尾都不太近时才跳
                int saved = Store.pos(key, index);
                int dur = 0;
                try {
                    dur = vv.getDuration();
                } catch (Throwable t) {
                }
                if (saved > 5000 && (dur <= 0 || saved < dur - 10000)) {
                    vv.seekTo(saved);
                    showToast("接着上次看 " + fmt(saved));
                }
                updateProgress();
                showControls();
                Trace.add("player", "起播成功 第" + (index + 1) + "集 " + sizeInfo(mp)
                        + " 屏幕 " + Ui.w() + "x" + Ui.h() + " 模式 " + modeName());
            }
        });
        // HLS 多码率切换时分辨率会变（比如 640x360 → 1280x720），
        // VideoView 内部会把新尺寸写给 mVideoWidth/mVideoHeight，但不会主动重新测量，
        // 结果就是播到一半画面突然变形或缩到一角。这里自己 requestLayout 一次。
        vv.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    // 码率切换后分辨率会变，重新喂一次尺寸再量
                    if (vv instanceof FitVideoView) {
                        try {
                            ((FitVideoView) vv).setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                        } catch (Throwable t) {
                        }
                    }
                    vv.requestLayout();
                }
                return false;
            }
        });
        vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                prepared = false;
                busy.setVisibility(View.GONE);
                Trace.add("player", "播放失败 what=" + what + " extra=" + extra
                        + " 第" + (index + 1) + "集 " + Http.shortUrl(raw));
                // 同一个源重试一次：VideoView 偶发在 HLS 首包上失败，重设地址通常能过
                if (!retried) {
                    retried = true;
                    showToast("这条线路没播起来，正在重试…");
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            vv.setVideoURI(Uri.parse(local));
                            vv.start();
                        }
                    }, 900);
                    return true;
                }
                showToast("这条线路播放失败（" + what + "/" + extra + "），按上键换一集试试");
                return true;
            }
        });
        vv.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                prepared = false;
                // 这集看完了，把位置清零，下次点进来从头播，不要「接着看」跳到片尾
                Store.savePos(key, index, 0);
                if (idx + 1 < urls.length) {
                    showToast("自动播放下一集");
                    playAt(idx + 1);
                } else {
                    showToast("已播完");
                }
            }
        });

        try {
            vv.setVideoURI(Uri.parse(local));
            vv.requestFocus();
        } catch (Throwable t) {
            busy.setVisibility(View.GONE);
            showToast("地址解析失败");
        }
    }

    private void togglePlay() {
        try {
            if (vv.isPlaying()) {
                vv.pause();
                showToast("已暂停");
                showControls();
            } else {
                vv.start();
                showToast("播放中");
            }
        } catch (Throwable t) {
        }
    }

    private void seekBy(int ms) {
        try {
            int p = vv.getCurrentPosition();
            int d = vv.getDuration();
            int target = p + ms;
            if (target < 0) {
                target = 0;
            }
            if (d > 0 && target > d - 1500) {
                target = d - 1500;
            }
            vv.seekTo(target);
            showToast((ms > 0 ? "快进 → " : "快退 ← ") + fmt(target));
            showControls();
        } catch (Throwable t) {
        }
    }

    private void showControls() {
        controlsVisible = true;
        if (bottomBar != null) {
            bottomBar.setVisibility(View.VISIBLE);
        }
        if (topBar != null) {
            topBar.setVisibility(View.VISIBLE);
        }
        if (hideControls != null) {
            ui.removeCallbacks(hideControls);
        }
        hideControls = new Runnable() {
            public void run() {
                hideControlsNow();
            }
        };
        ui.postDelayed(hideControls, 5000);
    }

    private void hideControlsNow() {
        controlsVisible = false;
        if (bottomBar != null && !epVisible) {
            bottomBar.setVisibility(View.GONE);
        }
        // 顶部标题条同样要收起来，否则它会一直盖在画面最上面
        if (topBar != null && !epVisible) {
            topBar.setVisibility(View.GONE);
        }
    }

    private void showToast(String msg) {
        toast.setText(msg);
        toast.setVisibility(View.VISIBLE);
        if (hideToast != null) {
            ui.removeCallbacks(hideToast);
        }
        hideToast = new Runnable() {
            public void run() {
                toast.setVisibility(View.GONE);
            }
        };
        ui.postDelayed(hideToast, 4000);
    }

    /** 把当前画面模式应用到 VideoView，并在 Trace 里留个记录。 */
    private void applyScaling() {
        if (vv instanceof FitVideoView) {
            ((FitVideoView) vv).setMode(scaleMode, MAX_AR_DEV);
        }
    }

    /** 切「自动 / 适应 / 铺满」三档，循环。竖屏短剧和宽银幕老片都靠它。 */
    private void toggleScaleMode() {
        scaleMode = (scaleMode + 1) % 3;
        applyScaling();
        String tip;
        if (scaleMode == MODE_FIT) {
            tip = "画面：适应（完整显示，两侧留黑）";
        } else if (scaleMode == MODE_FILL) {
            tip = "画面：铺满（超出部分裁掉）";
        } else {
            tip = "画面：自动（按片源比例自己判断）";
        }
        showToast(tip);
        Trace.add("player", "画面模式切到 " + modeName() + "；" + scalingDetail());
    }

    private String modeName() {
        if (scaleMode == MODE_FIT) {
            return "适应";
        }
        return scaleMode == MODE_FILL ? "铺满" : "自动";
    }

    /** 当前这次实际是铺满还是适应、裁/黑了多少，写日志和 toast 用。 */
    private String scalingDetail() {
        if (!(vv instanceof FitVideoView)) {
            return "";
        }
        return ((FitVideoView) vv).describe();
    }

    /** 视频尺寸与比例描述，写日志用。「竖屏」「4:3」这类词比 1440x1080 直观。 */
    private String sizeInfo(MediaPlayer mp) {
        int w = 0;
        int h = 0;
        try {
            w = mp.getVideoWidth();
            h = mp.getVideoHeight();
        } catch (Throwable t) {
        }
        if (w <= 0 || h <= 0) {
            return "尺寸未知";
        }
        double ar = (double) w / (double) h;
        String shape;
        if (ar < 0.85) {
            shape = "竖屏";
        } else if (ar < 1.5) {
            shape = "4:3";
        } else if (ar < 1.9) {
            shape = "16:9";
        } else {
            shape = "宽银幕";
        }
        return w + "x" + h + " " + shape + " " + String.format(Locale.US, "%.2f", ar);
    }

    private static String fmt(int ms) {
        if (ms <= 0) {
            return "00:00";
        }
        int s = ms / 1000;
        int m = s / 60;
        int hh = m / 60;
        if (hh > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hh, m % 60, s % 60);
        }
        return String.format(Locale.US, "%02d:%02d", m, s % 60);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (epVisible) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
                toggleEpisodes(false);
                return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                toggleEpisodes(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (controlsVisible) {
                    hideControlsNow();
                } else {
                    showControls();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                toggleScaleMode();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (index + 1 < urls.length) {
                    playAt(index + 1);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (index > 0) {
                    playAt(index - 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-15000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(15000);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /**
     * 按比例自动缩放的 VideoView。
     *
     * 为什么要自己写 onMeasure——原生 VideoView 在 API 22 的行为是这样的（AOSP 源码注释原文）：
     *
     *     // the size is fixed
     *     width = widthSpecSize; height = heightSpecSize;
     *     // for compatibility, we adjust size based on aspect ratio
     *     if (mVideoWidth * height  < width * mVideoHeight) {
     *         width = height * mVideoWidth / mVideoHeight;   // 视频更高（竖屏）
     *     } else if (mVideoWidth * height  > width * mVideoHeight) {
     *         height = width * mVideoHeight / mVideoWidth;   // 视频更宽（电影宽银幕）
     *     }
     *
     * 也就是说：控件会被压成视频的比例，永远有一边小于父容器。
     * 这个行为本身**没错**（等比完整显示就是这个意思），错的是 ViewGroup 怎么摆它。
     *
     * 而 FrameLayout 的默认 layout_gravity 是 START|TOP，于是：
     *   4:3 片子     → 控件 1440×1080，贴在 x=0，右边 480px 纯黑
     *   竖屏 9:16    → 控件 607×1080，贴在 x=0，右边 1313px 纯黑（用户说的「左边一小块」）
     *   宽银幕 2.35  → 控件 1920×817，贴在 y=0，看起来没事，但其实上边对齐、下边空
     * 这就是「部分影片画面被截断、右边一大片全黑」的真因，跟顶部标题栏无关。
     *
     * 修法分成两层：
     * 1) 布局层：外面用 Gravity.CENTER 摆这个控件 → 缩小的控件居中，两侧对称留黑（已在 buildUi 做）。
     * 2) 缩放层：如果用户选「铺满」，就把控件按比例放大到盖满父容器，
     *    由父容器 FrameLayout 的 clipChildren（默认 true）裁掉溢出部分。
     *
     * 覆盖 onMeasure 而不是只靠 layout_gravity 的原因：API 22 上
     * MediaPlayer.setVideoScalingMode 对「控件比 Surface 大」这种情况支持不稳，
     * 有的 ROM 会直接黑屏。用控件尺寸控制最保险，各版本行为一致。
     */
    static final class FitVideoView extends VideoView {

        FitVideoView(Context c) {
            super(c);
        }

        /** 当前模式：MODE_AUTO / MODE_FIT / MODE_FILL。 */
        private int mode = MODE_AUTO;
        /** 自动模式下「视频比例 vs 容器比例」允许的偏差，见 MAX_AR_DEV。 */
        private double maxDev = 0.04;

        /**
         * 外部显式告知的视频尺寸（0 表示还不知道）。
         *
         * 为什么需要这个：原来只能靠反射读 VideoView 的私有字段 mVideoWidth，
         * 那是**上一帧**的值。直播流的特点恰恰是「起播快、尺寸晚到」——
         * onPrepared 时 mVideoWidth 常常还是 0，于是第一次测量走 super.onMeasure
         * 拿满屏尺寸，之后尺寸到了却没人再触发 requestLayout，
         * 画面就一直按「满屏」渲染。播放器自己给的尺寸最可信，直接喂进来。
         */
        private int exW = 0;
        private int exH = 0;

        /** 上一次测量实际用了哪种方式，给日志/提示用。 */
        private boolean lastFill = false;
        private int lastVw = 0;
        private int lastVh = 0;

        void setMode(int mode, double maxDev) {
            if (this.mode != mode || this.maxDev != maxDev) {
                this.mode = mode;
                this.maxDev = maxDev;
                requestLayout();
            }
        }

        int mode() {
            return mode;
        }

        /** 由播放器回调喂真实视频尺寸（MediaPlayer.getVideoWidth/Height）。 */
        void setVideoSize(int w, int h) {
            if (w > 0 && h > 0 && w <= 8192 && h <= 8192 && (w != exW || h != exH)) {
                exW = w;
                exH = h;
                requestLayout();
            }
        }

        /**
         * 自动模式的核心判断：这个视频和屏幕是不是「同一种形状」？
         *
         * 判据是宽高比偏差，不是「裁掉多少」（旧逻辑的错，见 MAX_AR_DEV 注释）：
         *   δ = |视频AR - 容器AR| / max(视频AR, 容器AR)
         * δ 足够小 → 铺满（同形状，铺满等于刚好铺满，不裁任何内容）
         * δ 偏大   → 适应（形状不同，硬铺一定会切掉画面，宁可留黑）
         *
         * 特意处理「非方形像素」：MediaPlayer 报的是**编码尺寸**，不含 SAR。
         * 老剧常见的 1440×1080、960×720 其实是 4:3 像素存的 16:9 画面（PAR 4:3），
         * 按 1.333 的比例算就会被判成 4:3 → 左右留两条大黑边。
         * 这正是老板说的「明明可以完美做满屏，却被留出大黑边」。
         * 这类编码尺寸在国产剧源里出现率极高，直接按 16:9 认。
         */
        private boolean wantFill(int availW, int availH, int vw, int vh) {
            if (mode == MODE_FILL) {
                return true;
            }
            if (mode == MODE_FIT) {
                return false;
            }
            double videoAr = effectiveAr(vw, vh);
            double boxAr = (double) availW / (double) availH;
            double bigger = Math.max(videoAr, boxAr);
            if (bigger <= 0) {
                return true;
            }
            double dev = Math.abs(videoAr - boxAr) / bigger;
            return dev <= maxDev;
        }

        /**
         * 视频的**显示**宽高比（修正非方形像素）。
         *
         * 只认几组确定无疑的 anamorphic 编码尺寸，不做泛化推测 ——
         * 猜错的代价是把真 4:3 内容拉变形，比留黑边更难看。
         */
        private double effectiveAr(int vw, int vh) {
            if (vh == 1080 && (vw == 1440 || vw == 1456)) {
                return 16.0 / 9.0;   // HDV / 早期高清剧：4:3 像素存 16:9 画面
            }
            if (vh == 1088 && (vw == 1440 || vw == 1920)) {
                return 16.0 / 9.0;   // 1088 是 1080 的宏块对齐版，同样按 16:9
            }
            if (vh == 720 && (vw == 960 || vw == 1280)) {
                return 16.0 / 9.0;
            }
            if (vh == 576 && vw == 720) {
                return 4.0 / 3.0;    // PAL DVD，本来就是 4:3
            }
            return (double) vw / (double) vh;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // 优先用播放器显式喂进来的尺寸（setVideoSize），它一定是最新的。
            // 反射读 mVideoWidth 只作为兜底：那是上一帧的值，直播起播时经常还是 0，
            // 拿到 0 就退回 super.onMeasure 按容器铺满 —— 这正是之前「放大截断」的来源。
            int vw = exW;
            int vh = exH;
            if (vw <= 0 || vh <= 0) {
                try {
                    vw = getMeasuredVideoWidth();
                    vh = getMeasuredVideoHeight();
                } catch (Throwable t) {
                }
            }
            // 还没拿到视频尺寸（刚 new 出来、纯音频流、或解析器还没探到 SPS）：
            // 先按父容器来，别撑成 0；等尺寸回来后会 requestLayout 重新量。
            // vw/vh 大于 8192 基本是解析异常（有的源会把 mVideoWidth 写成垃圾值），
            // 也走这条兜底，否则会算出一个几万像素的 View 直接 OOM。
            if (vw <= 0 || vh <= 0 || vw > 8192 || vh > 8192) {
                lastFill = false;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            int availW = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
            int availH = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);
            if (availW <= 0 || availH <= 0) {
                lastFill = false;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }

            // 等比缩放系数：适应取小（完整装进容器），铺满取大（盖住容器后裁掉溢出）。
            // 两种模式都严格保持视频宽高比，绝不会把画拉变形。
            boolean fill = wantFill(availW, availH, vw, vh);
            double sx = (double) availW / (double) vw;
            double sy = (double) availH / (double) vh;
            double scale = fill ? Math.max(sx, sy) : Math.min(sx, sy);
            int outW = Math.max(1, (int) Math.round(vw * scale));
            int outH = Math.max(1, (int) Math.round(vh * scale));
            lastFill = fill;
            lastVw = vw;
            lastVh = vh;
            setMeasuredDimension(outW, outH);
        }

        /** 「1920x1080 → 铺满 2400x1350（裁上下 20%）」这种描述，写日志用。 */
        String describe() {
            if (lastVw <= 0 || lastVh <= 0) {
                return "尺寸未知";
            }
            int availW = getWidth();
            int availH = getHeight();
            if (availW <= 0 || availH <= 0) {
                return lastVw + "x" + lastVh;
            }
            double videoAr = effectiveAr(lastVw, lastVh);
            double boxAr = (double) availW / (double) availH;
            double bigger = Math.max(Math.max(videoAr, boxAr), 0.0001);
            double dev = Math.abs(videoAr - boxAr) / bigger;
            String how = lastFill ? "铺满" : "适应";
            String cut = lastFill && dev > 0.005
                    ? "，裁掉 " + Math.round(dev * 100) + "%" : "";
            String bars = !lastFill && dev > 0.005
                    ? "，留黑 " + Math.round(dev * 100) + "%" : "";
            return lastVw + "x" + lastVh + " 比例偏差 " + Math.round(dev * 1000) / 10.0 + "% "
                    + how + "（容器 " + availW + "x" + availH + cut + bars + "）";
        }

        /**
         * 拿视频尺寸。API 22 的 VideoView 没有公开 getVideoWidth()，
         * 只能通过反射读它的私有字段 mVideoWidth/mVideoHeight。
         * 反射失败就退化成「不知道尺寸」，此时按父容器铺满，不会黑屏。
         */
        private int getMeasuredVideoWidth() {
            return reflectInt("mVideoWidth");
        }

        private int getMeasuredVideoHeight() {
            return reflectInt("mVideoHeight");
        }

        private int reflectInt(String field) {
            try {
                java.lang.reflect.Field f = VideoView.class.getDeclaredField(field);
                f.setAccessible(true);
                return f.getInt(this);
            } catch (Throwable t) {
                return 0;
            }
        }
    }
}

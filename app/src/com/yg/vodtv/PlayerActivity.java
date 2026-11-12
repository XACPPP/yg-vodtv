package com.yg.vodtv;

import android.app.Activity;
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

        vv = new VideoView(this);
        vv.setFocusable(true);
        vv.setFocusableInTouchMode(true);
        root.addView(vv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        busy = new ProgressBar(this);
        root.addView(busy, new FrameLayout.LayoutParams(Ui.px(90), Ui.px(90), Gravity.CENTER));

        // 顶部标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(Ui.px(32), Ui.px(14), Ui.px(32), Ui.px(14));
        titleView = Ui.text(this, "", 30, 0xFFFFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.text(this, "OK 暂停/播放   上下键选集   左右键快进退", 22, 0xFFBFC7D2));
        root.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // 底部进度
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0x99000000);
        bottomBar.setPadding(Ui.px(40), Ui.px(12), Ui.px(40), Ui.px(16));
        timeLabel = Ui.text(this, "00:00 / 00:00", 22, 0xFFBFC7D2);
        timeLabel.setPadding(0, Ui.px(6), 0, Ui.px(6));
        bottomBar.addView(timeLabel);
        seekBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        seekBar.setMax(1000);
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        toast = Ui.text(this, "", 26, 0xFFFFFFFF);
        toast.setBackground(Ui.round(0xCC000000, 10, 0, 0));
        toast.setPadding(Ui.px(24), Ui.px(12), Ui.px(24), Ui.px(12));
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
        epPanel.setBackgroundColor(0xF20E1116);
        epPanel.setPadding(Ui.px(48), Ui.px(28), Ui.px(48), Ui.px(24));
        epPanel.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "选集", 34, Ui.ACCENT));
        head.addView(Ui.text(this, "    按 返回 / 上键 收起", 22, Ui.FG_DIM));
        epPanel.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        epRows = new LinearLayout(this);
        epRows.setOrientation(LinearLayout.VERTICAL);
        epRows.setPadding(0, Ui.px(16), 0, Ui.px(8));
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
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.px(12);
            for (int c = 0; c < 6 && i < names.length; c++, i++) {
                final int idx = i;
                boolean cur = idx == index;
                String label = (cur ? "▶ " : "") + names[i];
                TextView t = Ui.text(this, label, 24, cur ? 0xFF12161C : Ui.FG);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setFocusable(true);
                t.setClickable(true);
                t.setBackground(Ui.focusBg(cur ? Ui.ACCENT : Ui.BG_CARD, 8, Ui.ACCENT));
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
                vv.start();
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
                Trace.add("player", "起播成功 第" + (index + 1) + "集 "
                        + (mp.getVideoWidth() > 0 ? mp.getVideoWidth() + "x" + mp.getVideoHeight() : "尺寸未知"));
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
}

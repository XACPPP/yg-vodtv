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
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播播放页。
 *
 * 为什么不复用 PlayerActivity：那边一整套都是为点播写的——进度条、断点续播、
 * 播完自动下一集、选集浮层。直播没有「总时长」，`onCompletion` 一触发就会去播下一集，
 * 反而把正常播放打断。所以这里单独写一个薄的：只要 VideoView + 换源 + 换台。
 *
 * 换源是这一页存在的核心理由：直播源 40% 左右可用，卡了就得一键换下一个。
 * 三个入口都能换：菜单键、遥控器的 CH+ / CH-、出错时自动切。
 */
public final class LivePlayerActivity extends Activity {

    /**
     * 遥控器「频道 +」「频道 -」。国产电视/盒子基本都映射这两个码。
     * 注意：这两个码不是所有 ROM 都发，所以换台的主要入口是 ↑↓，这两个只是顺手支持。
     */
    private static final int KEY_CH_UP = 166;
    private static final int KEY_CH_DOWN = 167;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private VideoView vv;
    private ProgressBar busy;
    private TextView topInfo;
    private TextView bottomHint;
    private TextView bigMsg;
    private LinearLayout panel;
    private LinearLayout panelSources;

    private List<Live.Group> groups = new ArrayList<Live.Group>();
    private Live.Group group;
    private Live.Ch ch;
    private List<String> urls = new ArrayList<String>();

    private int groupIdx = 0;
    private int chIdx = 0;
    private int urlIdx = 0;
    /** 这一轮已经自动跳过的源数。超过 urls.size() 就说明全挂了，别再无限重试。 */
    private int fails = 0;
    private boolean panelVisible = false;
    private boolean dead = false;

    private final Runnable hideInfo = new Runnable() {
        public void run() {
            topInfo.setVisibility(View.GONE);
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

    /** ==================== 界面 ==================== */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        vv = new PlayerActivity.FitVideoView(this);
        vv.setFocusable(false);
        root.addView(vv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        busy = new ProgressBar(this);
        root.addView(busy, new FrameLayout.LayoutParams(Ui.px(90), Ui.px(90), Gravity.CENTER));

        bigMsg = Ui.bold(this, "", 32, Ui.FG);
        bigMsg.setGravity(Gravity.CENTER);
        bigMsg.setVisibility(View.GONE);
        root.addView(bigMsg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 顶部信息条：台名 + 第几个源，5 秒淡出
        topInfo = Ui.bold(this, "", 26, Ui.FG);
        topInfo.setBackgroundColor(0x9911141A);
        topInfo.setPadding(Ui.px(56), Ui.px(16), Ui.px(56), Ui.px(16));
        FrameLayout.LayoutParams til = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(topInfo, til);

        bottomHint = Ui.text(this, "↑↓ 换台　　←→ 换源　　OK 重连　　菜单 源列表　　返回 退出", 20, Ui.FG_DIM);
        bottomHint.setBackgroundColor(0x9911141A);
        bottomHint.setGravity(Gravity.CENTER);
        bottomHint.setPadding(0, Ui.px(12), 0, Ui.px(12));
        root.addView(bottomHint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        buildPanel();

        setContentView(root);
    }

    /** 换源面板：底部一排源，当前那个实心琥珀。 */
    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF211141A);
        panel.setPadding(Ui.px(56), Ui.px(20), Ui.px(56), Ui.px(24));
        panel.setVisibility(View.GONE);

        TextView t = Ui.bold(this, "切换线路", 24, Ui.ACCENT);
        panel.addView(t);

        panelSources = new LinearLayout(this);
        panelSources.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Ui.px(14);
        panel.addView(panelSources, pl);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    private void renderPanel() {
        panelSources.removeAllViews();
        for (int i = 0; i < urls.size(); i++) {
            final int idx = i;
            boolean on = i == urlIdx;
            TextView c = Ui.bold(this, "线路 " + (i + 1), 20, on ? 0xFF12161C : Ui.FG);
            c.setGravity(Gravity.CENTER);
            c.setSingleLine(true);
            c.setFocusable(true);
            c.setClickable(true);
            c.setPadding(Ui.px(24), Ui.px(9), Ui.px(24), Ui.px(9));
            c.setBackground(on ? Ui.round(Ui.ACCENT, 8, 0, 0)
                    : Ui.focusBg(Ui.BG_CARD, 8, Ui.ACCENT));
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
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
            TextView none = Ui.text(this, "这个台没有可用的线路", 20, Ui.FG_DIM);
            panelSources.addView(none);
        }
    }

    private void togglePanel() {
        panelVisible = !panelVisible;
        panel.setVisibility(panelVisible ? View.VISIBLE : View.GONE);
        bottomHint.setVisibility(panelVisible ? View.GONE : View.VISIBLE);
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

    /** ==================== 播放 ==================== */

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
        fails = 0;
        dead = false;
        return !urls.isEmpty();
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
        bigMsg.setVisibility(View.GONE);
        busy.setVisibility(View.VISIBLE);
        updateInfo();

        final String raw = urls.get(idx);
        final String local = Proxy.wrapPlaylist(raw);

        vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                busy.setVisibility(View.GONE);
                fails = 0;
                mp.setScreenOnWhilePlaying(true);
                vv.start();
                vv.post(new Runnable() {
                    public void run() {
                        vv.requestLayout();
                    }
                });
                restInfo();
                Trace.add("live", "直播起播 " + ch.name + " 线路" + (urlIdx + 1)
                        + "/" + urls.size());
            }
        });
        vv.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    busy.setVisibility(View.VISIBLE);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    busy.setVisibility(View.GONE);
                } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
                    vv.requestLayout();
                }
                return false;
            }
        });
        vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                busy.setVisibility(View.GONE);
                fails++;
                if (fails < urls.size()) {
                    // 还有别的源，静默切下一个，用户几乎无感
                    Trace.add("live", "线路" + (urlIdx + 1) + " 挂了（" + what + "/" + extra
                            + "），自动切下一个");
                    ui.post(new Runnable() {
                        public void run() {
                            playNext();
                        }
                    });
                    return true;
                }
                showDead("该频道暂时看不了，试试别的台（菜单键可手动换源）");
                return true;
            }
        });
        // 直播流一般不回调 onCompletion，但个别源会「播完一小段就结束」，
        // 这种情况下当作一次失败，交给上面的自动换源逻辑处理。
        vv.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                if (dead) {
                    return;
                }
                fails++;
                if (fails < urls.size()) {
                    Trace.add("live", "线路" + (urlIdx + 1) + " 提前结束，自动切下一个");
                    ui.post(new Runnable() {
                        public void run() {
                            playNext();
                        }
                    });
                } else {
                    showDead("该频道暂时看不了，试试别的台");
                }
            }
        });

        try {
            vv.setVideoURI(Uri.parse(local));
            vv.requestFocus();
        } catch (Throwable t) {
            Trace.err("live", "直播起播失败 " + ch.name, t);
            busy.setVisibility(View.GONE);
            showDead("起播失败：" + t.getClass().getSimpleName());
        }
    }

    private void playNext() {
        if (urls.isEmpty()) {
            return;
        }
        play((urlIdx + 1) % urls.size());
    }

    /** 上一台 / 下一台：只在同一个分组里循环，跨组用分组长按成本太高，先不做。 */
    private void switchChannel(int delta) {
        if (group == null || group.chs.isEmpty()) {
            return;
        }
        int n = group.chs.size();
        chIdx = ((chIdx + delta) % n + n) % n;
        if (!pickChannel()) {
            showDead("这个台没有可用的线路");
            return;
        }
        play(0);
    }

    private void showDead(String msg) {
        dead = true;
        busy.setVisibility(View.GONE);
        bigMsg.setText(msg);
        bigMsg.setVisibility(View.VISIBLE);
        if (ch != null) {
            topInfo.setText(ch.name + "　·　" + (urls.isEmpty() ? "无线路" : "全部线路不可用"));
        }
        bottomHint.setVisibility(View.VISIBLE);
    }

    private void updateInfo() {
        if (ch == null) {
            return;
        }
        topInfo.setText(ch.name + "　·　线路 " + (urlIdx + 1) + "/" + urls.size());
        topInfo.setVisibility(View.VISIBLE);
    }

    /** 起播成功后：信息条 5 秒淡出，遥控提示 4 秒淡出。 */
    private void restInfo() {
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 5000);
        bottomHint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBottom);
        ui.postDelayed(hideBottom, 4000);
    }

    private final Runnable hideBottom = new Runnable() {
        public void run() {
            if (!panelVisible) {
                bottomHint.setVisibility(View.GONE);
            }
        }
    };

    /** ==================== 按键 ==================== */

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
            if (urls.size() > 1) {
                int n = urls.size();
                play(((urlIdx - 1) % n + n) % n);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (urls.size() > 1) {
                playNext();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            // OK 键：画面卡住时手动重连当前线路
            play(urlIdx);
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (vv.isPlaying()) {
                vv.pause();
            } else {
                vv.start();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }
}

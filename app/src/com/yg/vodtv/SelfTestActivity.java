package com.yg.vodtv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自检页：不接电脑也能看清每一步通不通。
 * 依次跑：网络参数 → 代理端口 → 多源分类 → 海报下载 → 多源搜索 → 详情集数 → m3u8 首跳 → 分片首包。
 */
public final class SelfTestActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TextView> lines = new ArrayList<TextView>();

    private ScrollView scroll;
    private LinearLayout rows;
    private TextView summary;
    private TextView logTitle;
    private TextView logView;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.immersive(getWindow());
        Http.trustAll();
        buildUi();
        start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.pageBg());
        root.setPadding(Ui.px(56), Ui.px(30), Ui.px(56), Ui.px(20));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = Ui.text(this, "自检", 44, Ui.ACCENT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(t);
        bar.addView(Ui.text(this, "   逐步验证片源链路，全绿就说明能看", 22, Ui.FG_DIM));

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView again = chip("重新检测");
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!running) {
                    start();
                }
            }
        });
        bar.addView(again);

        TextView back = chip("返回");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.px(14);
        bar.addView(back, blp);

        root.addView(bar);

        summary = Ui.text(this, "准备中…", 26, Ui.FG_DIM);
        summary.setPadding(0, Ui.px(14), 0, Ui.px(10));
        root.addView(summary);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 错误日志：真机上出问题时，这里能直接看到原因，拍照即可反馈
        logTitle = Ui.text(this, "错误日志（0 条）", 24, Ui.FG_DIM);
        logTitle.setPadding(0, Ui.px(10), 0, Ui.px(6));
        root.addView(logTitle);
        logView = Ui.text(this, "（暂无）", 20, Ui.FG_DIM);
        logView.setTextIsSelectable(true);
        logView.setPadding(Ui.px(14), Ui.px(10), Ui.px(14), Ui.px(10));
        logView.setBackground(Ui.round(Ui.BG_CARD, 8, 0, 0));
        ScrollView logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(190)));

        setContentView(root);
        again.requestFocus();
    }

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 24, Ui.FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.px(22), Ui.px(11), Ui.px(22), Ui.px(11));
        t.setBackground(Ui.focusBg(Ui.BG_CARD, 8, Ui.ACCENT));
        return t;
    }

    /** ==================== 执行 ==================== */

    private void start() {
        running = true;
        lines.clear();
        rows.removeAllViews();
        summary.setText("检测中…");
        summary.setTextColor(Ui.FG_DIM);
        refreshLog();

        final long t0 = System.currentTimeMillis();
        io.execute(new Runnable() {
            public void run() {
                int pass = 0;
                int total = 0;

                // 0. 网络栈参数（IPv6 优先会导致 connect 打到 [::] 上超时）
                total++;
                String ipv4 = System.getProperty("java.net.preferIPv4Stack");
                if ("true".equals(ipv4)) {
                    pass++;
                    // 顺带报源表来源：内置 / 缓存 / 远端。两个数字不一样，
                    // 才说明远端 sources.json 真的生效了（换源不发版就靠它）。
                    add(true, "网络参数", "已强制 IPv4，连接层最多重试 "
                            + Http.attempts() + " 次；源表 " + SrcSync.status());
                } else {
                    add(false, "网络参数", "未强制 IPv4（" + ipv4
                            + "），IPv6 地址会先被尝试，连接可能超时；源表 " + SrcSync.status());
                }

                // 1. 本地代理
                total++;
                int port = Proxy.start();
                if (port > 0) {
                    pass++;
                    add(true, "本地代理", "监听 127.0.0.1:" + port);
                } else {
                    add(false, "本地代理", "18765-18800 全被占用，播放一定失败");
                }

                // 2. 分类列表（多源聚合）
                total++;
                Multi.Result mp = Multi.list(Src.CH_ACTION, 1);
                if (mp != null && mp.items.size() > 0) {
                    pass++;
                    int down = Multi.downCount();
                    add(true, "分类列表", "动作片 第1页聚合 " + mp.items.size() + " 条，"
                            + mp.okSources + " 个源有数据"
                            + (down > 0 ? "（" + down + " 个源已冷却）" : ""));
                } else {
                    add(false, "分类列表", "所有源都没返回数据，检查电视网络");
                    finishRun(pass, total, t0);
                    return;
                }

                // 3. 海报下载（首页第一张图能不能真的取到）
                total++;
                String posterUrl = mp.items.get(0).pic;
                String posterMsg = checkPoster(posterUrl);
                if (posterMsg == null) {
                    pass++;
                    add(true, "海报下载", Ui.directState() + "，取到图片 "
                            + (posterUrl.length() > 60 ? posterUrl.substring(0, 60) + "…" : posterUrl));
                } else {
                    add(false, "海报下载", posterMsg);
                }

                // 3.1 海报缓存状态（磁盘缓存是否真在工作、有没有超上限）
                // 以前这一步是无条件 pass，就算缓存涨到 500MB 也显示绿色，等于没测。
                // 现在按「实际占用 vs 上限」判定。
                total++;
                long cacheBytes = Ui.diskBytes();
                long cacheLimit = Ui.diskLimit();
                if (cacheBytes > cacheLimit) {
                    add(false, "海报缓存", Ui.cacheStat() + " —— 已超上限，清理没生效");
                } else {
                    pass++;
                    add(true, "海报缓存", Ui.cacheStat());
                }

                // 4. 搜索（多源 + 拼音）
                total++;
                List<Vod> sg = Multi.search("fanhua");
                if (sg != null && sg.size() > 0) {
                    pass++;
                    add(true, "搜索", "fanhua → " + sg.size() + " 条，首条「" + sg.get(0).name + "」");
                } else {
                    add(false, "搜索", "多源搜索无结果，搜索功能会不可用");
                }

                // 5. 详情 + 选集
                total++;
                int pid = mp.items.get(0).id;
                String pname = mp.items.get(0).name;
                Vod d = Api.detail(Src.byKey(mp.items.get(0).src), pid);
                if (d != null && d.epUrls.length > 0) {
                    pass++;
                    add(true, "详情与选集", "「" + pname + "」解析出 " + d.epUrls.length
                            + " 集，线路 " + (d.playFrom.length() > 0 ? d.playFrom : "未知"));
                } else {
                    add(false, "详情与选集", "详情没解析出可播地址（id=" + pid + "）");
                    finishRun(pass, total, t0);
                    return;
                }

                // 6. master playlist 经代理
                // v2.18：这一步改成先探「直播内置源」—— 老板真正在看的是这条链路。
                // 以前探的是公共采集源的点播 m3u8，那批源在这条电信家宽上成片失效，
                // 自检页就长期挂着一个红项，看着像 App 坏了，其实直播是好的。
                total++;
                String[] liveProbe = firstLive();
                String raw = liveProbe != null ? liveProbe[0] : d.epUrls[0];
                String rawName = liveProbe != null ? liveProbe[1] : "点播源";
                String local = Proxy.wrapPlaylist(raw);
                String master = Http.get(local, Api.REFERER);
                if (master != null && master.indexOf("#EXTM3U") >= 0) {
                    pass++;
                    String first = firstUri(master);
                    add(true, "播放列表首跳", rawName + "：" + (master.contains("#EXT-X-STREAM-INF")
                            ? "master 正常，子列表已改写到本地代理"
                            : "媒体列表正常，共 " + count(master, "#EXTINF") + " 个分片"));
                    if (first == null) {
                        add(false, "子列表地址", "master 里没找到子列表 URI");
                    } else {
                        // 6. 往下跳一层（master → 媒体列表 或 媒体列表 → 首分片）
                        total++;
                        if (first.indexOf("/hls/") >= 0) {
                            String media = Http.get(first, Api.REFERER);
                            if (media != null && media.indexOf("#EXTINF") >= 0) {
                                pass++;
                                add(true, "媒体列表", "解析出 " + count(media, "#EXTINF") + " 个分片");
                                String seg = firstUri(media);
                                if (seg != null) {
                                    total++;
                                    pass += checkSegment(seg);
                                }
                            } else {
                                add(false, "媒体列表", "取子列表失败");
                            }
                        } else {
                            pass += checkSegment(first);
                        }
                    }
                } else {
                    add(false, "播放列表首跳", rawName + " 经代理取 m3u8 失败，检查 Proxy/证书/Referer");
                }

                finishRun(pass, total, t0);
            }
        });
    }

    /** 拉一个分片的首包，确认真是 TS。返回 1 表示通过。 */
    private int checkSegment(String url) {
        byte[] head = Http.getBytes(url, Api.REFERER, 6000, 10000);
        if (head == null || head.length == 0) {
            add(false, "分片数据", "取分片失败，播放会黑屏");
            return 0;
        }
        boolean ts = head[0] == 0x47;
        if (ts) {
            add(true, "分片数据", "首包 0x47（合法 TS），" + head.length / 1024 + " KB");
            return 1;
        }
        add(false, "分片数据", "首字节 " + Integer.toHexString(head[0] & 0xFF)
                + "，不是 TS（可能取到的是错误页）");
        return 0;
    }

    /** 海报能不能真取到：走和列表页完全相同的三跳加载链。null 表示通过。 */
    private String checkPoster(String url) {
        long t = System.currentTimeMillis();
        String err = Ui.probe(url);
        long ms = System.currentTimeMillis() - t;
        if (err == null) {
            Trace.add("selftest", "海报 OK " + ms + "ms");
        }
        return err;
    }

    private void finishRun(final int pass, final int total, final long t0) {
        final long ms = System.currentTimeMillis() - t0;
        ui.post(new Runnable() {
            public void run() {
                running = false;
                boolean all = pass == total;
                summary.setText(all
                        ? "全部通过（" + pass + "/" + total + "，" + ms + " ms）— 可以正常看片"
                        : "有 " + (total - pass) + " 项没通过（" + pass + "/" + total + "，"
                                + ms + " ms）— 看下面标红的项");
                summary.setTextColor(all ? 0xFF5CD65C : 0xFFFF6B6B);
                refreshLog();
            }
        });
    }

    /** 把内存里的错误日志刷到屏幕，真机出问题时拍照即可反馈。 */
    private void refreshLog() {
        if (logView == null) {
            return;
        }
        int n = Trace.size();
        String body;
        if (n == 0) {
            body = Trace.tail(14);
            if (body.length() == 0) {
                body = "（暂无）";
            }
        } else {
            body = Trace.tail(14);
        }
        logTitle.setText("错误日志（" + n + " 条，显示最近 14 条）");
        logTitle.setTextColor(n > 0 ? 0xFFFFB4B4 : Ui.FG_DIM);
        logView.setText(body);
        logView.setTextColor(n > 0 ? 0xFFFFD0D0 : Ui.FG_DIM);
    }

    private void add(final boolean ok, final String name, final String detail) {
        ui.post(new Runnable() {
            public void run() {
                LinearLayout row = new LinearLayout(SelfTestActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, Ui.px(9), 0, Ui.px(9));

                TextView mark = Ui.text(SelfTestActivity.this, ok ? "通过" : "失败", 22,
                        ok ? 0xFF5CD65C : 0xFFFF6B6B);
                mark.setGravity(Gravity.CENTER);
                mark.setBackground(Ui.round(ok ? 0xFF16351C : 0xFF3C1A1A, 6,
                        1, ok ? 0xFF2E7D32 : 0xFF8E2A2A));
                mark.setPadding(Ui.px(12), Ui.px(5), Ui.px(12), Ui.px(5));
                row.addView(mark);

                TextView nm = Ui.text(SelfTestActivity.this, name, 26, Ui.FG);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.px(240),
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                nlp.leftMargin = Ui.px(18);
                row.addView(nm, nlp);

                TextView de = Ui.text(SelfTestActivity.this, detail, 24,
                        ok ? Ui.FG_DIM : 0xFFFFB4B4);
                de.setSingleLine(false);
                row.addView(de, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                rows.addView(row);
                lines.add(nm);
                scroll.post(new Runnable() {
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    /** ==================== 小工具 ==================== */

    /**
     * 取一台内置直播源的线路，用来做「播放列表首跳」的探针（v2.18）。
     * 返回 {线路, 台名}；没有内置台时返回 null，调用方退回点播源。
     */
    private String[] firstLive() {
        java.util.List<Live.Group> gs = Live.groups();
        for (int i = 0; i < gs.size(); i++) {
            Live.Group g = gs.get(i);
            if (g == null || g.chs == null) {
                continue;
            }
            for (int j = 0; j < g.chs.size(); j++) {
                Live.Ch c = g.chs.get(j);
                if (c != null && c.urls != null && !c.urls.isEmpty()) {
                    return new String[]{c.urls.get(0), g.name + " · " + c.name};
                }
            }
        }
        return null;
    }

    /** 取 m3u8 里第一条非注释、非标签的行。 */
    private static String firstUri(String text) {
        if (text == null) {
            return null;
        }
        String[] ls = text.split("\n");
        for (int i = 0; i < ls.length; i++) {
            String s = ls[i].trim();
            if (s.length() == 0 || s.startsWith("#")) {
                continue;
            }
            return s;
        }
        return null;
    }

    private static int count(String text, String key) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(key, i)) >= 0) {
            n++;
            i += key.length();
        }
        return n;
    }
}

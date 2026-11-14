package com.yg.vodtv;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相邻频道预热。
 *
 * 为什么需要：缓存只在「已经看过那一页」之后才有用，第一次切到隔壁频道照样
 * 要等 9 秒预算。这里在用户盯着当前频道看的时候，把左右各一个频道的首页
 * 提前算好写进 Cache，切过去基本是秒开。
 *
 * 为什么只预热两个、为什么要 sleep 400ms：电视的出口带宽和连接数都被
 * 抠得很紧（Multi.POOL 开 8 条就会被中间设备 reset，见 Multi 里的注释），
 * 再叠两三个频道的十个源会在用户正在翻当前页时把连接抢光，反而更卡。
 * 所以：单线程串行、两个频道、中间让 400ms。
 */
public final class Prefetch {

    /** 单线程：预取永远不许抢用户当前那页的并发。 */
    private static ExecutorService POOL;

    /** 已经排进队列的频道（含正在跑的），防止用户来回切 Tab 把同一个频道排三遍。 */
    private static final Set<Integer> INFLIGHT =
            Collections.synchronizedSet(new HashSet<Integer>());

    private Prefetch() {
    }

    private static synchronized ExecutorService pool() {
        if (POOL == null) {
            POOL = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "prefetch");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return POOL;
    }

    /**
     * 预热 cur 左右相邻频道的首页。
     *
     * @param channels TAB_TYPE 数组（统一频道号）
     * @param cur      当前选中的下标；越界或 null 时直接返回
     */
    public static void warm(final int[] channels, final int cur) {
        try {
            if (channels == null || cur < 0 || cur >= channels.length) {
                return;
            }
            final int prev = cur > 0 ? channels[cur - 1] : -1;
            final int next = cur + 1 < channels.length ? channels[cur + 1] : -1;
            // 去重（同一个频道号在 TAB_TYPE 里理论上唯一，但别指望它）
            if (prev >= 0 && prev != channels[cur]) {
                submit(prev);
            }
            if (next >= 0 && next != channels[cur] && next != prev) {
                submit(next);
            }
        } catch (Throwable t) {
            // 预取失败绝不影响主流程
        }
    }

    private static void submit(final int ch) {
        if (!INFLIGHT.add(ch)) {
            return; // 已经排过/正在跑
        }
        try {
            pool().execute(new Runnable() {
                public void run() {
                    try {
                        // 有缓存就不用再打一遍网络（哪怕内容旧，也比等 9 秒强）
                        if (Cache.get(ch, 1) != null) {
                            Trace.add("prefetch", "频道 " + ch + " 已有缓存，跳过");
                            return;
                        }
                        sleep(400);
                        long t0 = System.currentTimeMillis();
                        Multi.Result r = Multi.list(ch, 1);
                        if (r == null || r.items.isEmpty()
                                || r.raw == null || r.raw.length() <= 20) {
                            Trace.add("prefetch", "频道 " + ch + " 预热失败（无数据）");
                            return;
                        }
                        Cache.put(ch, 1, r.raw);
                        Trace.add("prefetch", "预热频道 " + ch + " 完成 "
                                + r.items.size() + " 条 / "
                                + (r.raw.length() / 1024) + "KB / "
                                + (System.currentTimeMillis() - t0) + "ms");
                    } catch (Throwable t) {
                        Trace.err("prefetch", "频道 " + ch + " 预热异常", t);
                    } finally {
                        INFLIGHT.remove(ch);
                    }
                }
            });
        } catch (Throwable t) {
            INFLIGHT.remove(ch);
            Trace.err("prefetch", "入队失败 ch=" + ch, t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 进播放页/退出时调用：把还在排队的预取全部掐掉，别和播放抢带宽。 */
    public static void cancel() {
        try {
            synchronized (Prefetch.class) {
                if (POOL != null) {
                    POOL.shutdownNow();
                    POOL = null; // 下次 warm 会重新建一条
                }
            }
            INFLIGHT.clear();
        } catch (Throwable t) {
            // 忽略
        }
    }
}

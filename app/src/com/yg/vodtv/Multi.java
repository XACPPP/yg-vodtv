package com.yg.vodtv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 多源聚合：并行打十个采集源，谁先回谁上屏，单个源挂了不影响整墙。
 *
 * 设计要点：
 * 1) 不等待全部完成 —— 用 latch.await(预算)，到点就用手上已有的结果，
 *    电视上网络慢，等齐十个源要十几秒，体验不可接受。
 * 2) 结果按「源优先级」轮转交织，避免首屏全是同一个站的老片。
 * 3) 按「片名 + 年份」去重，同一部剧多个源只留一个（简介更全的优先）。
 */
public final class Multi {

    /** 单个页最多展示多少条（7 列 × 12 行）。 */
    private static final int PAGE_CAP = 84;
    /** 分类页等多久就开始渲染。 */
    private static final long LIST_BUDGET = 7000;
    /** 搜索是用户主动操作，可以多等一点。 */
    private static final long SEARCH_BUDGET = 8000;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(8);

    private Multi() {
    }

    public static final class Result {
        public final List<Vod> items = new ArrayList<Vod>();
        /** 这一页的页码（聚合结果没有单一源页码，就用请求时那一页）。 */
        public int page = 1;
        public int pagecount = 1;
        /** 有几个源回了数据。 */
        public int okSources = 0;
    }

    /** 按统一频道取一页，多源并行合并。 */
    public static Result list(final int ch, final int pg) {
        final List<Src> srcs = new ArrayList<Src>();
        for (int i = 0; i < Src.all().size(); i++) {
            if (Src.all().get(i).has(ch)) {
                srcs.add(Src.all().get(i));
            }
        }
        final Api.Page[] got = new Api.Page[srcs.size()];
        final CountDownLatch latch = new CountDownLatch(srcs.size());
        for (int i = 0; i < srcs.size(); i++) {
            final Src s = srcs.get(i);
            final int idx = i;
            POOL.execute(new Runnable() {
                public void run() {
                    try {
                        got[idx] = Api.list(s, ch, pg);
                    } catch (Throwable t) {
                        Trace.err("multi", "源 " + s.key + " 取分类失败 ch=" + ch, t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await(LIST_BUDGET, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
        }
        return merge(srcs, got, pg);
    }

    /** 搜索：中文走各源 wd=，纯字母/拼音走 wsyzy 联想，两路合并。 */
    public static List<Vod> search(final String kw) {
        if (kw == null || kw.trim().length() == 0) {
            return new ArrayList<Vod>();
        }
        final String k = kw.trim();
        final List<Src> srcs = Src.all();
        final List<Vod>[] got = new List[srcs.size() + 1];
        final CountDownLatch latch = new CountDownLatch(srcs.size() + 1);

        for (int i = 0; i < srcs.size(); i++) {
            final Src s = srcs.get(i);
            final int idx = i;
            POOL.execute(new Runnable() {
                public void run() {
                    try {
                        got[idx] = Api.wd(s, k);
                    } catch (Throwable t) {
                        Trace.err("multi", "源 " + s.key + " 搜索失败", t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        // 拼音联想单开一格，只有 wsyzy 支持
        POOL.execute(new Runnable() {
            public void run() {
                try {
                    got[srcs.size()] = Api.suggest(k);
                } catch (Throwable t) {
                    Trace.err("multi", "拼音联想失败", t);
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(SEARCH_BUDGET, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
        }

        List<Vod> out = new ArrayList<Vod>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < got.length; i++) {
            if (got[i] == null) {
                continue;
            }
            for (int j = 0; j < got[i].size(); j++) {
                Vod v = got[i].get(j);
                String kk = key(v);
                if (kk.length() > 0 && seen.add(kk)) {
                    out.add(v);
                }
            }
        }
        Api.rank(out, k);
        Trace.add("multi", "搜索 " + k + " → " + out.size() + " 条（" + count(got) + " 个源有结果）");
        return out;
    }

    private static int count(List<Vod>[] got) {
        int n = 0;
        for (int i = 0; i < got.length; i++) {
            if (got[i] != null && !got[i].isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 轮转交织 + 去重 + 截断。 */
    private static Result merge(List<Src> srcs, Api.Page[] got, int pg) {
        Result r = new Result();
        r.page = pg;
        Set<String> seen = new HashSet<String>();
        int maxPage = 1;
        for (int i = 0; i < got.length; i++) {
            if (got[i] != null && !got[i].items.isEmpty()) {
                r.okSources++;
                if (got[i].pagecount > maxPage) {
                    maxPage = got[i].pagecount;
                }
            }
        }
        r.pagecount = Math.min(maxPage, 400);

        // 第 n 轮取每个源的第 n 条，保证首屏混着每个站的片
        boolean added = true;
        for (int round = 0; added && r.items.size() < PAGE_CAP; round++) {
            added = false;
            for (int i = 0; i < got.length && r.items.size() < PAGE_CAP; i++) {
                if (got[i] == null || round >= got[i].items.size()) {
                    continue;
                }
                Vod v = got[i].items.get(round);
                String k = key(v);
                if (k.length() == 0 || !seen.add(k)) {
                    continue;
                }
                r.items.add(v);
                added = true;
            }
        }
        Trace.add("multi", "第 " + pg + " 页聚合 " + r.items.size() + " 条，"
                + r.okSources + "/" + got.length + " 个源有数据");
        return r;
    }

    /** 去重键：片名 + 年份（年份缺失就只看片名）。 */
    private static String key(Vod v) {
        if (v == null || v.name == null || v.name.trim().length() == 0) {
            return "";
        }
        String n = v.name.trim();
        n = n.replace(' ', '\u0000').replace('\u3000', '\u0000');
        return n + "|" + (v.year == null ? "" : v.year.trim());
    }

    /**
     * 补简介：详情接口没给简介时，拿片名去其它源搜同名，取最像的那部的简介。
     * 同步调用，务必在后台线程里跑。
     */
    public static void fillIntro(Vod v) {
        if (v == null || v.intro().length() > 20) {
            return;
        }
        List<Vod> list = search(v.name);
        if (list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size() && i < 6; i++) {
            Vod o = list.get(i);
            if (o.intro().length() <= 20) {
                continue;
            }
            if (v.year.length() > 0 && o.year.length() > 0 && !v.year.equals(o.year)) {
                continue;
            }
            v.blurb = o.blurb;
            v.content = o.content;
            if (v.actor.length() == 0) {
                v.actor = o.actor;
            }
            if (v.director.length() == 0) {
                v.director = o.director;
            }
            if (v.area.length() == 0) {
                v.area = o.area;
            }
            if (v.lang.length() == 0) {
                v.lang = o.lang;
            }
            Trace.add("multi", "简介回填成功「" + v.name + "」← " + o.src);
            return;
        }
    }
}

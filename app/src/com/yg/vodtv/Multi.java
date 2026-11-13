package com.yg.vodtv;

import org.json.JSONArray;
import org.json.JSONObject;

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
    /**
     * 分类页等多久就开始渲染。
     * 加上了连接层重试（每次失败最多多花 1.2s），预算从 7s 放宽到 9s，
     * 否则被 reset 的源还没轮到重试就被截断了。
     */
    private static final long LIST_BUDGET = 9000;
    /** 搜索是用户主动操作，可以多等一点。 */
    private static final long SEARCH_BUDGET = 10000;

    /** 搜索拿到几个源的结果就算够（够排序出个像样的列表了）。 */
    private static final int SEARCH_ENOUGH_N = 5;

    /**
     * 分类页至少等到几个源回来才肯出画面。
     * 6 个能保证首屏混着六家站的片；少于这个就再等等，
     * 但如果预算到了还没凑够，也照样出画面（有总比没有强）。
     */
    private static final int MIN_SRC = 6;

    /**
     * 并发度。
     *
     * 原来开 8 条，实测电视上十个源同一瞬间各发一次 TLS 握手，
     * 会被中间设备当成异常流量 reset（日志里 modu/ysxq/ikun 三条
     * SocketException(Connection reset) 基本是同一秒出现的）。
     * 收到 6 条既不会挤爆连接，也还在预算内跑得完。
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    /**
     * 源级熔断：某个源连续失败就冷处理一段时间。
     *
     * 实测一个源彻底挂掉（比如 ikun 现在握手就断）时，每个频道都要白等它
     * 3 次重试共 ~6 秒，港剧/港台综艺直接顶到 9 秒预算上限。
     * 记一次失败、冷却 3 分钟，期间直接跳过它，用户完全感觉不到。
     */
    private static final long COOLDOWN = 180000;
    private static final java.util.Map<String, Long> DOWN =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private static boolean isDown(Src s) {
        Long t = DOWN.get(s.key);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t > COOLDOWN) {
            DOWN.remove(s.key);
            return false;
        }
        return true;
    }

    private static void markDown(Src s) {
        if (DOWN.put(s.key, System.currentTimeMillis()) == null) {
            Trace.add("multi", "源 " + s.key + " 连续失败，冷却 3 分钟不再请求");
        }
    }

    /** 自检页/调试用：现在有几个源处于冷却中。 */
    public static int downCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Long t : DOWN.values()) {
            if (now - t <= COOLDOWN) {
                n++;
            }
        }
        return n;
    }

    private Multi() {
    }

    public static final class Result {
        public final List<Vod> items = new ArrayList<Vod>();
        /** 这一页的页码（聚合结果没有单一源页码，就用请求时那一页）。 */
        public int page = 1;
        public int pagecount = 1;
        /** 有几个源回了数据。 */
        public int okSources = 0;
        /**
         * 这一页的紧凑 JSON 文本（见 {@link Multi#encode(Result)}），空列表时为空串。
         * 给 Cache 落盘、给 Prefetch 预热用；上屏完全不需要它。
         */
        public String raw = "";
    }

    /* ==================== 缓存序列化 ==================== */

    /**
     * 把一个 Result 序列化成紧凑 JSON 文本，供 Cache 落盘。
     *
     * 为什么手写拼接而不用 JSONObject.put：这套代码要被 test/harness 的
     * org.json 替身编译，替身只有解析能力（5 个 opt*），没有任何写方法。
     * 所以转义、逗号、括号全部手写。
     *
     * 不序列化 epNames/epUrls：列表页一个字段都用不到，而且 84 条 × 几十集的
     * 播放地址会让单页 JSON 涨到几百 KB；详情页本来就会按 id 重新拉一次。
     */
    public static String encode(Result r) {
        if (r == null || r.items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append("{\"v\":1,\"page\":").append(r.page)
                .append(",\"pagecount\":").append(r.pagecount)
                .append(",\"ok\":").append(r.okSources)
                .append(",\"items\":[");
        boolean first = true;
        for (int i = 0; i < r.items.size(); i++) {
            Vod v = r.items.get(i);
            if (v == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"");
            sb.append(v.id);
            sb.append("\",\"s\":\"");
            esc(sb, v.src);
            sb.append("\",\"n\":\"");
            esc(sb, v.name);
            sb.append("\",\"p\":\"");
            esc(sb, v.pic);
            sb.append("\",\"r\":\"");
            esc(sb, v.remarks);
            sb.append("\",\"t\":\"");
            esc(sb, v.typeName);
            sb.append("\",\"y\":\"");
            esc(sb, v.year);
            sb.append("\",\"a\":\"");
            esc(sb, v.area);
            sb.append("\",\"pf\":\"");
            esc(sb, v.playFrom);
            sb.append("\",\"pu\":\"");
            esc(sb, v.playUrl);
            sb.append("\",\"e\":\"");
            esc(sb, v.en);
            sb.append("\",\"ac\":\"");
            esc(sb, v.actor);
            sb.append("\",\"d\":\"");
            esc(sb, v.director);
            sb.append("\",\"l\":\"");
            esc(sb, v.lang);
            sb.append("\",\"b\":\"");
            esc(sb, v.blurb);
            sb.append("\",\"c\":\"");
            esc(sb, v.content);
            sb.append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 写 JSON 字符串内容（调用方已经写好开头的引号）。
     * 规则：`"` → `\"`、`\` → `\\`、换行/回车/制表 → `\n`/`\r`/`\t`、
     * 其余 < 0x20 的控制字符换成空格（JSON 不允许裸控制字符）。null 输出空串。
     */
    private static void esc(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    /**
     * 把 {@link #encode(Result)} 的文本还原成 Result；任何异常都吞掉，返回 null。
     *
     * 只走替身也支持的那几个读方法：new JSONObject(text) + optInt/optString/
     * optJSONArray + JSONArray.length()/optJSONObject(i)。
     * 特别注意：**不用** optJSONObject(String)、也不用 new JSONArray(String)
     * （替身 JSONArray 没有 String 构造）。
     *
     * @param ch 仅用于日志，解码不依赖频道号（频道号已在文件名里）
     */
    public static Result decode(String raw, int ch) {
        if (raw == null || raw.length() < 20) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("v", 1) > 1) {
                return null; // 将来换格式直接当不存在，让上层重新拉
            }
            JSONArray arr = o.optJSONArray("items");
            if (arr == null) {
                return null;
            }
            Result r = new Result();
            r.page = o.optInt("page", 1);
            if (r.page < 1) {
                r.page = 1;
            }
            r.pagecount = o.optInt("pagecount", 1);
            if (r.pagecount < 1) {
                r.pagecount = 1;
            }
            r.okSources = o.optInt("ok", 0);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                Vod v = new Vod();
                v.id = it.optInt("id", 0);
                v.src = it.optString("s", "");
                v.name = it.optString("n", "");
                v.pic = it.optString("p", "");
                v.remarks = it.optString("r", "");
                v.typeName = it.optString("t", "");
                v.year = it.optString("y", "");
                v.area = it.optString("a", "");
                v.playFrom = it.optString("pf", "");
                v.playUrl = it.optString("pu", "");
                v.en = it.optString("e", "");
                v.actor = it.optString("ac", "");
                v.director = it.optString("d", "");
                v.lang = it.optString("l", "");
                v.blurb = it.optString("b", "");
                v.content = it.optString("c", "");
                // 刻意不 parseEpisodes()：列表页只画卡片，选集在详情页重新拉，
                // 84 条各切一遍 $$$/# 纯属白烧电视的 CPU。
                if (v.name.length() > 0) {
                    r.items.add(v);
                }
            }
            if (r.items.isEmpty()) {
                return null;
            }
            r.raw = raw;
            return r;
        } catch (Throwable t) {
            Trace.err("multi", "缓存解析失败 ch=" + ch, t);
            return null;
        }
    }

    /** 按统一频道取一页，多源并行合并。 */
    public static Result list(final int ch, final int pg) {
        // 只取一次快照：Src.all() 现在返回副本，循环里反复调会拿到不同副本，
        // 一旦后台换表把数量改小，索引就会越界。
        final List<Src> snap = Src.all();
        final List<Src> srcs = new ArrayList<Src>();
        for (int i = 0; i < snap.size(); i++) {
            Src s = snap.get(i);
            if (s.has(ch) && !isDown(s)) {
                srcs.add(s);
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
                        if (got[idx] == null || got[idx].failed) {
                            markDown(s);
                        }
                    } catch (Throwable t) {
                        markDown(s);
                        Trace.err("multi", "源 " + s.key + " 取分类失败 ch=" + ch, t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        // 不再死等满预算：边等边看，手上够了就立刻出画面。
        // 电视上快源 1 秒内就回来了，慢源再等它 8 秒毫无意义。
        awaitEnough(latch, got, LIST_BUDGET);
        Result r = merge(srcs, got, pg);
        // 空列表不 encode（拼接 84 条字符串纯属白烧 CPU，而且空页也没什么好缓存的）
        if (!r.items.isEmpty()) {
            r.raw = encode(r);
        }
        return r;
    }

    /** 搜索侧的「够用就走」，判定依据是已经拿到多少个源的结果。 */
    private static void awaitSearch(CountDownLatch latch, List<Vod>[] got,
            long timeout, int enoughSources) {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            try {
                if (latch.await(80, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (Throwable t) {
                return;
            }
            int n = 0;
            for (int i = 0; i < got.length; i++) {
                if (got[i] != null && !got[i].isEmpty()) {
                    n++;
                }
            }
            if (n >= enoughSources) {
                return;
            }
        }
    }

    /**
     * 等到「够用了」就返回，而不是等满 timeout。
     *
     * 判据按**源数**而不是条数：两个源各自 30/20 条就能凑够 50 条，
     * 但那样整墙就只剩两家站的片，多源聚合的意义全没了。
     * 所以要求至少 MIN_SRC 个源回来了，或者预算用尽。
     */
    private static void awaitEnough(CountDownLatch latch, Api.Page[] got, long timeout) {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            try {
                if (latch.await(60, TimeUnit.MILLISECONDS)) {
                    return; // 全部源都回来了
                }
            } catch (Throwable t) {
                return;
            }
            int n = 0;
            for (int i = 0; i < got.length; i++) {
                if (got[i] != null && !got[i].items.isEmpty()) {
                    n++;
                }
            }
            if (n >= MIN_SRC) {
                return; // 够热闹了，剩下的慢源不等
            }
        }
    }

    /** 搜索：中文走各源 wd=，纯字母/拼音走 wsyzy 联想，两路合并。 */
    public static List<Vod> search(final String kw) {
        if (kw == null || kw.trim().length() == 0) {
            return new ArrayList<Vod>();
        }
        final String k = kw.trim();
        final List<Src> snap = Src.all();
        final List<Src> srcs = new ArrayList<Src>();
        for (int i = 0; i < snap.size(); i++) {
            Src s = snap.get(i);
            if (!isDown(s)) {
                srcs.add(s);
            }
        }
        final boolean ascii = isAscii(k);
        final List<Vod>[] got = new List[srcs.size() + 1];
        final CountDownLatch latch = new CountDownLatch(srcs.size() + 1);

        for (int i = 0; i < srcs.size(); i++) {
            final Src s = srcs.get(i);
            final int idx = i;
            POOL.execute(new Runnable() {
                public void run() {
                    try {
                        // 纯字母/全拼：各源的 wd= 实测全 0（拼音根本不进库），
                        // 直接问 suggest 又快又准；中文才走 wd=
                        got[idx] = ascii ? Api.suggest(s, k) : Api.wd(s, k);
                        if (got[idx] == null && !"wsyzy".equals(s.key)) {
                            markDown(s);
                        }
                    } catch (Throwable t) {
                        markDown(s);
                        Trace.err("multi", "源 " + s.key + " 搜索失败", t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        // 拼音联想：wsyzy 那条留下当兜底，其余源各自再问一次 suggest
        // （实测 ffzy/360zy/yhzy/maoyan/bfzy/modu/ikun/zuidazy/jszyapi/dytt 都能用，
        //  输 xiaochengliangfang 直接命中「小城良方」；cmov 空、uku 非 JSON、ysxq 超时，静默跳过）
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

        // 搜索也一样：够铺一屏就不等慢源了
        awaitSearch(latch, got, SEARCH_BUDGET, SEARCH_ENOUGH_N);

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
        // 剪掉「只是碰巧带这几个字」的噪声：搜「逐玉」不该出现枭雄、逐爱、没问题
        out = Api.prune(out, k);
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

    /** 关键词是不是纯字母/数字（全拼、缩写）——这类走各源 wd= 基本查不到，得靠 suggest。 */
    private static boolean isAscii(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127) {
                return false;
            }
        }
        return true;
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

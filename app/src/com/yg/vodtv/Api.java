package com.yg.vodtv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 采集源接口封装（苹果CMS 标准协议）。
 *
 * 多源之后这里的方法都多了一个 Src 参数：type_id 是每个源自己的一套编号，
 * 不能跨源混用，所以「取哪一类」由 Src.type(频道) 翻译后再发请求。
 */
public final class Api {

    /** 取消掉的那一条：单源时代的站点常量，保留给自检和拼音联想用。 */
    public static final String SITE = "https://wsyzy.cc";
    public static final String API = SITE + "/api.php/provide/vod/";
    public static final String REFERER = SITE + "/";

    private Api() {
    }

    public static final class Page {
        public final List<Vod> items = new ArrayList<Vod>();
        public int page = 1;
        public int pagecount = 1;
        public int total = 0;
    }

    /** ==================== 单源取数 ==================== */

    public static Page list(Src s, int ch, int pg) {
        if (s == null) {
            return new Page();
        }
        StringBuilder sb = new StringBuilder(s.api);
        sb.append("?ac=detail&pg=").append(pg);
        int t = s.type(ch);
        if (t > 0) {
            sb.append("&t=").append(t);
        }
        // 8 秒连接 / 10 秒读取：够取 87KB 的整页，又不至于让单个慢源拖死整次聚合
        return parsePage(Http.get(sb.toString(), s.referer, 8000, 10000),
                "list " + s.key + " ch=" + ch + " pg=" + pg, s.key);
    }

    public static Vod detail(Src s, int id) {
        if (s == null) {
            return null;
        }
        Page p = parsePage(Http.get(s.api + "?ac=detail&ids=" + id, s.referer),
                "detail " + s.key + " id=" + id, s.key);
        if (p.items.isEmpty()) {
            Trace.add("api", "详情为空 " + s.key + " id=" + id);
            return null;
        }
        return p.items.get(0);
    }

    /** 中文关键词全文搜索（wsyzy 不支持，会返回非 JSON，这里当成空结果）。 */
    public static List<Vod> wd(Src s, String kw) {
        List<Vod> out = new ArrayList<Vod>();
        if (s == null || kw == null || kw.trim().length() == 0) {
            return out;
        }
        try {
            String url = s.api + "?ac=detail&wd=" + URLEncoder.encode(kw.trim(), "UTF-8");
            String body = Http.get(url, s.referer, 6000, 9000);
            if (body == null) {
                return out;
            }
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Vod v = Vod.from(arr.optJSONObject(i), s.key);
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
        } catch (Throwable t) {
            // wsyzy 这类不支持 wd 的源会返回纯文本，属于预期情况，不打日志噪音
            Trace.add("api", "wd 不支持 " + s.key);
        }
        return out;
    }

    /** 拼音联想：只有 wsyzy 的 index.php/ajax/suggest 支持（返回精简条目，需再查详情）。 */
    public static List<Vod> suggest(String kw) {
        List<Vod> out = new ArrayList<Vod>();
        try {
            String url = SITE + "/index.php/ajax/suggest?mid=1&wd=" + URLEncoder.encode(kw, "UTF-8");
            String body = Http.get(url, REFERER);
            if (body == null) {
                return out;
            }
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Vod v = Vod.from(arr.optJSONObject(i), "wsyzy");
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
            rank(out, kw);
            Trace.add("api", "联想 " + kw + " → " + out.size() + " 条"
                    + (out.isEmpty() ? "" : "，首条「" + out.get(0).name + "」"));
        } catch (Throwable t) {
            Trace.err("api", "联想解析失败 " + kw, t);
        }
        return out;
    }

    /** ==================== 兼容单源老接口（自检页 / harness 用） ==================== */

    public static Page list(int type, int pg) {
        StringBuilder sb = new StringBuilder(API);
        sb.append("?ac=detail&pg=").append(pg);
        if (type > 0) {
            sb.append("&t=").append(type);
        }
        return parsePage(Http.get(sb.toString(), REFERER), "list t=" + type + " pg=" + pg, "wsyzy");
    }

    public static Vod detail(int id) {
        Page p = parsePage(Http.get(API + "?ac=detail&ids=" + id, REFERER),
                "detail id=" + id, "wsyzy");
        if (p.items.isEmpty()) {
            Trace.add("api", "详情为空 id=" + id);
            return null;
        }
        return p.items.get(0);
    }

    /** ==================== 排序 ==================== */

    /**
     * 搜索结果是「子串模糊匹配」：搜 fanhua 会先吐出一堆「万倍返还」。
     * 按「名称/拼音 slug 与关键词的贴合度」重排，把真正的《繁花》顶到前面。
     */
    public static void rank(List<Vod> list, String kw) {
        final String k = kw == null ? "" : kw.trim().toLowerCase(Locale.US);
        if (k.length() == 0 || list.size() < 2) {
            return;
        }
        final java.util.HashMap<Vod, Integer> sc = new java.util.HashMap<Vod, Integer>();
        for (int i = 0; i < list.size(); i++) {
            Vod v = list.get(i);
            sc.put(v, Integer.valueOf(score(v, k)));
        }
        java.util.Collections.sort(list, new java.util.Comparator<Vod>() {
            public int compare(Vod a, Vod b) {
                int sa = sc.containsKey(a) ? sc.get(a).intValue() : 0;
                int sb = sc.containsKey(b) ? sc.get(b).intValue() : 0;
                if (sa != sb) {
                    return sb - sa;
                }
                int la = a.name == null ? 0 : a.name.length();
                int lb = b.name == null ? 0 : b.name.length();
                return la - lb;
            }
        });
    }

    private static int score(Vod v, String k) {
        String nm = v.name == null ? "" : v.name.toLowerCase(Locale.US);
        String en = v.en == null ? "" : v.en.toLowerCase(Locale.US);
        int s;
        if (nm.equals(k) || en.equals(k)) {
            s = 1000;
        } else if (nm.startsWith(k)) {
            s = 800;
        } else if (en.startsWith(k)) {
            s = 760;
        } else if (nm.contains(k)) {
            s = 500;
        } else if (en.contains(k)) {
            s = 300;
        } else {
            s = 100;
        }
        // 关键词在标题里占比越高越像正主：《繁花1990》优于《重生的我一路繁花》
        if (nm.length() > 0) {
            s += Math.max(0, 60 - Math.min(60, nm.length() / 2));
        }
        return s;
    }

    /** ==================== 解析 ==================== */

    public static Page parsePage(String body) {
        return parsePage(body, "page", "");
    }

    public static Page parsePage(String body, String what) {
        return parsePage(body, what, "");
    }

    public static Page parsePage(String body, String what, String src) {
        Page p = new Page();
        if (body == null) {
            Trace.add("api", "无响应 " + what);
            return p;
        }
        try {
            JSONObject o = new JSONObject(body);
            p.page = o.optInt("page", 1);
            p.pagecount = Math.max(1, o.optInt("pagecount", 1));
            p.total = o.optInt("total", 0);
            JSONArray arr = o.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Vod v = Vod.from(arr.optJSONObject(i), src);
                    if (v != null) {
                        p.items.add(v);
                    }
                }
            }
            if (p.items.isEmpty()) {
                Trace.add("api", "空列表 " + what + " len=" + body.length());
            }
        } catch (Throwable t) {
            // 部分分类返回非 JSON，容错为空页
            Trace.err("api", "JSON 解析失败 " + what + " 头部=" + head(body), t);
        }
        return p;
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}

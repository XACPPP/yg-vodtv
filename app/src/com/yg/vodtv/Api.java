package com.yg.vodtv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 片源接口封装。
 * 搜索：suggest 只支持关键词联想（不支持 wd 全量搜索），必须先取 id 再查详情。
 * 分类：t=<type_id> 过滤；不带 t 返回全站最新。
 */
public final class Api {

    public static final String SITE = "https://wsyzy.cc";
    /**
     * 用主站域名走接口，而不是 api.wsyzy.net：
     * 两者返回一致，但少一个域名就少一次 TLS 握手与一套证书链，
     * Android 5.1 上这一步最容易出问题（实测 api.wsyzy.net 偶发 522）。
     */
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

    public static Page list(int type, int pg) {
        StringBuilder sb = new StringBuilder(API);
        sb.append("?ac=detail&pg=").append(pg);
        if (type > 0) {
            sb.append("&t=").append(type);
        }
        return parsePage(Http.get(sb.toString(), REFERER), "list t=" + type + " pg=" + pg);
    }

    public static Vod detail(int id) {
        Page p = parsePage(Http.get(API + "?ac=detail&ids=" + id, REFERER), "detail id=" + id);
        if (p.items.isEmpty()) {
            Trace.add("api", "详情为空 id=" + id);
            return null;
        }
        return p.items.get(0);
    }

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
                    Vod v = Vod.from(arr.optJSONObject(i));
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
            rank(out, kw);
            Trace.add("api", "搜索 " + kw + " → " + out.size() + " 条"
                    + (out.isEmpty() ? "" : "，首条「" + out.get(0).name + "」"));
        } catch (Throwable t) {
            Trace.err("api", "搜索解析失败 " + kw, t);
        }
        return out;
    }

    /**
     * suggest 是「子串模糊匹配」：搜 fanhua 会先吐出一堆「万倍返还」（fanhuan 里含 fanhua）。
     * 这里按「名称/拼音 slug 与关键词的贴合度」重排，把真正的《繁花》顶到前面。
     */
    static void rank(List<Vod> list, String kw) {
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

    public static Page parsePage(String body) {
        return parsePage(body, "page");
    }

    public static Page parsePage(String body, String what) {
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
                    Vod v = Vod.from(arr.optJSONObject(i));
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

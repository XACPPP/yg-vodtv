package com.yg.vodtv;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 影片数据模型 + 选集解析。 */
public final class Vod {

    public int id;
    /** 来自哪个采集源（Src.key）：不同源 vod_id 会撞，存取播放位置、取详情都靠它区分。 */
    public String src = "";
    public String name = "";
    public String pic = "";
    public String remarks = "";
    public String typeName = "";
    public String year = "";
    public String area = "";
    public String playFrom = "";
    public String playUrl = "";
    /** 拼音 slug（suggest 接口返回的 en），用来把「fanhua」这类全拼搜准。 */
    public String en = "";
    /** ===== 以下为片源自带的刮削信息（详情接口 ac=detail 才有） ===== */
    public String actor = "";
    public String director = "";
    public String lang = "";
    /** 一句话简介。 */
    public String blurb = "";
    /** 长简介，带 HTML 标签（p / br），展示前要清洗。 */
    public String content = "";
    public String[] epNames = new String[0];
    public String[] epUrls = new String[0];

    public static Vod from(JSONObject o) {
        return from(o, "");
    }

    public static Vod from(JSONObject o, String src) {
        if (o == null) {
            return null;
        }
        Vod v = new Vod();
        v.src = src == null ? "" : src;
        v.id = o.optInt("vod_id", o.optInt("id", 0));
        v.name = o.optString("vod_name", o.optString("name", ""));
        v.pic = normPic(o.optString("vod_pic", o.optString("pic", "")), baseOf(v.src));
        v.remarks = o.optString("vod_remarks", "");
        v.typeName = o.optString("vod_class", o.optString("type_name", ""));
        v.year = o.optString("vod_year", "");
        v.area = o.optString("vod_area", "");
        v.playFrom = o.optString("vod_play_from", "");
        v.playUrl = o.optString("vod_play_url", "");
        v.en = o.optString("en", o.optString("vod_en", ""));
        v.actor = o.optString("vod_actor", "");
        v.director = o.optString("vod_director", "");
        v.lang = o.optString("vod_lang", "");
        v.blurb = o.optString("vod_blurb", "");
        v.content = o.optString("vod_content", "");
        v.parseEpisodes();
        return v.name.length() == 0 ? null : v;
    }

    /**
     * 海报地址规范化。
     *
     * 历史坑（v2.0 的 bug）：以前这里对任何非绝对地址一律返回空串，理由是
     * 「maoyan 有 13% 的 vod_pic 是相对路径，拼哪个域名都 404」。
     * 那个结论只对 maoyan 成立，却把 bfzy 整条联想链路打死了：
     * bfzy 的 suggest 接口返回的 pic 100% 是 `/upload/vod/...` 这种以斜杠开头的
     * 站内路径（实测 20/20），拼上接口自己的域名 https://bfzyapi.com 就能 200
     * 拿到真图（15892 字节 RIFF/WebP）。判空 = 搜索结果里 bfzy 的条目全没有封面。
     *
     * 现在的规则：
     * - `//host/x`   → 补 https:
     * - `http(s)://` → 原样
     * - `/x`         → 拼 base + `/x`（base 为该源的 scheme://host）
     * - `x/y`        → 拼 base + `/x/y`
     * - 没有 base 可用（早期单源代码、或 base 解析失败）时，仍然判空，
     *   避免客户端拿相对地址去解析、白等三跳超时。
     */
    private static String normPic(String p, String base) {
        if (p == null) {
            return "";
        }
        String s = p.trim();
        if (s.length() == 0) {
            return "";
        }
        if (s.startsWith("//")) {
            return "https:" + s;
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        if (base == null || base.length() == 0) {
            return "";
        }
        if (s.startsWith("/")) {
            return base + s;
        }
        return base + "/" + s;
    }

    /** 从源 key 查出它的 scheme://host，供相对海报地址拼接。查不到返回空串。 */
    private static String baseOf(String srcKey) {
        if (srcKey == null || srcKey.length() == 0) {
            return "";
        }
        Src s = Src.byKey(srcKey);
        if (s == null || s.api == null) {
            return "";
        }
        return Src.root(s.api);
    }

    public boolean hasEpisodes() {
        return epUrls != null && epUrls.length > 0;
    }

    /** 简介：优先长简介（去掉 HTML），没有就退回一句话简介。 */
    public String intro() {
        String s = plain(content);
        if (s.length() == 0) {
            s = plain(blurb);
        }
        return s;
    }

    /** 把 vod_content 里的 HTML 去掉：块标签换行、其余标签直接删、常见实体还原。 */
    public static String plain(String html) {
        if (html == null || html.length() == 0) {
            return "";
        }
        String s = html.replace("\r", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        // 折叠连续空白与多余空行
        s = s.replaceAll("[ \t\u00a0]+", " ");
        s = s.replaceAll("\n{2,}", "\n");
        return s.trim();
    }

    /**
     * vod_play_url 形如：第01集$https://x/index.m3u8#第02集$https://y/index.m3u8
     * 多线路时线路之间用 $$$ 分隔，取第一条非空线路。
     */
    public void parseEpisodes() {
        List<String> ns = new ArrayList<String>();
        List<String> us = new ArrayList<String>();
        if (playUrl != null && playUrl.length() > 0) {
            String[] groups = playUrl.split("\\$\\$\\$");
            String chosen = null;
            for (int i = 0; i < groups.length; i++) {
                String g = groups[i];
                if (g != null && g.trim().length() > 0) {
                    chosen = g;
                    break;
                }
            }
            if (chosen != null) {
                String[] parts = chosen.split("#");
                for (int i = 0; i < parts.length; i++) {
                    String s = parts[i].trim();
                    if (s.length() == 0) {
                        continue;
                    }
                    String nm;
                    String url;
                    int d = s.indexOf('$');
                    if (d > 0) {
                        nm = s.substring(0, d).trim();
                        url = s.substring(d + 1).trim();
                    } else {
                        nm = "第" + (i + 1) + "集";
                        url = s;
                    }
                    if (url.startsWith("http")) {
                        if (nm.length() == 0) {
                            nm = "第" + (i + 1) + "集";
                        }
                        ns.add(nm);
                        us.add(url);
                    }
                }
            }
        }
        epNames = ns.toArray(new String[ns.size()]);
        epUrls = us.toArray(new String[us.size()]);
    }
}

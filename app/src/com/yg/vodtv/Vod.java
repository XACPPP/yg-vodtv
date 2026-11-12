package com.yg.vodtv;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 影片数据模型 + 选集解析。 */
public final class Vod {

    public int id;
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
        if (o == null) {
            return null;
        }
        Vod v = new Vod();
        v.id = o.optInt("vod_id", o.optInt("id", 0));
        v.name = o.optString("vod_name", o.optString("name", ""));
        v.pic = o.optString("vod_pic", o.optString("pic", ""));
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

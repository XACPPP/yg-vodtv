package com.yg.vodtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 观看记录：直播的「常看频道」与点播的「看到哪儿了」。
 *
 * 为什么不用数据库：两边加起来的记录也就几十 KB，SharedPreferences 一个 key 存 JSON 足够，
 * 这台老电视上读一个 xml 比开 SQLite 快得多。写操作都是「读出整串 → 改 → 写回」，
 * 数据量小、只在主线程外的协程里调，不会互相踩。
 */
object History {
    /** 常看频道最多留几条：面板里一屏能看十几个，再多就是垃圾数据 */
    private const val MAX_LIVE = 40
    /** 继续观看最多留几部：够翻好几屏，又不至于让首屏读文件变慢 */
    private const val MAX_VOD = 60

    private fun sp(c: Context) = c.getSharedPreferences("hist", Context.MODE_PRIVATE)

    /* ==================== 直播：常看频道 ==================== */

    private fun liveArr(c: Context): JSONArray =
        runCatching { JSONArray(sp(c).getString("live", "[]")) }.getOrDefault(JSONArray())

    /** 切到某台就记一次：次数 +1、时间刷新；同一台只留一条 */
    fun touchLive(c: Context, name: String) {
        if (name.isEmpty()) return
        val now = System.currentTimeMillis()
        val arr = liveArr(c)
        val out = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("n")
            if (n == name) {
                hit = true
                out.put(JSONObject().put("n", n).put("c", o.optInt("c", 1) + 1).put("t", now))
            } else {
                out.put(o)
            }
        }
        if (!hit) out.put(JSONObject().put("n", name).put("c", 1).put("t", now))
        // 只留最近 MAX_LIVE 条：刚记完的都在末尾
        val keep = JSONArray()
        val from = (out.length() - MAX_LIVE).coerceAtLeast(0)
        for (i in from until out.length()) keep.put(out.optJSONObject(i))
        sp(c).edit().putString("live", keep.toString()).apply()
    }

    /** 常看频道：按最近观看时间倒序（看了几次只用来做副标题，不参与排序） */
    fun liveTop(c: Context, n: Int = 12): List<String> {
        val arr = liveArr(c)
        val rows = ArrayList<Pair<String, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nm = o.optString("n")
            if (nm.isNotEmpty()) rows.add(nm to o.optLong("t", 0L))
        }
        return rows.sortedByDescending { it.second }.take(n).map { it.first }
    }

    /** 这一台看过几次：给「常看」列表当副标题用 */
    fun liveCount(c: Context, name: String): Int {
        val arr = liveArr(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("n") == name) return o.optInt("c", 1)
        }
        return 0
    }

    /* ==================== 点播：看到哪儿了 ==================== */

    /**
     * 一条点播记录。存 url 是为了「点一下就能接着放」，不用先回源站查线路；
     * 万一那条直链换了域名，播放前会回源站按片名重取一次（见 UiState.resume）。
     */
    data class VHit(
        val name: String,
        val src: String,
        val id: Long,
        val pic: String,
        val url: String,
        val epName: String,
        val li: Int,
        val ei: Int,
        val posMs: Long,
        val durMs: Long,
        val last: Long,
    ) {
        /** 变成海报墙上的一格：副标题显示「第 3 集  12:34」 */
        fun toVod(): Vod = Vod(
            id = id, name = name, pic = pic, url = url,
            remark = if (posMs > 3000) epName + "  " + mmss(posMs) else epName,
            src = src,
        )
    }

    private fun hit(o: JSONObject) = VHit(
        name = o.optString("n"), src = o.optString("s"), id = o.optLong("id"),
        pic = o.optString("p"), url = o.optString("u"), epName = o.optString("ep"),
        li = o.optInt("li"), ei = o.optInt("ei"),
        posMs = o.optLong("pos"), durMs = o.optLong("dur"), last = o.optLong("t"),
    )

    private fun json(h: VHit) = JSONObject()
        .put("n", h.name).put("s", h.src).put("id", h.id).put("p", h.pic)
        .put("u", h.url).put("ep", h.epName).put("li", h.li).put("ei", h.ei)
        .put("pos", h.posMs).put("dur", h.durMs).put("t", h.last)

    private fun vodArr(c: Context): JSONArray =
        runCatching { JSONArray(sp(c).getString("vod", "[]")) }.getOrDefault(JSONArray())

    /** 最近看的在前 */
    fun vodAll(c: Context): List<VHit> {
        val arr = vodArr(c)
        val out = ArrayList<VHit>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(hit(it)) }
        return out.sortedByDescending { it.last }
    }

    fun vodGet(c: Context, src: String, id: Long): VHit? =
        vodAll(c).firstOrNull { it.src == src && it.id == id }

    /** 同一部片子只留一条：位置一直覆盖，别越攒越多 */
    fun saveVod(c: Context, h: VHit) {
        if (h.name.isEmpty()) return
        val arr = vodArr(c)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("s") == h.src && o.optLong("id") == h.id) continue
            out.put(o)
        }
        out.put(json(h))
        val keep = JSONArray()
        val from = (out.length() - MAX_VOD).coerceAtLeast(0)
        for (i in from until out.length()) keep.put(out.optJSONObject(i))
        sp(c).edit().putString("vod", keep.toString()).apply()
    }

    fun dropVod(c: Context, src: String, id: Long) {
        val arr = vodArr(c)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("s") == src && o.optLong("id") == id) continue
            out.put(o)
        }
        sp(c).edit().putString("vod", out.toString()).apply()
    }

    fun clear(c: Context) {
        sp(c).edit().remove("live").remove("vod").apply()
    }

    /** 12:34 / 1:02:03 —— 播放条和记录副标题共用同一套写法 */
    fun mmss(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}

package com.yg.vodtv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 数据层：把 Java 版（YG影视TV）跑了两年的两张真表搬过来用，不再有 example.com。
 *
 * - 直播：app/src/main/assets/live_jx.txt 「组id|台名|url」，同名台的多条线路并成一个台的多源
 * - 点播：app/src/main/assets/src.txt 「key|接口前缀|Referer|29 个频道号各自的 type_id」
 *   频道号是 App 内部口径（0 推荐 / 1 电影 / 2 剧集 …），取数据时按源查表，0 表示该源没这个分类
 */
object Config {
    /** GitHub 最新 release，用来做「检测更新」 */
    const val UPDATE_API = "https://api.github.com/repos/XACPPP/yg-vodtv/releases/latest"
    const val UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA_TV) YGTV/1.0"

    /** 界面分类：名称 → App 内部频道号（与 src.txt 里的位置对应） */
    val TABS = listOf(
        "推荐" to 0,
        "电影" to 1,
        "剧集" to 2,
        "综艺" to 3,
        "动漫" to 4,
        "纪录" to 19,
        "4K" to 28,
        "短剧" to 27,
    )

    /** 每个分类一次拉多少条（14 个源并发，去重后留这么多） */
    const val LIMIT = 120
}

data class Channel(val name: String, val group: String, val urls: List<String>)

/** 一集：名字形如「第01集」，url 一定是能播的直链 */
data class Episode(val name: String, val url: String)

/** 一条线路（采集站的 vod_play_from 一个段）里的一串剧集 */
data class PlayLine(val name: String, val eps: List<Episode>)

data class Vod(
    val id: Long,
    val name: String,
    val pic: String,
    /** 默认起播地址：第一条可用线路的第一集 */
    val url: String,
    val remark: String,
    val src: String,
    /** 多线路分集；空表示这条是旧数据（只有 url 一个地址） */
    val lines: List<PlayLine> = emptyList(),
    val content: String = "",
    val year: String = "",
    val area: String = "",
    val score: String = "",
    val actor: String = "",
    val director: String = "",
) {
    /** 详情页副标题「2021 · 大陆 · 评分 8.2」，空项自动省略 */
    val meta: String
        get() = listOfNotNull(
            year.ifEmpty { null },
            area.ifEmpty { null },
            score.takeIf { it.isNotEmpty() && it != "0.0" && it != "0" }?.let { "评分 $it" },
        ).joinToString("  ·  ")

    /** 线路名对用户没意义（ffm3u8 / maomi 之类），统一按序号显示 */
    fun lineLabel(i: Int) = "线路" + (i + 1)
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)
    fun mode(c: Context) = sp(c).getString("mode", "live") ?: "live"
    fun setMode(c: Context, m: String) = sp(c).edit().putString("mode", m).apply()
    fun channel(c: Context) = sp(c).getString("ch", "") ?: ""
    fun setChannel(c: Context, n: String) = sp(c).edit().putString("ch", n).apply()
    fun tab(c: Context) = sp(c).getInt("tab", 0)
    fun setTab(c: Context, i: Int) = sp(c).edit().putInt("tab", i).apply()
}

object Net {
    /**
     * 电视是 Android 5.1，系统根证书是老库：采集站/海报站的 Let's Encrypt 新链会报
     * `Trust anchor for certification path not found`。Java 版一直用宽松校验活着，
     * 这里同样处理（只用于自家的片源/海报请求，没有账号与支付）。
     */
    private val trustAll: javax.net.ssl.X509TrustManager = object : javax.net.ssl.X509TrustManager {
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
    }

    /** 同一份 socket 工厂也给 HttpsURLConnection 用（Media3 的播放器走的就是它） */
    val sslFactory: javax.net.ssl.SSLSocketFactory by lazy {
        val sc = javax.net.ssl.SSLContext.getInstance("TLS")
        sc.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
        sc.socketFactory
    }

    val ok: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply {
            runCatching {
                sslSocketFactory(sslFactory, trustAll)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    suspend fun get(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", Config.UA)
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        ok.newCall(b.build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            it.body?.string() ?: error("empty body")
        }
    }
}

/* ============================ 直播表 ============================ */

object LiveData {
    /** 组顺序就是界面上的先后：本地频道放最前 */
    private val GROUPS = listOf(
        "jxitv" to "江西本地",
        "jxcctv" to "央视",
        "jxws" to "卫视",
        "jxfilm" to "影视剧场",
        "jxmore" to "付费专业",
    )

    @Volatile
    private var cache: List<Channel>? = null

    fun table(ctx: Context): List<Channel> = cache ?: synchronized(this) {
        cache ?: load(ctx).also { cache = it }
    }

    private fun load(ctx: Context): List<Channel> {
        val raw = runCatching {
            ctx.assets.open("live_jx.txt").bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())

        val out = ArrayList<Channel>(raw.size)
        for ((gid, gname) in GROUPS) {
            // LinkedHashMap 保序：台序就是表里的书写顺序；同名台的备用线路并成多源
            val byName = LinkedHashMap<String, MutableList<String>>()
            for (line in raw) {
                val p = line.trim().split("|")
                if (p.size < 3 || p[0] != gid) continue
                val name = p[1].trim()
                val url = p[2].trim()
                if (name.isEmpty()) continue
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val bucket = byName.getOrPut(name) { ArrayList(2) }
                if (!bucket.contains(url)) bucket.add(url)
            }
            for ((name, urls) in byName) out.add(Channel(name, gname, urls))
        }
        return out
    }
}

/* ============================ 采集源 ============================ */

data class Src(val key: String, val api: String, val referer: String, val types: List<Int>) {
    /** 频道号 → 该站 type_id；0 表示这个源没有该分类 */
    fun typeOf(ch: Int): Int =
        if (ch in 1..types.size) types[ch - 1] else 0
}

object Sources {
    @Volatile
    private var cache: List<Src>? = null

    fun all(ctx: Context): List<Src> = cache ?: synchronized(this) {
        cache ?: load(ctx).also { cache = it }
    }

    private fun load(ctx: Context): List<Src> = runCatching {
        ctx.assets.open("src.txt").bufferedReader().use { r ->
            r.readLines().mapNotNull { line ->
                val p = line.trim().split("|")
                if (p.size < 4) return@mapNotNull null
                val types = p[3].split(",").map { it.trim().toIntOrNull() ?: 0 }
                Src(p[0], p[1], p[2], types)
            }
        }
    }.getOrDefault(emptyList())
}

object VodRepo {
    private val cache = HashMap<String, List<Vod>>()
    /** 片名 → 各源的同名候选（第一个是列表里显示的那条），播放失败时按序换 */
    private val alts = HashMap<String, MutableList<Vod>>()

    fun alternates(name: String): List<Vod> = alts[name.lowercase()] ?: emptyList()

    private fun jsonArray(js: String): JSONArray? = runCatching {
        JSONObject(js).optJSONArray("list")
    }.getOrNull()

    /**
     * 拆线路与分集。vod_play_from 与 vod_play_url 都是 `$$$` 分线路、
     * 线路内 `#` 分集、每集「集名$地址」。地址为网页（.../share/<hash>）的集丢掉，
     * 整条线路没有可用集也丢掉；返回的每条线路都保证至少一集能播。
     */
    private fun parseLines(from: String, play: String): List<PlayLine> {
        if (play.isBlank()) return emptyList()
        val names = from.split("$$$")
        val out = ArrayList<PlayLine>(4)
        for ((gi, group) in play.split("$$$").withIndex()) {
            val eps = ArrayList<Episode>(64)
            for (seg in group.split("#")) {
                val s = seg.trim()
                if (s.isEmpty()) continue
                val nm = s.substringBefore("$").trim()
                val u = s.substringAfter("$", "").trim()
                if (!u.startsWith("http")) continue
                if (!u.contains(".m3u8") && !u.contains(".mp4")) continue
                eps.add(Episode(nm.ifEmpty { "第" + (eps.size + 1) + "集" }, u))
            }
            if (eps.isEmpty()) continue
            val raw = names.getOrNull(gi)?.trim().orEmpty()
            out.add(PlayLine(raw.ifEmpty { "线路" + (out.size + 1) }, eps))
        }
        return out
    }

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("[\\s\\u3000]+")
    /** 采集站把换行转义错了，简介末尾常拖着「rn」「\\r\\n」这种残渣 */
    private val TAIL_JUNK = Regex("(?i)(?<=[\\u3000-\\u303f\\u4e00-\\u9fa5,.;:!?])[\\\\rn]{1,4}$")

    /** 简介是富文本：去标签、反转义、折叠空白（全角空格电视字体没有连字，统一成半角） */
    private fun clean(s: String): String {
        if (s.isEmpty()) return ""
        return s.replace(TAGS, "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(SPACES, " ")
            .replace(TAIL_JUNK, "")
            .trim()
    }

    private fun parse(js: String, key: String): List<Vod> {
        val arr = jsonArray(js) ?: return emptyList()
        val out = ArrayList<Vod>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lines = parseLines(o.optString("vod_play_from"), o.optString("vod_play_url"))
            val first = lines.firstOrNull()?.eps?.firstOrNull()?.url ?: continue
            out.add(
                Vod(
                    id = o.optLong("vod_id"),
                    name = o.optString("vod_name").trim(),
                    pic = o.optString("vod_pic").trim(),
                    url = first,
                    remark = o.optString("vod_remarks").trim(),
                    src = key,
                    lines = lines,
                    content = clean(o.optString("vod_content")),
                    year = o.optString("vod_year").trim(),
                    area = o.optString("vod_area").trim(),
                    score = o.optString("vod_score").trim(),
                    actor = clean(o.optString("vod_actor")),
                    director = clean(o.optString("vod_director")),
                )
            )
        }
        return out
    }

    private suspend fun fetch(s: Src, ch: Int, page: Int, wd: String, ac: String): List<Vod> {
        val sb = StringBuilder(s.api)
        sb.append("?ac=").append(ac)
        if (wd.isNotEmpty()) {
            sb.append("&wd=").append(URLEncoder.encode(wd, "UTF-8"))
        } else {
            val t = s.typeOf(ch)
            if (t > 0) sb.append("&t=").append(t)
            sb.append("&pg=").append(page)
        }
        return parse(Net.get(sb.toString(), s.referer), s.key)
    }

    /** 同一片名多源时只留第一条，源顺序即优先级（src.txt 的书写顺序）；其余留作备选 */
    private fun merge(lists: List<List<Vod>>, limit: Int): List<Vod> {
        // 第一趟：把所有源的同名候选都收进 alts（备选要全，不能因为凑够条数就停）
        for (list in lists) {
            for (v in list) {
                val k = v.name.lowercase()
                if (k.isEmpty()) continue
                val bucket = alts.getOrPut(k) { ArrayList(4) }
                if (bucket.none { it.url == v.url }) bucket.add(v)
            }
        }
        // 第二趟：按源顺序挑出要显示的
        val seen = HashSet<String>(512)
        val out = ArrayList<Vod>(limit)
        for (list in lists) {
            for (v in list) {
                if (v.name.isEmpty() || !seen.add(v.name.lowercase())) continue
                out.add(v)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** 起播前探一下：按顺序 HEAD 一遍，返回第一个真能连上的地址（都连不上就返回 null） */
    suspend fun firstPlayable(urls: List<String>): String? = withContext(Dispatchers.IO) {
        val quick = Net.ok.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        for (u in urls) {
            var note = "连接失败"
            val ok = runCatching {
                val req = Request.Builder().url(u).head().header("User-Agent", Config.UA).build()
                quick.newCall(req).execute().use {
                    note = "HTTP " + it.code
                    it.isSuccessful
                }
            }.onFailure { note = it.toString() }.getOrDefault(false)
            Diag.w("probe", (if (ok) "通 " else "不通 ") + note + " " + u)
            if (ok) return@withContext u
        }
        null
    }

    suspend fun list(ctx: Context, ch: Int, page: Int = 1, fresh: Boolean = false): List<Vod> {
        val key = "c$ch-$page"
        if (!fresh) cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val srcs = Sources.all(ctx).filter { ch == 0 || it.typeOf(ch) > 0 }
            val lists = coroutineScope {
                srcs.map { s -> async { runCatching { fetch(s, ch, page, "", "detail") }.getOrDefault(emptyList()) } }
                    .awaitAll()
            }
            merge(lists, Config.LIMIT).also {
                if (it.isNotEmpty()) cache[key] = it
                val f = it.firstOrNull()
                Diag.w("vod", "ch=$ch 源=" + srcs.size + " 拿到=" + it.size + " 首条=" +
                    (f?.name ?: "-") + " pic=" + (f?.pic ?: "-") + " url=" + (f?.url ?: "-"))
            }
        }
    }

    suspend fun search(ctx: Context, q: String): List<Vod> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        val srcs = Sources.all(ctx)
        val lists = coroutineScope {
            srcs.map { s -> async { runCatching { fetch(s, 0, 1, q, "detail") }.getOrDefault(emptyList()) } }
                .awaitAll()
        }
        merge(lists, Config.LIMIT)
    }

    /**
     * 详情页用：列表接口的 vod_content 经常是空的，单独打一次 ac=detail&ids= 补简介/
     * 完整线路。失败就返回 null，界面拿列表里那条兜底（至少分集能看能选）。
     */
    suspend fun detail(ctx: Context, v: Vod): Vod? = withContext(Dispatchers.IO) {
        val s = Sources.all(ctx).firstOrNull { it.key == v.src } ?: return@withContext null
        runCatching {
            val js = Net.get(s.api + "?ac=detail&ids=" + v.id, s.referer)
            // 有些源不理 ac=detail&ids=，直接把整张列表吐回来：按 vod_id 认领，别拿错片
            val list = parse(js, s.key)
            list.firstOrNull { it.id == v.id } ?: list.firstOrNull { it.name == v.name } ?: list.firstOrNull()
        }.onFailure { Diag.w("vod", "详情失败 " + v.name + " " + it) }.getOrNull()
    }

    fun clearCache() {
        cache.clear(); alts.clear()
    }
}

/* ============================ 更新 / 自检 ============================ */

object Update {
    data class Info(val tag: String, val versionCode: Int, val url: String)

    /** GitHub 最新 release：tag_name 形如 v2.28，取 apk 资产地址 */
    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(Net.get(Config.UPDATE_API))
            val tag = o.optString("tag_name")
            val assets = o.optJSONArray("assets")
            var url = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", true)) {
                        url = a.optString("browser_download_url")
                        break
                    }
                }
            }
            if (tag.isEmpty()) null else Info(tag, versionOf(tag), url)
        }.getOrNull()
    }

    /** "v2.28" / "2.28" → 228，"2.9" → 209；解析不出来当 0。只用来比新旧，别当 versionCode 用 */
    fun versionOf(s: String?): Int {
        val t = (s ?: "").trim().trimStart('v', 'V')
        if (t.isEmpty()) return 0
        return t.split('.').take(2).fold(0) { acc, p ->
            acc * 100 + (p.filter { it.isDigit() }.toIntOrNull() ?: 0)
        }
    }
}

object SelfTest {
    data class Report(val liveOk: Boolean, val liveCount: Int, val vodOk: Int, val vodBad: Int)

    /** 直播：asset 表解析出频道即可播；点播：并发打一发「电影」分类，拿到片子就算通 */
    suspend fun run(ctx: Context): Report = withContext(Dispatchers.IO) {
        val channels = runCatching { LiveData.table(ctx) }.getOrDefault(emptyList())
        val srcCount = Sources.all(ctx).size
        val got = runCatching { VodRepo.list(ctx, 1, 1, fresh = true) }.getOrDefault(emptyList())
        val ok = if (got.isEmpty()) 0 else got.map { it.src }.distinct().size
        Report(channels.isNotEmpty(), channels.size, ok, (srcCount - ok).coerceAtLeast(0))
    }
}

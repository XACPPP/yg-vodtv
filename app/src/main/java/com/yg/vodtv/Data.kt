package com.yg.vodtv

import android.content.Context
import kotlinx.coroutines.CancellationException
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
 * 数据层：把 Java 版（YG影视TV）跑了两年的真表搬过来用，不再有 example.com。
 *
 * - 直播：app/src/main/assets/live_jx.txt「组id|台名|url」（江西电信单播）+
 *   app/src/main/assets/live.json（公共源，CI 每天重建），同名台的多条线路并成一个台的多源
 * - 点播：app/src/main/assets/src.txt「key|接口前缀|Referer|29 个 type_id」（type_id 已不用，分类改成按源的分类表动态取），
 *   分类不再写死：启动时问各源 `?ac=list` 拿它自己的分类表（见 Cats）
 * - 搜索：苹果CMS 服务端只认中文/全拼，首字母要靠本地的 py/ 索引翻译（见 PyIndex）
 */
object Config {
    /** GitHub 最新 release，用来做「检测更新」 */
    const val UPDATE_API = "https://api.github.com/repos/XACPPP/yg-vodtv/releases/latest"
    const val UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA_TV) YGTV/1.0"

    /** 公共直播源（仓库里的 live.json，CI 每天重建后提交） */
    /** 公共源/拼音表：GitHub raw 在国内经常连不上，先走 jsDelivr 镜像，再退回 raw */
    val LIVE_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/XACPPP/yg-vodtv@master/app/src/main/assets/live.json",
        "https://raw.githubusercontent.com/XACPPP/yg-vodtv/master/app/src/main/assets/live.json",
    )

    /** 拼音首字母索引，客户端按需下单个字母桶 */
    val PY_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/XACPPP/yg-vodtv@master/app/src/main/assets/py/",
        "https://raw.githubusercontent.com/XACPPP/yg-vodtv/master/app/src/main/assets/py/",
    )

    /** 每个分类一次拉多少条（14 个源并发，去重后留这么多） */
    const val LIMIT = 120

    /** 分类条最多几档（含「推荐」）：遥控器左右滑的极限 */
    const val MAX_CATS = 24
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
    /** 条目自己的分类名（采集站给的原话，如「国产剧」「恐怖片」），浏览分类时就靠它认领 */
    val typeName: String = "",
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
    /**
     * 组顺序：本地（江西电信单播，内网最稳）最前，然后是公共源。
     * 公共源 live.json 的组名与电信的「央视/卫视」是同一批台，合并到同一组里，
     * 台名相同就并成多线路——一个台挂了还能换下一路。
     */
    private val JX = listOf(
        "jxitv" to "江西本地",
        "jxcctv" to "央视",
        "jxws" to "卫视",
        "jxfilm" to "影视",
        "jxmore" to "付费",
    )
    private val PUB = mapOf(
        "cctv" to "央视", "sat" to "卫视", "sports" to "体育", "movie" to "电影",
        "hkmotw" to "港澳台", "local" to "地方", "other" to "其它",
    )
    /** 界面上的组顺序（只列真存在的组） */
    private val ORDER = listOf("江西本地", "央视", "卫视", "地方", "体育", "电影", "港澳台", "其它", "影视", "付费")

    @Volatile
    private var cache: List<Channel>? = null

    fun table(ctx: Context): List<Channel> = cache ?: synchronized(this) {
        cache ?: load(ctx).also { cache = it }
    }

    private fun load(ctx: Context): List<Channel> {
        // 组 → 台名 → 地址表；LinkedHashMap 保序：台序就是表里的书写顺序
        val byGroup = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()

        fun put(group: String, name: String, url: String) {
            if (name.isEmpty()) return
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            val bucket = byGroup.getOrPut(group) { LinkedHashMap() }.getOrPut(name) { ArrayList(2) }
            if (!bucket.contains(url)) bucket.add(url)
        }

        // 1) 电信单播
        val raw = runCatching {
            ctx.assets.open("live_jx.txt").bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
        val jxName = JX.toMap()
        for (line in raw) {
            val p = line.trim().split("|")
            if (p.size < 3) continue
            val g = jxName[p[0]] ?: continue
            put(g, p[1].trim(), p[2].trim())
        }

        // 2) 公共源：优先用缓存下来的新版本（上一轮刷新过的），没有就用包里那份
        val pub = fileBody(ctx) ?: runCatching {
            ctx.assets.open("live.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (pub != null) runCatching {
            val arr = JSONObject(pub).optJSONArray("groups") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val gname = PUB[g.optString("id")] ?: continue
                val chs = g.optJSONArray("chs") ?: continue
                for (j in 0 until chs.length()) {
                    val c = chs.optJSONObject(j) ?: continue
                    val urls = c.optJSONArray("urls") ?: continue
                    for (k in 0 until urls.length()) {
                        put(gname, c.optString("name").trim(), urls.optString(k).trim())
                    }
                }
            }
        }.onFailure { Diag.w("live", "公共源解析失败 " + it) }

        val out = ArrayList<Channel>(512)
        for (g in ORDER) {
            val chs = byGroup[g] ?: continue
            for ((name, urls) in chs) out.add(Channel(name, g, urls))
        }
        Diag.w("live", "频道表 " + out.size + " 台 组=" + out.map { it.group }.distinct().joinToString("/"))
        return out
    }

    private fun fileBody(ctx: Context): String? = runCatching {
        val f = java.io.File(ctx.filesDir, "live.json")
        if (f.exists() && f.length() > 4096) f.readText() else null
    }.getOrNull()

    /**
     * 后台刷新公共源：GitHub raw 拉最新 live.json 存到 filesDir/live.json，下次启动生效。
     * 拉不到就继续用包里那份，绝不阻塞界面。
     */
    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        for (url in Config.LIVE_URLS) {
            val body = runCatching { Net.get(url) }.getOrNull()
            if (body == null || body.length < 4096) {
                Diag.w("live", "公共源拉取失败 " + url.substringAfter("//").substringBefore("/"))
                continue
            }
            val old = fileBody(ctx) ?: ""
            // 内容没变就不写盘，省一次闪存
            if (body == old) return@withContext
            val f = java.io.File(ctx.filesDir, "live.json")
            val tmp = java.io.File(ctx.filesDir, "live.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            Diag.w("live", "公共源已更新 " + body.length + "B（" +
                url.substringAfter("//").substringBefore("/") + "）")
            return@withContext
        }
        Unit
    }
}

/* ============================ 采集源 ============================ */

data class Src(val key: String, val api: String, val referer: String, val types: List<Int>)

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

/**
 * 一个分类档：归一后的显示名 + 各源里能归到它的原始分类名。
 * aliases 为空 = 「推荐」：不筛分类，各源按最新更新倒序给。
 */
data class Cat(
    val name: String,
    /** 各源里归到这一档的原始分类名（只用于统计/日志） */
    val aliases: Set<String>,
    /** 条目级分类名（归一后）落在这个集合里的，就算这一档的内容 */
    val accepts: Set<String>,
) {
    /** 有多少个原始分类名归到这一档 */
    val count: Int get() = aliases.size
    /** 「推荐」不筛分类 */
    val isAll: Boolean get() = accepts.isEmpty()
    fun match(typeName: String): Boolean = isAll || accepts.contains(Cats.norm(typeName))
}

/**
 * 动态分类：各源的分类名与 type_id 互不相同（ffzy 电影=1、bfzy 电影=20、jszyapi 电影=2…），
 * 以前拿一张「内部频道号 → 位置」的死表去套，位置一错分类内容就串了。
 * 现在直接问每个源 `?ac=list` 要它自己的 class 表，归一分类名后并成分类条；
 * 列表内容再按条目自己的 type_name 认领（不让源按 t= 过滤：绝大多数源不认这个参数）。
 */
object Cats {
    /** 采集站的同义分类名先归一，否则分类条会冒出「电影/电影片/电影片儿」三档 */
    private val ALIAS = mapOf(
        "电影片" to "电影", "电影" to "电影",
        "连续剧" to "剧集", "电视剧" to "剧集", "剧集" to "剧集",
        "综艺片" to "综艺", "综艺" to "综艺",
        "动漫片" to "动漫", "动漫" to "动漫",
        "纪录片" to "纪录", "记录片" to "纪录", "记录" to "纪录",
        "短剧大全" to "短剧", "爽文短剧" to "短剧", "短剧" to "短剧",
        "体育赛事" to "体育", "体育" to "体育",
        "伦理片" to "伦理", "伦理" to "伦理", "港台三级" to "伦理", "理论片" to "伦理",
        "动画片" to "动画", "动画电影" to "动画",
        "动作片" to "动作", "喜剧片" to "喜剧", "爱情片" to "爱情", "科幻片" to "科幻",
        "恐怖片" to "恐怖", "剧情片" to "剧情", "战争片" to "战争", "悬疑片" to "悬疑",
        "犯罪片" to "犯罪", "灾难片" to "灾难", "惊悚片" to "惊悚", "奇幻片" to "奇幻",
        "古装片" to "古装", "历史片" to "历史", "家庭片" to "家庭", "家庭篇" to "家庭",
        "冒险片" to "冒险", "文艺片" to "文艺", "歌舞片" to "歌舞", "其他片" to "其他片", "短片" to "短片",
        "国产剧" to "国产剧", "大陆剧" to "国产剧", "内地剧" to "国产剧",
        "欧美剧" to "欧美剧", "香港剧" to "港剧", "港剧" to "港剧",
        "韩国剧" to "韩剧", "韩剧" to "韩剧", "日本剧" to "日剧", "日剧" to "日剧",
        "台湾剧" to "台剧", "台剧" to "台剧", "泰国剧" to "泰剧", "泰剧" to "泰剧",
        "海外剧" to "海外剧", "马泰剧" to "海外剧",
        "大陆综艺" to "大陆综艺", "国内综艺" to "大陆综艺",
        "港台综艺" to "港台综艺", "日韩综艺" to "日韩综艺", "日本综艺" to "日韩综艺",
        "欧美综艺" to "欧美综艺",
        "国产动漫" to "国产动漫", "大陆动漫" to "国产动漫",
        "日韩动漫" to "日韩动漫", "日本动漫" to "日韩动漫",
        "欧美动漫" to "欧美动漫", "港台动漫" to "港台动漫", "海外动漫" to "海外动漫",
    )

    /**
     * 一级分类 → 它的子分类。采集站列表里条目的 type_name 基本是叶子
     * （电影一律写成「动作片/恐怖片…」，剧一律写成「国产剧/日剧…」），
     * 所以「电影」这一档必须把整棵子树都算进来，否则点进去是空的。
     */
    private val GROUP = mapOf(
        "电影" to setOf(
            "电影", "动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争", "悬疑", "犯罪",
            "灾难", "惊悚", "奇幻", "古装", "历史", "家庭", "冒险", "文艺", "歌舞", "其他片", "短片",
        ),
        "剧集" to setOf("剧集", "国产剧", "欧美剧", "港剧", "韩剧", "日剧", "台剧", "泰剧", "海外剧"),
        "综艺" to setOf("综艺", "大陆综艺", "港台综艺", "日韩综艺", "欧美综艺"),
        "动漫" to setOf("动漫", "动画", "国产动漫", "日韩动漫", "欧美动漫", "港台动漫", "海外动漫"),
        "纪录" to setOf("纪录"),
        "短剧" to setOf("短剧"),
        "体育" to setOf("体育"),
        "伦理" to setOf("伦理"),
    )

    /** 分类条顺序：一级分类在前，后面是常看的二级；表外的分类丢弃（源站噪声太多） */
    private val ORDER = listOf(
        "电影", "剧集", "综艺", "动漫", "纪录", "短剧", "体育", "伦理",
        "动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争", "悬疑", "犯罪", "灾难",
        "动画", "国产剧", "欧美剧", "韩剧", "日剧", "港剧", "台剧", "泰剧",
        "大陆综艺", "港台综艺", "日韩综艺", "欧美综艺", "国产动漫", "日韩动漫", "港台动漫", "海外剧",
    )

    /** 原始分类名 → 归一后的显示名；表外的名字原样返回（分类表和条目必须用同一套映射） */
    fun norm(raw: String): String = ALIAS[raw.trim()] ?: raw.trim()

    @Volatile
    private var cache: List<Cat>? = null

    /** 拉各源的分类表并归并；失败/空就只剩「推荐」，界面照样能用 */
    suspend fun load(ctx: Context, fresh: Boolean = false): List<Cat> = withContext(Dispatchers.IO) {
        cache?.takeIf { !fresh }?.let { return@withContext it }
        val srcs = Sources.all(ctx)
        val per = coroutineScope {
            srcs.map { s ->
                async {
                    runCatching { classesOf(s) }.onFailure { Diag.w("vod", "分类失败 " + s.key + " " + it) }
                        .getOrDefault(emptyMap())
                }
            }.awaitAll()
        }
        val merged = LinkedHashMap<String, LinkedHashSet<String>>()
        srcs.forEachIndexed { i, _ -> for ((nm, raw) in per[i]) merged.getOrPut(nm) { LinkedHashSet() }.addAll(raw) }

        val out = ArrayList<Cat>(Config.MAX_CATS)
        out.add(Cat("推荐", emptySet(), emptySet()))
        for (nm in ORDER) {
            val kids = GROUP[nm]
            if (kids == null) {
                val m = merged[nm] ?: continue
                out.add(Cat(nm, m, setOf(nm)))
            } else {
                val hit = kids.filter { merged.containsKey(it) }
                if (hit.isEmpty()) continue
                val raw = LinkedHashSet<String>()
                for (k in hit) raw.addAll(merged[k] ?: emptySet())
                out.add(Cat(nm, raw, hit.toSet()))
            }
            if (out.size >= Config.MAX_CATS) break
        }
        Diag.w("vod", "分类 " + out.size + " 档：" + out.joinToString(" ") { it.name + "(" + it.count + ")" })
        cache = out
        out
    }

    /** 一个源的分类表：归一后的名字 → 该源用过的原始分类名 */
    private suspend fun classesOf(s: Src): Map<String, Set<String>> {
        val js = Net.get(s.api + "?ac=list", s.referer)
        val arr = JSONObject(js).optJSONArray("class")
        if (arr == null) { Diag.w("vod", "分类原文无 class " + s.key + " " + js.take(160)); return emptyMap() }
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nm = o.optString("type_name").trim()
            if (nm.isEmpty()) continue
            out.getOrPut(norm(nm)) { LinkedHashSet() }.add(nm)
        }
        return out
    }

    fun clear() { cache = null }
}

/**
 * 拼音首字母索引（移植自 Java 版 PyIndex）。
 *
 * 为什么要有：14 个采集源都是苹果CMS，服务端只给中文和全拼建了索引 ——
 * `wd=qyn` 在 14 个源上只会吐噪声（而且 bfzy/zuidazy 干脆忽略 wd 返回整库），
 * 同一个源 `wd=庆余年` 立刻命中。电视遥控器又打不了中文，所以必须本地把
 * 「首字母」翻成「中文片名」这一步补上。
 *
 * 索引形态（仓库 app/src/main/assets/py/，CI 每天重建）：{a..z}.txt，
 * 每行「首字母<TAB>片名」，按需下单个桶（最大 260KB）缓存到 cacheDir/py/。
 * 不打包进 APK 的原因：4.8MB 会白占电视存储，而且索引天天变，包内那份第二天就旧了。
 */
object PyIndex {
    private const val MIN_BYTES = 200
    private const val MAX_NAMES = 8

    private var lastL = ""
    private var lastBody: String? = null

    private fun dir(ctx: Context) = java.io.File(ctx.cacheDir, "py").apply { mkdirs() }

    /** 首字母缩写 → 候选中文片名（最多 8 个，第一个最贴） */
    suspend fun names(ctx: Context, kw: String): List<String> = withContext(Dispatchers.IO) {
        val q = kw.trim().lowercase()
        if (q.length < 2 || q.length > 24 || q.any { it !in 'a'..'z' }) return@withContext emptyList()
        val body = bucket(ctx, q[0]) ?: return@withContext emptyList()
        pick(body, q, MAX_NAMES).also {
            Diag.w("py", "首字母 " + q + " → " + it.size + " 个候选" + if (it.isEmpty()) "" else "，首个「" + it[0] + "」")
        }
    }

    /** 标点/括号/方括号越多，越像「A,B」这种合并条目，排到后面去 */
    private fun junk(s: String): Int {
        var n = 0
        for (c in s) if (c in ",，、;；:：[]【】()（）·.|") n++
        return n
    }

    /**
     * 纯函数：两轮扫描（先收首字母完全相同的，再收「以它开头」的），
     * 然后按「标点少 → 名字短」重排：用户打 qyn 想要的是《庆余年》，不是某个长串合集。
     */
    fun pick(body: String, prefix: String, cap: Int = MAX_NAMES): List<String> {
        val q = prefix.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val lines = body.split("\n")
        val out = ArrayList<String>(64)
        for (ln in lines) {
            if (out.size >= 64) break
            if (q == keyOf(ln)) addName(out, ln)
        }
        for (ln in lines) {
            if (out.size >= 64) break
            val k = keyOf(ln) ?: continue
            if (k.length > q.length && k.startsWith(q)) addName(out, ln)
        }
        return out.sortedWith(compareBy({ junk(it) }, { it.length })).take(cap)
    }

    private fun keyOf(ln: String): String? {
        val tab = ln.indexOf('\t')
        if (tab <= 0) return null
        return ln.substring(0, tab).trim().lowercase()
    }

    private fun addName(out: MutableList<String>, ln: String) {
        val tab = ln.indexOf('\t')
        if (tab <= 0 || tab + 1 >= ln.length) return
        val name = ln.substring(tab + 1).trim()
        if (name.isNotEmpty() && !out.contains(name)) out.add(name)
    }

    /** 取一个字母的桶：本地有就读盘，没有就下（下载失败返回 null，搜索退回直接查） */
    private suspend fun bucket(ctx: Context, L: Char): String? {
        val key = L.toString()
        synchronized(this) { if (key == lastL) lastBody?.let { return it } }
        val f = java.io.File(dir(ctx), key + ".txt")
        if (f.exists() && f.length() >= MIN_BYTES) {
            val t = runCatching { f.readText() }.getOrNull()
            if (t != null && t.length >= MIN_BYTES) {
                synchronized(this) { lastL = key; lastBody = t }
                return t
            }
        }
        // 打进包里的那份最省事：不联网也能搜，只有巡检更新过才需要拉新的
        val packed = runCatching {
            ctx.assets.open("py/" + key + ".txt").use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()
        if (packed != null && packed.length >= MIN_BYTES) {
            synchronized(this) { lastL = key; lastBody = packed }
            return packed
        }
        for (base in Config.PY_URLS) {
            val net = runCatching { Net.get(base + key + ".txt") }.getOrNull()
            if (net == null || net.length < MIN_BYTES) continue
            runCatching {
                val tmp = java.io.File(dir(ctx), key + ".txt.tmp")
                tmp.writeText(net)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
            Diag.w("py", "首字母表 " + key + " 在线取自 " + base.substringAfter("//").substringBefore("/"))
            synchronized(this) { lastL = key; lastBody = net }
            return net
        }
        Diag.w("py", "首字母表 " + key + " 拿不到，本次搜索直接查关键词")
        return null
    }

    fun clear(ctx: Context) {
        synchronized(this) { lastL = ""; lastBody = null }
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
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
                    typeName = o.optString("type_name").trim(),
                )
            )
        }
        return out
    }

    /**
     * 打一个源的接口：不带分类参数，按最新更新倒序取一页（分类由调用方按 type_name 筛）；
     * wd 非空走搜索；filter 非空会把结果按片名再筛一遍 ——
     * bfzy/zuidazy 这类源压根不认 wd，直接把 17 万条片库吐回来，不筛就全是噪声。
     */
    private suspend fun fetch(s: Src, page: Int, wd: String, ac: String, filter: String = ""): List<Vod> {
        val sb = StringBuilder(s.api)
        sb.append("?ac=").append(ac)
        if (wd.isNotEmpty()) sb.append("&wd=").append(URLEncoder.encode(wd, "UTF-8"))
        sb.append("&pg=").append(page)
        val list = parse(Net.get(sb.toString(), s.referer), s.key)
        if (filter.isEmpty()) return list
        val f = filter.lowercase()
        return list.filter { it.name.lowercase().contains(f) }
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

    suspend fun list(ctx: Context, cat: Cat, page: Int = 1, fresh: Boolean = false): List<Vod> {
        val key = "c:" + cat.name + "-" + page
        if (!fresh) cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val srcs = Sources.all(ctx)
            // 分类靠条目自己的 type_name 认领：实测 14 个源里只有 bfzy 认 t= 过滤，其余给 t= 一律返回空
            val pages = if (cat.isAll) 1 else 4
            val lists = coroutineScope {
                srcs.map { s ->
                    async {
                        val acc = ArrayList<Vod>(64)
                        for (p in page until page + pages) {
                            val got = runCatching { fetch(s, p, "", "detail") }.getOrDefault(emptyList())
                            if (got.isEmpty()) break
                            for (v in got) if (cat.match(v.typeName)) acc.add(v)
                            if (!cat.isAll && acc.size >= 40) break
                        }
                        acc
                    }
                }.awaitAll()
            }
            merge(lists, Config.LIMIT).also {
                if (it.isNotEmpty()) cache[key] = it
                val f = it.firstOrNull()
                Diag.w("vod", cat.name + " 源=" + srcs.size + " 拿到=" + it.size + " 首条=" +
                    (f?.name ?: "-") + "(" + (f?.typeName ?: "-") + ") url=" + (f?.url ?: "-"))
            }
        }
    }

    /**
     * 搜索。电视遥控器打不了中文，所以：
     * 1) 输入全是字母 → 先查本地拼音首字母索引（py/q.txt 这类桶），把「qyn」翻成「庆余年」；
     *    苹果CMS 服务端只认中文和全拼，直接拿首字母去问只会得到一堆噪声。
     * 2) 拿到中文片名（最多 3 个候选）后，逐名去问每个源，再把每源结果按片名筛一遍。
     * 3) 全是数字/中文就直接当关键词问（有些源也吃数字片名）。
     */
    suspend fun search(ctx: Context, q: String): List<Vod> = withContext(Dispatchers.IO) {
        val key = q.trim()
        if (key.isEmpty()) return@withContext emptyList()
        val lower = key.lowercase()
        val ascii = lower.all { it in 'a'..'z' }
        val names = if (ascii) PyIndex.names(ctx, lower) else emptyList()
        val queries = (names.ifEmpty { listOf(key) }).take(3)
        Diag.w("vod", "搜索「" + key + "」" + (if (names.isEmpty()) "" else " 首字母→" + names.joinToString("/")) +
            " 查询词=" + queries.joinToString("/"))

        val srcs = Sources.all(ctx)
        val lists = coroutineScope {
            srcs.map { s ->
                async {
                    val out = ArrayList<Vod>()
                    for (wd in queries) {
                        // 别用 runCatching 包 suspend：它会连 CancellationException 一起吞掉，
                        // 上一个字的搜索被取消后还会把空结果写回界面
                        val one = try {
                            fetch(s, 1, wd, "detail", filter = wd)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Diag.w("vod", "搜索 " + s.key + " 出错 " + t)
                            emptyList()
                        }
                        out.addAll(one)
                    }
                    if (out.isNotEmpty()) Diag.w("vod", "搜索 " + s.key + " 命中 " + out.size)
                    out
                }
            }.awaitAll()
        }
        val merged = merge(lists, Config.LIMIT)
        // 首字母命中的候选片名排前面：用户打全了首字母，最贴的那部应该在第一屏
        if (names.isEmpty()) merged else merged.sortedBy { v -> names.indexOfFirst { it.equals(v.name, true) }.let { if (it < 0) names.size else it } }
    }

    /** 点播封面墙用「推荐」时没有类型参数 */
    suspend fun list(ctx: Context, ch: Int, page: Int = 1, fresh: Boolean = false): List<Vod> =
        list(ctx, Cat("推荐", emptySet(), emptySet()), page, fresh)

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

    fun clearCache(ctx: Context) {
        cache.clear(); alts.clear(); Cats.clear(); PyIndex.clear(ctx)
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
        val cats = runCatching { Cats.load(ctx) }.getOrDefault(emptyList())
        val ch = cats.firstOrNull { it.name == "电影" } ?: cats.firstOrNull() ?: Cat("推荐", emptySet(), emptySet())
        val got = runCatching { VodRepo.list(ctx, ch, 1, fresh = true) }.getOrDefault(emptyList())
        val ok = if (got.isEmpty()) 0 else got.map { it.src }.distinct().size
        Report(channels.isNotEmpty(), channels.size, ok, (srcCount - ok).coerceAtLeast(0))
    }
}

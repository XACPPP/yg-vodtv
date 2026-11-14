package com.yg.vodtv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Zone { BAR, PLAY, PANEL, TABS, SEARCH, GRID, SEARCH_PAGE, RESULTS, DETAIL, VOD_PLAY }
enum class Rk { L, R, U, D, OK, B }

val KB = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } + listOf("删", "清")

/** 全部按键逻辑集中在这里（与 HTML 原型一一对应），界面只负责按状态画焦点。 */
class UiState(private val ctx: Context, private val scope: CoroutineScope, private val onExit: () -> Unit) {
    val live = LiveController(ctx, scope)
    var mode by mutableStateOf(Prefs.mode(ctx))
    var zone by mutableStateOf(Zone.BAR)
    var bar by mutableIntStateOf(if (mode == "vod") 1 else 0)
    var barShow by mutableStateOf(true)
    var osd by mutableStateOf(false)
    var pc by mutableIntStateOf(0); var pi by mutableIntStateOf(0); var col by mutableIntStateOf(1)
    var tab by mutableIntStateOf(0); var g by mutableIntStateOf(0)
    var kb by mutableIntStateOf(0); var rg by mutableIntStateOf(0)
    var q by mutableStateOf("")
    var items by mutableStateOf<List<Vod>>(emptyList())
    var results by mutableStateOf<List<Vod>>(emptyList())
    var playing by mutableStateOf<Vod?>(null)
    var toast by mutableStateOf("")

    /* 详情页：detail 是列表那条，进来后再异步补简介与完整线路 */
    var detail by mutableStateOf<Vod?>(null)
    var dLine by mutableIntStateOf(0)      // 当前线路
    var dEp by mutableIntStateOf(0)        // 当前集（当前线路内的序号）
    var dFocus by mutableStateOf(false)    // true = 焦点在「线路」那排，false = 选集
    var dLoading by mutableStateOf(false)  // 正在补详情（简介还没到手）
    private var detailBack = Zone.GRID

    /** 播放页集数状态：用于「第 3/12 集」和 ▲▼ 切集 */
    var epLine by mutableIntStateOf(0)
    var epIdx by mutableIntStateOf(0)

    val dEps: List<Episode> get() = detail?.lines?.getOrNull(dLine)?.eps ?: emptyList()
    val epTotal: Int get() = playing?.lines?.getOrNull(epLine)?.eps?.size ?: 0
    val epNow: String get() = playing?.lines?.getOrNull(epLine)?.eps?.getOrNull(epIdx)?.name ?: ""

    private var auto = mode == "live"
    private var exitPending = false
    private var vodBack = Zone.GRID
    private var idle: Job? = null; private var toastJob: Job? = null
    private var tabJob: Job? = null; private var searchJob: Job? = null

    init {
        live.onVodFail = { msg -> toast(msg, 3000) }
        scope.launch { live.load(); if (mode == "live") live.startCurrent() }
        tab = Prefs.tab(ctx).coerceIn(0, Config.TABS.size - 1)
        if (mode == "vod") loadTab(tab)
        arm()
    }

    fun toast(t: String, ms: Long = 2000) {
        toast = t; toastJob?.cancel()
        toastJob = scope.launch { delay(ms); toast = ""; exitPending = false }
    }

    /** 播放页 2 秒无操作隐藏菜单；直播卡片被选中后 2 秒自动进入播放 */
    private fun arm() {
        idle?.cancel()
        if (zone == Zone.PLAY || zone == Zone.VOD_PLAY) {
            idle = scope.launch { delay(2000); barShow = false; osd = false }
        } else if (zone == Zone.BAR && mode == "live" && auto && bar == 0) {
            idle = scope.launch { delay(2000); zone = Zone.PLAY; barShow = false; osd = true; arm() }
        }
    }

    private fun applyMode(m: String) {
        if (m == mode) return
        mode = m; Prefs.setMode(ctx, m); g = 0
        if (m == "vod") { live.stop(); loadTab(tab) } else live.startCurrent()
    }

    private fun enter() {
        auto = false
        if (mode == "live") { zone = Zone.PLAY; barShow = true; osd = true } else zone = Zone.TABS
    }

    private fun loadTab(i: Int) {
        tabJob?.cancel(); items = emptyList(); g = 0
        Prefs.setTab(ctx, i)
        val ch = Config.TABS[i].second
        tabJob = scope.launch {
            delay(120)   // 连按左右键时只在最后一档真正发请求
            items = runCatching { VodRepo.list(ctx, ch) }.getOrDefault(emptyList())
            if (items.isEmpty()) toast("这个分类暂时取不到片子，稍后再试")
        }
    }

    private fun doSearch() {
        searchJob?.cancel()
        if (q.isEmpty()) { results = emptyList(); return }
        searchJob = scope.launch {
            delay(300)
            results = runCatching { VodRepo.search(ctx, q) }.getOrDefault(emptyList())
            rg = 0
            if (results.isEmpty()) toast("没搜到\"$q\"的片子")
        }
    }

    /** 从海报墙/搜索结果进详情页：先把列表数据画出来，再补一次真详情（简介常是空的） */
    private fun openDetail(v: Vod, back: Zone) {
        detail = v; detailBack = back
        dLine = 0; dEp = 0; dFocus = false; dLoading = false
        zone = Zone.DETAIL; barShow = true
        Diag.w("vod", "详情《" + v.name + "》线路=" + v.lines.size + " 集=" +
            (v.lines.firstOrNull()?.eps?.size ?: 0) + " 简介=" + v.content.length)
        if (v.content.isNotEmpty() && v.lines.size > 1) { dLoading = false; return }
        dLoading = true
        scope.launch {
            val d = try { runCatching { VodRepo.detail(ctx, v) }.getOrNull() } finally { dLoading = false }
            if (d == null) { Diag.w("vod", "详情拿不到《" + v.name + "》，用列表数据"); return@launch }
            if (detail?.id != v.id) return@launch
            val merged = v.copy(
                lines = d.lines.ifEmpty { v.lines },
                content = d.content.ifEmpty { v.content },
                year = d.year.ifEmpty { v.year },
                area = d.area.ifEmpty { v.area },
                score = d.score.ifEmpty { v.score },
                actor = d.actor.ifEmpty { v.actor },
                director = d.director.ifEmpty { v.director },
                pic = d.pic.ifEmpty { v.pic },
                remark = d.remark.ifEmpty { v.remark },
            )
            detail = merged
            dLine = dLine.coerceIn(0, (merged.lines.size - 1).coerceAtLeast(0))
            dEp = dEp.coerceIn(0, (dEps.size - 1).coerceAtLeast(0))
            Diag.w("vod", "详情补充《" + merged.name + "》线路=" + merged.lines.size + " 集=" +
                (merged.lines.firstOrNull()?.eps?.size ?: 0) + " 简介=" + merged.content.length)
        }
    }

    /** 播某一线路的某一集；备选顺序=同集其它线路 → 其它源的同一集 → 其它源第一集 */
    private fun playEpisode(v: Vod, li: Int, ei: Int, back: Zone) {
        val line = v.lines.getOrNull(li) ?: return
        val ep = line.eps.getOrNull(ei) ?: return
        playing = v; epLine = li; epIdx = ei; vodBack = back
        Diag.w("vod", "播放《" + v.name + "》" + line.name + " " + ep.name + " " + ep.url)
        val cands = ArrayList<String>(8)
        cands.add(ep.url)
        v.lines.forEachIndexed { i, l -> if (i != li) l.eps.getOrNull(ei)?.let { cands.add(it.url) } }
        VodRepo.alternates(v.name).forEach { o ->
            val l = o.lines.firstOrNull()
            cands.add(l?.eps?.getOrNull(ei)?.url ?: l?.eps?.firstOrNull()?.url ?: o.url)
        }
        val alts = cands.filter { it.isNotEmpty() }.distinct()
        zone = Zone.VOD_PLAY; barShow = true
        // 先探路再起播：电视上有些播放域名直接不通，先黑屏再换源很难看
        scope.launch {
            val u = VodRepo.firstPlayable(alts.take(4)) ?: alts.first()
            if (u != alts.first()) Diag.w("vod", "改用备选线路 " + u)
            live.playVod(u, alts)
        }
    }

    /** 播放页 ▲▼ 切集：同一条线路里走，到头给提示不走位 */
    private fun stepEpisode(d: Int) {
        val v = playing ?: return
        val eps = v.lines.getOrNull(epLine)?.eps ?: return
        if (eps.size <= 1) return
        val t = epIdx + d
        if (t < 0) { toast("已经是第一集"); return }
        if (t > eps.size - 1) { toast("已经是最后一集"); return }
        playEpisode(v, epLine, t, vodBack)
    }

    private fun action(i: Int) {
        when (i) {
            2 -> scope.launch {
                toast("自检中…", 12000)
                val r = runCatching { SelfTest.run(ctx) }.getOrNull()
                Diag.w("tool", "自检 " + (r?.let { "直播" + it.liveCount + "台/" + it.liveOk + " 点播" + it.vodOk + "好" + it.vodBad + "坏" } ?: "失败"))
                toast(
                    if (r == null) "自检失败"
                    else "直播 " + (if (r.liveOk) "正常(${r.liveCount}台)" else "异常") +
                        "  点播 ${r.vodOk}/${r.vodOk + r.vodBad} 个源可用",
                    4500
                )
            }
            3 -> {
                val n = ctx.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } +
                    ctx.codeCacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                ctx.codeCacheDir.listFiles()?.forEach { it.deleteRecursively() }
                VodRepo.clearCache()
                Diag.w("tool", "清缓存 " + (n / 1024 / 1024) + "MB")
                toast("已清除缓存 " + (n / 1024 / 1024) + " MB")
            }
            4 -> scope.launch {
                toast("检查更新中…", 8000)
                val info = runCatching { Update.latest() }.getOrNull()
                if (info == null) { Diag.w("tool", "检测更新失败"); toast("检测更新失败，请检查网络"); return@launch }
                Diag.w("tool", "检测更新 " + info.tag)
                @Suppress("DEPRECATION")
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val cur = Update.versionOf(pi.versionName)
                toast(
                    if (info.versionCode > cur) "发现新版本 ${info.tag}" + (if (info.url.isEmpty()) "" else "：${info.url}")
                    else "已是最新版本（${info.tag}）",
                    4500
                )
            }
        }
    }

    private fun selectTab(i: Int) { tab = i; loadTab(i) }

    fun onKey(k: Rk) {
        if (k != Rk.B) exitPending = false
        val from = zone
        when (zone) {
            Zone.BAR -> when (k) {
                Rk.L, Rk.R -> {
                    bar = (bar + if (k == Rk.L) -1 else 1).coerceIn(0, 4)
                    if (bar < 2) { applyMode(if (bar == 0) "live" else "vod"); auto = bar == 0 }
                }
                Rk.OK -> if (bar > 1) action(bar) else enter()
                Rk.D -> enter()
                Rk.B -> if (bar > 1) bar = if (mode == "live") 0 else 1
                        else if (exitPending) { Diag.w("key", "返回 退出"); onExit() } else { exitPending = true; Diag.w("key", "返回 再按一次退出"); toast("再按一次返回键退出") }
                else -> {}
            }
            Zone.PLAY -> when (k) {
                Rk.U -> { live.switchTo(live.cur - 1); osd = true }
                Rk.D -> { live.switchTo(live.cur + 1); osd = true }
                Rk.L, Rk.R -> { zone = Zone.BAR; bar = if (k == Rk.R) 1 else 0; auto = false; barShow = true; if (k == Rk.R) applyMode("vod") }
                Rk.OK -> { val p = live.locate(); pc = p.first; pi = p.second; col = 1; zone = Zone.PANEL }
                Rk.B -> { zone = Zone.BAR; bar = 0; auto = false }
            }
            Zone.PANEL -> when (k) {
                Rk.U, Rk.D -> {
                    val d = if (k == Rk.U) -1 else 1
                    if (col == 0) { pc = (pc + d).coerceIn(0, (live.groups.size - 1).coerceAtLeast(0)); pi = 0 }
                    else pi = (pi + d).coerceIn(0, (live.sizeOf(pc) - 1).coerceAtLeast(0))
                }
                Rk.L -> col = 0
                Rk.R -> col = 1
                Rk.OK -> if (col == 0) col = 1 else { live.switchTo(live.globalIndex(pc, pi), immediate = true); zone = Zone.PLAY; osd = true; barShow = false }
                Rk.B -> { zone = Zone.PLAY; barShow = false }
            }
            Zone.TABS -> when (k) {
                Rk.L -> if (tab > 0) selectTab(tab - 1)
                Rk.R -> if (tab < Config.TABS.size - 1) selectTab(tab + 1) else zone = Zone.SEARCH
                Rk.D -> if (items.isNotEmpty()) { zone = Zone.GRID; g = minOf(tab, items.size - 1) }
                Rk.U -> { zone = Zone.BAR; bar = 1 }
                Rk.B -> zone = Zone.SEARCH
                else -> {}
            }
            Zone.SEARCH -> when (k) {
                Rk.L -> { zone = Zone.TABS; tab = Config.TABS.size - 1 }
                Rk.D -> if (items.isNotEmpty()) { zone = Zone.GRID; g = minOf(COLS - 1, items.size - 1) }
                Rk.U, Rk.B -> { zone = Zone.BAR; bar = 1 }
                Rk.OK -> { zone = Zone.SEARCH_PAGE; kb = 0 }
                else -> {}
            }
            Zone.GRID -> {
                val n = items.size
                when (k) {
                    Rk.L -> if (g % COLS > 0) g--
                    Rk.R -> if (g % COLS < COLS - 1 && g < n - 1) g++
                    Rk.D -> if (g + COLS < n) g += COLS
                    Rk.U -> if (g >= COLS) g -= COLS else zone = Zone.TABS
                    Rk.OK -> items.getOrNull(g)?.let { openDetail(it, Zone.GRID) }
                    Rk.B -> zone = Zone.SEARCH
                }
            }
            Zone.SEARCH_PAGE -> {
                val n = KB.size
                when (k) {
                    Rk.L -> if (kb % 10 > 0) kb--
                    Rk.R -> if (kb % 10 < 9 && kb < n - 1) kb++ else if (results.isNotEmpty()) { zone = Zone.RESULTS; rg = 0 }
                    Rk.U -> if (kb >= 10) kb -= 10
                    Rk.D -> kb = minOf(n - 1, kb + 10)
                    Rk.OK -> {
                        when (val x = KB[kb]) { "删" -> q = q.dropLast(1); "清" -> q = ""; else -> if (q.length < 12) q += x }
                        doSearch()
                    }
                    Rk.B -> zone = Zone.SEARCH
                }
            }
            Zone.RESULTS -> {
                val n = results.size
                when (k) {
                    Rk.L -> if (rg % 3 > 0) rg-- else zone = Zone.SEARCH_PAGE
                    Rk.R -> if (rg % 3 < 2 && rg < n - 1) rg++
                    Rk.U -> if (rg >= 3) rg -= 3
                    Rk.D -> if (rg + 3 < n) rg += 3
                    Rk.OK -> results.getOrNull(rg)?.let { openDetail(it, Zone.RESULTS) }
                    Rk.B -> zone = Zone.SEARCH_PAGE
                }
            }
            Zone.DETAIL -> {
                val d = detail
                val eps = dEps
                val cols = dcolsOf(eps.size)
                val lineCount = minOf(d?.lines?.size ?: 0, MaxLines)
                when (k) {
                    Rk.B -> { detail = null; dFocus = false; zone = detailBack }
                    Rk.L -> when {
                        dFocus -> if (dLine > 0) {
                            dLine--
                            dEp = dEp.coerceIn(0, (dEps.size - 1).coerceAtLeast(0))
                        }
                        dEp % cols > 0 -> dEp--
                    }
                    Rk.R -> when {
                        dFocus -> if (dLine < lineCount - 1) {
                            dLine++
                            dEp = dEp.coerceIn(0, (dEps.size - 1).coerceAtLeast(0))
                        }
                        dEp % cols < cols - 1 && dEp < eps.size - 1 -> dEp++
                    }
                    Rk.U -> when {
                        dFocus -> {}
                        dEp >= cols -> dEp -= cols
                        lineCount > 1 -> dFocus = true
                    }
                    Rk.D -> when {
                        dFocus -> dFocus = false
                        dEp + cols < eps.size -> dEp += cols
                    }
                    Rk.OK -> if (dFocus) dFocus = false
                            else if (d != null && eps.isNotEmpty()) playEpisode(d, dLine, dEp, Zone.DETAIL)
                }
            }
            Zone.VOD_PLAY -> {
                val p = live.player
                when (k) {
                    Rk.B -> { p.stop(); live.vod = false; zone = vodBack }
                    Rk.OK -> p.playWhenReady = !p.playWhenReady
                    Rk.L -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                    Rk.R -> p.seekTo(p.currentPosition + 10_000)
                    Rk.U -> stepEpisode(-1)
                    Rk.D -> stepEpisode(1)
                }
                if (k != Rk.B) barShow = true
            }
        }
        if (zone != Zone.PLAY && zone != Zone.VOD_PLAY && zone != Zone.PANEL) barShow = true
        arm()
        if (zone != from) Diag.w("key", k.name + " " + from.name + " → " + zone.name + (if (barShow) " [界面显示]" else " [界面收起]"))
    }
}

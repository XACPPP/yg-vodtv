package com.yg.vodtv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

const val COLS = 5
const val DCOLS = 6
/** 详情页线路最多显示几条（多了放不下，取前 6 条） */
const val MaxLines = 6

/** 详情页选集列数：电视剧 6 列；电影/短剧（≤3 集）用 3 列大格子好看也更省地方 */
fun dcolsOf(n: Int) = if (n <= 3) 3 else DCOLS

val Bg = Color(0xFF0C1016); val Panel = Color(0xFF171F2B); val Panel2 = Color(0xFF243349)
val Accent = Color(0xFFF2B441); val Muted = Color(0xFF8C98AB)

@Composable
fun Modifier.focusFx(on: Boolean, r: Dp = 10.dp): Modifier {
    val s by animateFloatAsState(if (on) 1.06f else 1f, label = "fx")
    return graphicsLayer { scaleX = s; scaleY = s }
        .then(if (on) Modifier.border(2.dp, Accent, RoundedCornerShape(r)) else Modifier)
}

@Composable
fun Cell(text: String, focused: Boolean, selected: Boolean, modifier: Modifier, size: Int = 15, maxLines: Int = 1) {
    Box(
        modifier.focusFx(focused).clip(RoundedCornerShape(10.dp)).background(if (selected) Panel2 else Panel),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (selected || focused) Color.White else Muted, fontSize = size.sp,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            lineHeight = (size + 2).sp
        )
    }
}

@Composable
fun Video(p: ExoPlayer) = AndroidView(
    factory = { c -> PlayerView(c).apply { useController = false; player = p } },
    modifier = Modifier.fillMaxSize()
)

@Composable
fun App(s: UiState) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (s.mode == "live") LiveScreen(s) else VodHome(s)
        if (s.zone == Zone.DETAIL) DetailScreen(s)
        if (s.zone == Zone.SEARCH_PAGE || s.zone == Zone.RESULTS) SearchPage(s)
        if (s.zone == Zone.VOD_PLAY) VodPlayer(s)
        if (s.zone == Zone.PANEL) ChannelPanel(s)
        TopBar(s, when (s.zone) { Zone.PLAY, Zone.VOD_PLAY -> s.barShow; Zone.PANEL -> false; else -> true })
        if (s.toast.isNotEmpty()) Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.BottomCenter) {
            Text(s.toast, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xDD000000)).padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}

@Composable
fun TopBar(s: UiState, visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF005070B), Color.Transparent)))
                .padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Text("▶", fontSize = 19.sp, color = Color(0xFF2A1600))
            }
            // 卡片与图标同高(40dp)
            Cell("电视直播", s.zone == Zone.BAR && s.bar == 0, s.mode == "live", Modifier.height(40.dp).width(132.dp), 15)
            Cell("影视点播", s.zone == Zone.BAR && s.bar == 1, s.mode == "vod", Modifier.height(40.dp).width(132.dp), 15)
            Spacer(Modifier.weight(1f))
            Cell("自检", s.zone == Zone.BAR && s.bar == 2, false, Modifier.height(32.dp).width(76.dp), 12)
            Cell("清除缓存", s.zone == Zone.BAR && s.bar == 3, false, Modifier.height(32.dp).width(92.dp), 12)
            Cell("检测更新", s.zone == Zone.BAR && s.bar == 4, false, Modifier.height(32.dp).width(92.dp), 12)
        }
    }
}

@Composable
fun LiveScreen(s: UiState) {
    Box(Modifier.fillMaxSize()) {
        Video(s.live.player)
        val c = s.live.channels.getOrNull(s.live.cur)
        if (c == null || s.live.failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (c == null) "正在加载频道…" else "${c.name} 暂时无法播放，按上下键换台", color = Muted, fontSize = 18.sp)
        }
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(s.osd && c != null, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xD905070B)).padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("第 ${s.live.cur + 1} 台 · ${c?.name ?: ""}", color = Color.White, fontSize = 16.sp)
                    Text("↑↓ 换台   OK 频道列表   返回 卡片栏", color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ChannelPanel(s: UiState) {
    Row(Modifier.fillMaxHeight().width(450.dp).background(Color(0xF2080B12)).padding(vertical = 20.dp)) {
        Column(Modifier.width(135.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.live.groups.forEachIndexed { i, n -> Cell(n, s.col == 0 && s.pc == i, s.pc == i, Modifier.fillMaxWidth().height(38.dp), 14) }
        }
        val st = rememberLazyListState()
        LaunchedEffect(s.pi, s.pc) { st.animateScrollToItem((s.pi - 3).coerceAtLeast(0)) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = st, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(s.live.inGroup(s.pc)) { k, ic ->
                Cell("${ic.index + 1}  ${ic.value.name}", s.col == 1 && s.pi == k, ic.index == s.live.cur, Modifier.fillMaxWidth().height(38.dp), 14)
            }
        }
    }
}

@Composable
fun Poster(v: Vod, focused: Boolean, h: Dp) {
    Box(Modifier.fillMaxWidth().height(h).focusFx(focused, 9.dp).clip(RoundedCornerShape(9.dp)).background(Panel)) {
        // 底衬：图没下来（或源站挂图）时露出片名首字，别让整格变黑砖
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                v.name.take(2),
                color = Color(0x33FFFFFF),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        AsyncImage(
            model = v.pic.ifEmpty { null },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = { Diag.w("img", "失败 " + v.name + " " + (v.pic.ifEmpty { "(空地址)" }) + " " + it.result.throwable) },
            onSuccess = { Diag.w("img", "成功 " + v.name) },
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(10.dp)) {
            Text(v.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
            Text(v.remark, color = Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
fun VodHome(s: UiState) {
    Column(Modifier.fillMaxSize().padding(top = 74.dp, start = 28.dp, end = 28.dp)) {
        // 频道标签与搜索：各占一半宽度，同高
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Config.TABS.forEachIndexed { i, t -> Cell(t.first, s.zone == Zone.TABS && s.tab == i, s.tab == i, Modifier.weight(1f).fillMaxHeight(), 15) }
            }
            Cell("搜索片名", s.zone == Zone.SEARCH, false, Modifier.width(340.dp).fillMaxHeight(), 15)
        }
        Spacer(Modifier.height(14.dp))
        val gs = rememberLazyGridState()
        LaunchedEffect(s.g) { if (s.zone == Zone.GRID) gs.animateScrollToItem(s.g / COLS * COLS) }
        if (s.items.isEmpty()) Text("加载中…", color = Muted, fontSize = 15.sp)
        LazyVerticalGrid(GridCells.Fixed(COLS), state = gs, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(s.items.size) { i -> Poster(s.items[i], s.zone == Zone.GRID && s.g == i, 200.dp) }
        }
    }
}

@Composable
fun SearchPage(s: UiState) {
    Row(Modifier.fillMaxSize().background(Bg).padding(top = 74.dp, start = 28.dp, end = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(494.dp).height(44.dp).clip(RoundedCornerShape(9.dp)).background(Panel).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(s.q.ifEmpty { "输入片名" }, color = if (s.q.isEmpty()) Muted else Color.White, fontSize = 20.sp)
            }
            Spacer(Modifier.height(6.dp))
            KB.chunked(10).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { c, k -> Cell(k, s.zone == Zone.SEARCH_PAGE && s.kb == r * 10 + c, false, Modifier.size(44.dp), 16) }
                }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.results.size) { i -> Poster(s.results[i], s.zone == Zone.RESULTS && s.rg == i, 120.dp) }
        }
    }
}

@Composable
fun VodPlayer(s: UiState) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Video(s.live.player)
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(s.barShow, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xD905070B)).padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        (s.playing?.name ?: "") + (if (s.epNow.isEmpty()) "" else "   " + s.epNow),
                        color = Color.White, fontSize = 16.sp
                    )
                    Text("OK 暂停/播放  ◀▶ 快退/快进  ▲▼ 上/下一集  返回 退出", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 影片详情：左边海报+简介，右边线路+选集。
 * 进页先画列表数据，再补 ac=detail（简介和完整集数经常只在详情接口里）。
 */
@Composable
fun DetailScreen(s: UiState) {
    val d = s.detail ?: return
    val eps = s.dEps
    val cols = dcolsOf(eps.size)
    Box(Modifier.fillMaxSize().background(Bg)) {
        // 海报压暗铺底，避免右侧一大片纯黑
        AsyncImage(
            model = d.pic.ifEmpty { null }, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.20f
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF80B0F15), Color(0xF20B0F15), Color(0xFA0B0F15)))
            )
        )
        Row(
            Modifier.fillMaxSize().padding(top = 76.dp, start = 28.dp, end = 28.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ---- 左：海报 + 片名/元信息 + 简介 ----
            // 电影只有一集：左边放宽、右边选集缩窄，别让简介挤在一条里
            Column(if (cols <= 3) Modifier.weight(1.55f) else Modifier.width(420.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(148.dp).height(198.dp).clip(RoundedCornerShape(9.dp)).background(Panel)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(d.name.take(2), color = Color(0x33FFFFFF), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        }
                        AsyncImage(
                            model = d.pic.ifEmpty { null }, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            onError = { Diag.w("img", "详情海报失败 " + d.name) }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, lineHeight = 25.sp)
                        if (d.meta.isNotEmpty()) Text(d.meta, color = Accent, fontSize = 13.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp))
                        if (d.remark.isNotEmpty()) Text(d.remark, color = Muted, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp))
                        if (d.actor.isNotEmpty()) Text("主演 " + d.actor.replace(",", " / "), color = Muted, fontSize = 12.sp,
                            maxLines = 2, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                        if (d.director.isNotEmpty()) Text("导演 " + d.director.replace(",", " / "), color = Muted, fontSize = 12.sp,
                            maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("简介", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(
                    d.content.ifEmpty { if (s.dLoading) "正在取简介…" else "这个源没提供简介。" },
                    color = Color(0xFFC7D2E1), fontSize = 13.sp, lineHeight = 19.sp,
                    maxLines = 9, overflow = TextOverflow.Ellipsis
                )
            }
            // ---- 右：线路 + 选集 ----
            Column(Modifier.weight(1f)) {
                if (d.lines.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("线路", color = Muted, fontSize = 13.sp)
                        d.lines.take(MaxLines).forEachIndexed { i, _ ->
                            Cell(d.lineLabel(i), s.dFocus && s.dLine == i, s.dLine == i,
                                Modifier.height(34.dp).width(78.dp), 12)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (eps.isEmpty()) "加载中…"
                        else "共 " + eps.size + " 集   当前第 " + (s.dEp + 1) + " 集",
                        color = Muted, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                val gs = rememberLazyGridState()
                LaunchedEffect(s.dLine, s.dEp) {
                    if (!s.dFocus && eps.isNotEmpty()) gs.animateScrollToItem(s.dEp / cols * cols)
                }
                LazyVerticalGrid(
                    GridCells.Fixed(cols), state = gs, contentPadding = PaddingValues(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(eps.size) { i ->
                        Cell(eps[i].name, !s.dFocus && s.dEp == i, s.dEp == i,
                            Modifier.fillMaxWidth().height(40.dp), 12, maxLines = 2)
                    }
                }
            }
        }
        Text(
            "◀▶ 选集   ▲▼ 换行/换线路   OK 播放   返回 上一页",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 12.dp)
        )
    }
}

package com.yg.vodtv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

/* 960x540dp 画布：6 列海报刚好两行、搜索页 8 列键盘 + 4 列结果，比例不再互相挤 */
const val COLS = 6
const val DCOLS = 6
/** 详情页线路最多显示几条（多了放不下，取前 6 条） */
const val MaxLines = 6
/** 搜索页键盘列数（39 键 → 5 行，键盘只占 42% 宽） */
const val KbCols = 8
/** 搜索结果列数 */
const val ResCols = 4

val TopH = 92.dp
val HintH = 46.dp
val PosterH = 170.dp
/** 海报解码尺寸（像素）：960x540dp 画布在 1920 宽屏上是 2 倍密度，格子约 286x340，取略大一点不发虚 */
const val PostW = 320
const val PostH = 380

/** 详情页选集列数：电视剧 6 列；电影/短剧（≤3 集）用 3 列大格子好看也更省地方 */
fun dcolsOf(n: Int) = if (n <= 3) 3 else DCOLS

/* ============================ 播放器 ============================ */

/**
 * 画面比例：0 自动 / 1 适应 / 2 铺满。
 * 自动沿用旧 Java 版判据：|视频AR-容器AR|/max ≤ 4% 就铺满，否则适应（4:3 老源两侧留黑）。
 */
fun resolveResize(mode: Int, vAr: Float, cAr: Float): Int = when (mode) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> if (vAr <= 0f || cAr <= 0f) AspectRatioFrameLayout.RESIZE_MODE_FIT
    else {
        val dev = Math.abs(vAr - cAr) / Math.max(vAr, cAr)
        if (dev <= 0.04f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
}

@Composable
fun Video(p: ExoPlayer, mode: Int, vw: Int, vh: Int, par: Float) {
    val cfg = LocalConfiguration.current
    // 容器比例用控件实际像尺寸算：Configuration 拿到的是「可用区」，会和真正铺满的画面差几个百分点，
    // 恰好能把 16:9 片子误判成「不是同一比例」而留黑边
    var cw by remember { mutableIntStateOf(0) }
    var ch by remember { mutableIntStateOf(0) }
    val cAr = if (cw > 0 && ch > 0) cw.toFloat() / ch
    else if (cfg.screenWidthDp > 0 && cfg.screenHeightDp > 0)
        cfg.screenWidthDp.toFloat() / cfg.screenHeightDp else 16f / 9f
    val vAr = if (vw > 0 && vh > 0) vw * (if (par > 0f) par else 1f) / vh else 0f
    val rm = resolveResize(mode, vAr, cAr)
    AndroidView(
        factory = { c -> PlayerView(c).apply { useController = false; player = p; resizeMode = rm } },
        update = { v -> if (v.resizeMode != rm) v.resizeMode = rm },
        modifier = Modifier.fillMaxSize().onSizeChanged { cw = it.width; ch = it.height }
    )
    LaunchedEffect(mode, rm, vw, vh) {
        Diag.w("play", "画面 " + SCALE_SHORT[mode] + " 视频AR=" + (Math.round(vAr * 100) / 100f) +
            " 容器AR=" + (Math.round(cAr * 100) / 100f) + " resize=" + rm)
    }
}

/* ============================ 外壳 ============================ */

@Composable
fun App(s: UiState) {
    val ctx = LocalContext.current
    val ver = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "2.31"
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0E14), Bg, BgDeep)))
            // 顶部暖黄光晕：设计稿最抢眼的那层氛围，压在内容下面
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0x26F5B942), Color(0x0CF5B942), Color.Transparent),
                    center = Offset(size.width * 0.30f, -size.height * 0.18f),
                    radius = size.width * 0.80f
                )
            )
        }
    ) {
        // 搜索页自带底板，底下不再画片库：既不掉帧，也不会透出后面的海报墙
        if (s.zone != Zone.SEARCH_PAGE && s.zone != Zone.RESULTS) {
            if (s.mode == "live") LiveScreen(s) else VodHome(s)
        }
        if (s.zone == Zone.DETAIL) DetailScreen(s)
        if (s.zone == Zone.SEARCH_PAGE || s.zone == Zone.RESULTS) SearchPage(s)
        if (s.zone == Zone.VOD_PLAY) VodPlayer(s)
        if (s.zone == Zone.PANEL) ChannelPanel(s)

        val topOn = when (s.zone) {
            // 播放页有自己的标题条和「画面」胶囊，再来一条顶栏会把播放信息压在下面
            Zone.PLAY, Zone.VOD_PLAY, Zone.PANEL, Zone.SEARCH_PAGE, Zone.RESULTS -> false
            else -> true
        }
        TopBar(s, topOn)

        val hintOn = s.zone != Zone.PLAY && s.zone != Zone.VOD_PLAY && s.zone != Zone.PANEL
        if (hintOn) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                HintBar(
                    when (s.zone) {
                        Zone.SEARCH_PAGE, Zone.RESULTS -> listOf(
                            "方向" to "选字", "OK" to "输入", "结果" to "看片单", "BACK" to "收起键盘"
                        )
                        Zone.DETAIL -> listOf(
                            "左右" to "选集/线路", "上下" to "换行", "OK" to "播放", "BACK" to "返回"
                        )
                        else -> listOf(
                            "方向" to "移动焦点", "OK" to "进入", "BACK" to "返回", "MENU" to "画面比例"
                        )
                    }, ver
                )
            }
        }

        if (s.toast.isNotEmpty() && s.upd == null) {
            Box(Modifier.fillMaxSize().padding(bottom = HintH + 24.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    s.toast, color = TextHi, fontSize = 13.sp,
                    modifier = Modifier.aurora(Color.Black, 0.5f, 10.dp, 11.dp)
                        .clip(RoundedCornerShape(11.dp)).background(Color(0xF2101720))
                        .border(1.dp, Color(0xFF2A3546), RoundedCornerShape(11.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }

        // 更新浮层压在最上层：下载进度要看得到，遥控器也只在这里响应
        val u = s.upd
        if (u != null) UpdDialog(u)
    }
}

/* ============================ 在线更新浮层 ============================ */

@Composable
fun UpdDialog(u: UpdState) {
    val heading = when (u.phase) {
        UpdPhase.DOWNLOAD -> "正在下载 ${u.tag}"
        UpdPhase.READY -> "正在拉起安装器"
        UpdPhase.LAUNCH -> "已就绪，等待安装"
        UpdPhase.ERROR -> "更新失败"
    }
    val tip = when (u.phase) {
        UpdPhase.DOWNLOAD -> "下载中可以按返回取消；下完会自动打开系统安装界面"
        UpdPhase.READY -> "马上跳到系统安装页，别走开"
        UpdPhase.LAUNCH -> "用遥控器选「安装」；装完 App 会自己关掉，重新打开就是新版"
        UpdPhase.ERROR -> "按 OK 重试，按返回先关掉这个框"
    }
    val barColor = when (u.phase) {
        UpdPhase.ERROR -> Bad
        UpdPhase.LAUNCH, UpdPhase.READY -> Ok
        else -> Accent
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xC4060A10)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(440.dp)
                .aurora(if (u.phase == UpdPhase.ERROR) Bad else Accent, 0.35f, 18.dp, 22.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.dp, if (u.phase == UpdPhase.ERROR) Bad else Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(heading, color = TextHi, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("当前 ${u.cur}  →  ${u.tag}", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            // 进度条：DOWNLOAD 用真实百分比，其余阶段直接给满格表示媒体已就绪
            val frac = when (u.phase) {
                UpdPhase.DOWNLOAD -> if (u.total > 0) (u.done.toFloat() / u.total) else 0f
                UpdPhase.ERROR -> if (u.total > 0) (u.done.toFloat() / u.total) else 0f
                else -> 1f
            }.coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0E141C))
            ) {
                Box(
                    Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                u.line,
                color = if (u.phase == UpdPhase.ERROR) Bad else TextHi,
                fontSize = 13.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(tip, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

/* ============================ 顶栏 ============================ */

@Composable
fun TopBar(s: UiState, visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        Row(
            Modifier.fillMaxWidth().height(TopH)
                .background(Brush.verticalGradient(listOf(Color(0xE60A0E14), Color(0x000A0E14))))
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark()
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavCard(
                    "电视直播", "500+ 高清频道", "▶",
                    focused = s.zone == Zone.BAR && s.bar == 0,
                    active = s.mode == "live"
                )
                NavCard(
                    "影视点播", "多源聚合 · 随时点", "▤",
                    focused = s.zone == Zone.BAR && s.bar == 1,
                    active = s.mode == "vod"
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(s.online)
                ClockText()
                Spacer(Modifier.width(4.dp))
                ToolPill("自检", s.zone == Zone.BAR && s.bar == 2, 46.dp)
                ToolPill("清缓存", s.zone == Zone.BAR && s.bar == 3, 54.dp)
                ToolPill("更新", s.zone == Zone.BAR && s.bar == 4, 46.dp)
            }
        }
    }
}

/** 顶栏主卡片：选中=金色描边+外发光+暖色内渐变（设计稿里的双卡片） */
@Composable
fun NavCard(title: String, sub: String, icon: String, focused: Boolean, active: Boolean) {
    val sc by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "nav")
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.width(172.dp).height(58.dp).graphicsLayer { scaleX = sc; scaleY = sc }
            .aurora(Accent, if (focused) 0.34f else if (active) 0.14f else 0f, 13.dp, 14.dp)
            .clip(shape)
            .background(
                if (active) Brush.horizontalGradient(listOf(Color(0xFF2C2410), Color(0xFF18202C)))
                else Brush.horizontalGradient(listOf(Panel, Panel))
            )
            .then(
                when {
                    focused -> Modifier.border(2.dp, Accent, shape)
                    active -> Modifier.border(1.dp, Color(0x66F5B942), shape)
                    else -> Modifier.border(1.dp, Line, shape)
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                Modifier.size(31.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (active) Brush.linearGradient(listOf(Color(0xFFF9CE63), AccentDeep)) else Brush.linearGradient(listOf(Panel2, Panel2))),
                contentAlignment = Alignment.Center
            ) { Text(icon, color = if (active) Ink else Muted, fontSize = 12.sp) }
            Column {
                Text(
                    title, color = if (focused || active) TextHi else Muted, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1
                )
                Text(
                    sub, color = if (active) Color(0xFFB9A469) else Muted, fontSize = 9.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/* ============================ 直播 ============================ */

@Composable
fun LiveScreen(s: UiState) {
    Box(Modifier.fillMaxSize()) {
        Video(s.live.player, s.scaleMode, s.live.vw, s.live.vh, s.live.par)
        val c = s.live.channels.getOrNull(s.live.cur)
        if (c == null || s.live.failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (c == null) "正在加载频道…" else "${c.name} 暂时无法播放，按上下键换台",
                    color = Muted, fontSize = 17.sp
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(26.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(s.osd && c != null, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xD9080C12))
                        .border(1.dp, Color(0xFF1E2936), RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("第 ${s.live.cur + 1} 台 · ${c?.name ?: ""}", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("↑↓ 换台   OK 频道列表   返回 卡片栏", color = Muted, fontSize = 11.sp)
                    }
                    Text(
                        "画面 " + SCALE_SHORT[s.scaleMode], color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelPanel(s: UiState) {
    Box(Modifier.fillMaxSize().background(Color(0xC004070B))) {
        Column(
            Modifier.fillMaxHeight().width(430.dp).background(Color(0xFA0A0E15))
                .border(1.dp, Color(0xFF1B2734)).padding(vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("频道", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Text("${s.live.channels.size} 台", color = Accent, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(s.live.groups.getOrNull(s.pc) ?: "", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.weight(1f)) {
            Column(
                Modifier.width(128.dp).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                s.live.groups.forEachIndexed { i, n ->
                    Cell(n, s.col == 0 && s.pc == i, s.pc == i, Modifier.fillMaxWidth().height(34.dp), 12)
                }
            }
            val st = rememberLazyListState()
            LaunchedEffect(s.pi, s.pc) { st.animateScrollToItem((s.pi - 4).coerceAtLeast(0)) }
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 10.dp), state = st,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(s.live.inGroup(s.pc)) { k, ic ->
                    Cell(
                        "${ic.index + 1}  ${ic.value.name}", s.col == 1 && s.pi == k, ic.index == s.live.cur,
                        Modifier.fillMaxWidth().height(34.dp), 12
                    )
                }
            }
            }
        }
    }
}

/* ============================ 点播首页 ============================ */

@Composable
fun VodHome(s: UiState) {
    Column(Modifier.fillMaxSize().padding(top = TopH, start = 22.dp, end = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            val ts = rememberLazyListState()
            LaunchedEffect(s.tab) { ts.animateScrollToItem((s.tab - 2).coerceAtLeast(0)) }
            SearchPill("搜索片名 / 拼音", s.zone == Zone.SEARCH)
            Box(Modifier.weight(1f)) {
                LazyRow(
                    Modifier.fillMaxWidth(), state = ts,
                    contentPadding = PaddingValues(end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(s.cats) { i, c ->
                        Cell(
                            c.name, s.zone == Zone.TABS && s.tab == i, s.tab == i,
                            Modifier.width(if (c.name.length >= 3) 86.dp else 64.dp).fillMaxHeight(), 12
                        )
                    }
                }
                // 右端渐隐：分类比一屏多，滚到边上时别让半个字露出来
                Box(
                    Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(0f to Color.Transparent, 1f to Bg))
                )
            }
        }
        Spacer(Modifier.height(11.dp))
        if (s.items.isEmpty()) {
            SkeletonGrid()
        } else {
            val gs = rememberLazyGridState()
            LaunchedEffect(s.g) { if (s.zone == Zone.GRID) gs.animateScrollToItem(s.g / COLS * COLS) }
            LazyVerticalGrid(
                GridCells.Fixed(COLS), state = gs,
                contentPadding = PaddingValues(top = 2.dp, bottom = HintH + 10.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                items(s.items.size) { i -> Poster(s.items[i], s.zone == Zone.GRID && s.g == i, PosterH) }
            }
        }
    }
}

@Composable
fun SearchPill(text: String, focused: Boolean, w: Dp = 186.dp) {
    val shape = RoundedCornerShape(19.dp)
    Row(
        Modifier.width(w).height(38.dp)
            .aurora(Accent, if (focused) 0.26f else 0f, 11.dp, 19.dp)
            .clip(shape).background(Color(0xFF101821))
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier.border(1.dp, Line, shape))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Lens(if (focused) Accent else Muted, 12.dp)
        Text(text, color = if (focused) TextHi else Muted, fontSize = 11.sp, maxLines = 1)
    }
}

/** 首屏加载用骨架块，比一行「加载中…」有质感，也不会让海报墙高度跳变 */
@Composable
fun SkeletonGrid() {
    var ph by remember { mutableStateOf(0.42f) }
    LaunchedEffect(Unit) {
        while (true) {
            ph = if (ph < 0.55f) 0.9f else 0.4f
            delay(850)
        }
    }
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    repeat(COLS) {
                        Box(
                            Modifier.weight(1f).height(PosterH).graphicsLayer(alpha = ph)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.verticalGradient(listOf(Color(0xFF141B26), Color(0xFF0E141D))))
                                .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
        }
        Text(
            "正在加载片单…", color = Muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun Poster(v: Vod, focused: Boolean, h: Dp) {
    val sc by animateFloatAsState(if (focused) 1.05f else 1f, tween(160), label = "poster")
    val shape = RoundedCornerShape(12.dp)
    val scoreOn = v.score.toFloatOrNull()?.let { it > 0f } == true
    // 海报按固定小尺寸解码：源站动辄 300x400 以上，12 张一起解在这台老电视上要两三秒；
    // 固定成 PostW x PostH 后解码量降到一成，切分类首屏基本跟手（尺寸要和 warmImages 一致才共用缓存 key）
    val ctx = LocalContext.current
    val req = remember(v.pic) {
        if (v.pic.isEmpty()) null
        else coil.request.ImageRequest.Builder(ctx).data(v.pic).size(PostW, PostH).build()
    }
    Box(
        Modifier.fillMaxWidth().height(h).graphicsLayer { scaleX = sc; scaleY = sc }
            .aurora(Accent, if (focused) 0.36f else 0f, 16.dp, 12.dp)
            .clip(shape).background(Panel)
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier.border(1.dp, Color(0x14FFFFFF), shape))
    ) {
        // 底衬：图没下来（或源站挂图）时露出片名首字，别让整格变黑砖
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(v.name.take(2), color = Color(0x2EFFFFFF), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        AsyncImage(
            model = req,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = { Diag.w("img", "失败 " + v.name + " " + (v.pic.ifEmpty { "(空地址)" }) + " " + it.result.throwable) },
        )
        if (scoreOn) {
            Text(
                "★ " + v.score, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0000000), Color(0xF2000000))))
                .padding(start = 9.dp, end = 9.dp, top = 16.dp, bottom = 8.dp)
        ) {
            Text(v.name, color = TextHi, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            Text(v.remark, color = if (focused) Accent else Muted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

/* ============================ 搜索 ============================ */

@Composable
fun SearchPage(s: UiState) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0E14), Bg, BgDeep)))
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0x1FF5B942), Color(0x0AF5B942), Color.Transparent),
                    center = Offset(size.width * 0.18f, -size.height * 0.10f),
                    radius = size.width * 0.66f
                )
            )
        }
    ) {
        Row(
            Modifier.fillMaxSize().padding(top = 16.dp, start = 22.dp, end = 22.dp, bottom = HintH),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // ---- 左：键盘台。362dp = 屏宽的 37%（上一版 403dp 占 42%，结果区被挤成 3 列）----
            Column(
                Modifier.width(362.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xD9101721))
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 6.dp, vertical = 11.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 1.dp)
                        .aurora(Accent, if (s.zone == Zone.SEARCH_PAGE) 0.24f else 0f, 12.dp, 21.dp)
                        .clip(RoundedCornerShape(21.dp)).background(Color(0xFF0C141C))
                        .then(
                            if (s.zone == Zone.SEARCH_PAGE) Modifier.border(2.dp, Accent, RoundedCornerShape(21.dp))
                            else Modifier.border(1.dp, Line, RoundedCornerShape(21.dp))
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Lens(if (s.q.isEmpty()) Muted else Accent, 13.dp)
                    Text(
                        s.q.ifEmpty { "输入片名或拼音首字母" },
                        color = if (s.q.isEmpty()) Muted else TextHi, fontSize = 14.sp, maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    if (s.q.isNotEmpty()) Text("${s.q.length}/12", color = Muted, fontSize = 9.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    s.searchNote.ifEmpty { "打字母就行：qyn = 庆余年" },
                    color = if (s.searchNote.startsWith("没搜到")) Accent else Muted,
                    fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 5.dp, end = 5.dp)
                )
                Spacer(Modifier.height(8.dp))
                KB.chunked(KbCols).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed { c, k ->
                            Cell(
                                k, s.zone == Zone.SEARCH_PAGE && s.kb == r * KbCols + c, false,
                                Modifier.width(40.dp).height(33.dp), 13
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text("· 打 2~3 个字母就会陆续出结果", color = Color(0xFF65718A), fontSize = 9.5.sp, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Text("· 片源按到达顺序实时补齐，不用等最慢的", color = Color(0xFF65718A), fontSize = 9.5.sp, maxLines = 1)
                }
            }

            // ---- 右：结果。536dp 宽 4 列，海报 127×168 ----
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索结果", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    if (s.results.isNotEmpty()) Text("${s.results.size} 条", color = Accent, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (s.zone == Zone.RESULTS) "OK 看详情   BACK 回键盘" else "按「结果」进片单",
                        color = Muted, fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(9.dp))
                if (s.results.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (s.searchNote.startsWith("搜索中")) "正在同时查询所有片源，边到边显示…"
                            else "打几个字母，片子会陆续出来",
                            color = Muted, fontSize = 12.sp
                        )
                    }
                } else {
                    val gs = rememberLazyGridState()
                    LaunchedEffect(s.rg) { if (s.zone == Zone.RESULTS) gs.animateScrollToItem(s.rg / ResCols * ResCols) }
                    LazyVerticalGrid(
                        GridCells.Fixed(ResCols), state = gs,
                        contentPadding = PaddingValues(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(s.results.size) { i ->
                            Poster(s.results[i], s.zone == Zone.RESULTS && s.rg == i, 168.dp)
                        }
                    }
                }
            }
        }
    }
}

/* ============================ 播放页 ============================ */

private fun fmt(ms: Int): String {
    val t = ms / 1000
    return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

@Composable
fun VodPlayer(s: UiState) {
    val p = s.live.player
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Video(p, s.scaleMode, s.live.vw, s.live.vh, s.live.par)

        var pos by remember { mutableIntStateOf(0) }
        var dur by remember { mutableIntStateOf(0) }
        LaunchedEffect(s.barShow) {
            if (!s.barShow) return@LaunchedEffect
            while (true) {
                pos = (runCatching { p.currentPosition }.getOrNull() ?: 0L).coerceIn(0L, 24L * 3600 * 1000).toInt()
                val d = (runCatching { p.duration }.getOrNull() ?: 0L)
                dur = if (d > 0) d.coerceAtMost(24L * 3600 * 1000).toInt() else 0
                delay(500)
            }
        }

        AnimatedVisibility(
            s.barShow, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                    .padding(start = 26.dp, end = 26.dp, top = 18.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.playing?.name ?: "", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (s.epNow.isNotEmpty()) Text(
                        s.epNow + (if (s.epTotal > 1) "   第 " + (s.epIdx + 1) + "/" + s.epTotal + " 集" else ""),
                        color = Muted, fontSize = 11.sp
                    )
                }
                Text(
                    "画面 " + SCALE_SHORT[s.scaleMode], color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        AnimatedVisibility(
            s.barShow, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 26.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(fmt(pos), color = TextHi, fontSize = 10.sp)
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                        val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        if (f > 0.001f) Box(
                            Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Accent)
                        )
                    }
                    Text(if (dur > 0) fmt(dur) else "--:--", color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("OK 暂停/播放   ◀▶ 快退/快进   ▲▼ 上/下一集   返回 退出", color = Muted, fontSize = 11.sp)
                    Text("MENU 切换画面比例", color = Accent, fontSize = 11.sp)
                }
            }
        }
    }
}

/* ============================ 详情 ============================ */

@Composable
fun DetailScreen(s: UiState) {
    val d = s.detail ?: return
    val eps = s.dEps
    val cols = dcolsOf(eps.size)
    Box(Modifier.fillMaxSize().background(Bg)) {
        AsyncImage(
            model = d.pic.ifEmpty { null }, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.18f
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF60B0F15), Color(0xEE0B0F15), Color(0xF80B0F15)))
            )
        )
        Row(
            Modifier.fillMaxSize().padding(top = TopH, start = 22.dp, end = 22.dp, bottom = HintH + 6.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---- 左：海报 + 信息 + 简介 ----
            Column(Modifier.width(374.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(
                        Modifier.width(122.dp).height(166.dp).clip(RoundedCornerShape(12.dp))
                            .background(Panel).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(d.name.take(2), color = Color(0x2EFFFFFF), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                        AsyncImage(
                            model = d.pic.ifEmpty { null }, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            onError = { Diag.w("img", "详情海报失败 " + d.name) }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.name, color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, lineHeight = 23.sp
                        )
                        if (d.meta.isNotEmpty()) Text(
                            d.meta, color = Accent, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (d.remark.isNotEmpty()) Text(
                            d.remark, color = Muted, fontSize = 10.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (d.actor.isNotEmpty()) Text(
                            "主演 " + d.actor.replace(",", " / "), color = Muted, fontSize = 10.sp,
                            maxLines = 3, lineHeight = 14.sp, modifier = Modifier.padding(top = 5.dp),
                            overflow = TextOverflow.Ellipsis
                        )
                        if (d.director.isNotEmpty()) Text(
                            "导演 " + d.director.replace(",", " / "), color = Muted, fontSize = 10.sp,
                            maxLines = 1, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.height(11.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("简介", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.height(1.dp).weight(1f).background(Color(0x1AFFFFFF)))
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    d.content.ifEmpty { if (s.dLoading) "正在取简介…" else "这个源没提供简介。" },
                    color = Color(0xFFC2CCDA), fontSize = 11.sp, lineHeight = 17.sp,
                    maxLines = 8, overflow = TextOverflow.Ellipsis
                )
            }

            // ---- 右：线路 + 选集 ----
            Column(Modifier.weight(1f)) {
                if (d.lines.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("线路", color = Muted, fontSize = 11.sp)
                        d.lines.take(MaxLines).forEachIndexed { i, _ ->
                            Cell(
                                d.lineLabel(i), s.dFocus && s.dLine == i, s.dLine == i,
                                Modifier.height(30.dp).width(66.dp), 11
                            )
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (eps.isEmpty()) "加载中…" else "共 " + eps.size + " 集   当前第 " + (s.dEp + 1) + " 集",
                        color = Muted, fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                val gs = rememberLazyGridState()
                LaunchedEffect(s.dLine, s.dEp) {
                    if (!s.dFocus && eps.isNotEmpty()) gs.animateScrollToItem(s.dEp / cols * cols)
                }
                LazyVerticalGrid(
                    GridCells.Fixed(cols), state = gs, contentPadding = PaddingValues(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(eps.size) { i ->
                        Cell(
                            eps[i].name, !s.dFocus && s.dEp == i, s.dEp == i,
                            Modifier.fillMaxWidth().height(34.dp), 11, maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

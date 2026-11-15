package com.yg.vodtv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ========================= 设计系统 =========================
 * 对照老板给的 Stitch 设计稿提炼：近黑底 + 顶部暖黄光晕、金色发光选中态、
 * 品牌头（YG 影视TV PREMIUM）、右上状态胶囊 + 金色时钟、底部键帽提示条。
 * 电视实测画布只有 960x540dp（1920x1080 / density 320），所有尺寸按此重排。
 */

val Bg = Color(0xFF0B0F15)
val BgDeep = Color(0xFF06090D)
val Panel = Color(0xFF141C27)
val Panel2 = Color(0xFF1E2A38)
val Line = Color(0xFF223040)
val Accent = Color(0xFFF5B942)
val AccentDeep = Color(0xFFE0A22C)
val Muted = Color(0xFF8A96A8)
val TextHi = Color(0xFFF3F6FA)
/** accent 底色上的深色字，别用纯黑，压一点更贵气 */
val Ink = Color(0xFF20160A)
val Ok = Color(0xFF4BE07A)
val Bad = Color(0xFFE0574B)

/**
 * 外发光。故意不用 RenderEffect/blur：电视是 Android 5.1，没有硬件 blur，
 * 用三层递减的半透明圆角矩形叠一圈，效果接近而且不吃 GPU。
 */
fun Modifier.aurora(color: Color = Accent, alpha: Float = 0.22f, spread: Dp = 12.dp, r: Dp = 14.dp): Modifier =
    drawBehind {
        if (alpha < 0.01f) return@drawBehind
        val g = spread.toPx()
        val rad = r.toPx()
        for (i in 3 downTo 1) {
            val e = g * i / 3f
            drawRoundRect(
                color = color.copy(alpha = alpha / (i + 1.4f)),
                topLeft = Offset(-e, -e),
                size = Size(size.width + e * 2f, size.height + e * 2f),
                cornerRadius = CornerRadius(rad + e, rad + e)
            )
        }
    }

/** 通用格子：分类 chip / 选集 / 键盘键都用它，选中=金色底黑字，聚焦=白描边 */
@Composable
fun Cell(text: String, focused: Boolean, selected: Boolean, modifier: Modifier, size: Int = 15, maxLines: Int = 1) {
    val sc by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "cell")
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .aurora(Accent, if (focused) 0.26f else 0f, 10.dp, 10.dp)
            .clip(shape)
            .background(if (selected) Accent else Panel)
            .then(if (focused && !selected) Modifier.border(2.dp, Color.White, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Ink else if (focused) TextHi else Muted,
            fontSize = size.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, lineHeight = (size + 2).sp,
            fontWeight = if (focused || selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/** 放大镜：老固件字体里没有 ⌕ 这类符号，直接画，绝不显示成方块 */
@Composable
fun Lens(c: Color, s: Dp = 13.dp) {
    Canvas(Modifier.size(s)) {
        val d = size.minDimension
        val sw = d * 0.11f
        drawCircle(c, d * 0.30f, center = Offset(d * 0.42f, d * 0.42f), style = Stroke(width = sw))
        drawLine(c, Offset(d * 0.64f, d * 0.64f), Offset(d * 0.92f, d * 0.92f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** 品牌头：金色渐变方块 + YG 影视TV + PREMIUM 徽标 */
@Composable
fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp)
                .aurora(Accent, 0.30f, 10.dp, 11.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF9CE63), AccentDeep))),
            contentAlignment = Alignment.Center
        ) { Text("▶", color = Ink, fontSize = 14.sp) }
        Spacer(Modifier.width(9.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("YG 影视TV", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(
                    "PREMIUM", color = Accent, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
                    modifier = Modifier.border(1.dp, Color(0x99F5B942), RoundedCornerShape(50))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Text("定制专版 · 双源聚合", color = Muted, fontSize = 8.5.sp, lineHeight = 11.sp)
        }
    }
}

/** 右上状态胶囊：绿点 + 分辨率 + 连接状态，对应设计稿「1080P · 已连接」 */
@Composable
fun StatusPill(online: Boolean) {
    val dot = if (online) Ok else Bad
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF101822))
            .border(1.dp, Line, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).aurora(dot, 0.9f, 4.dp, 3.dp).clip(CircleShape).background(dot))
        Text(if (online) "1080P · 已连接" else "离线", color = Muted, fontSize = 9.sp)
    }
}

private val HHmm = SimpleDateFormat("HH:mm", Locale.US)

/** 金色时钟，设计稿右上角那个 */
@Composable
fun ClockText() {
    var t by remember { mutableStateOf(HHmm.format(Date())) }
    LaunchedEffect(Unit) { while (true) { delay(5000); t = HHmm.format(Date()) } }
    Text(t, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

/** 工具胶囊（自检/清缓存/更新），弱化处理，不和两张主卡片抢视线 */
@Composable
fun ToolPill(text: String, focused: Boolean, w: Dp) {
    val sc by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "tool")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.width(w).height(28.dp).graphicsLayer { scaleX = sc; scaleY = sc }
            .aurora(Accent, if (focused) 0.30f else 0f, 9.dp, 14.dp)
            .clip(shape).background(if (focused) Panel2 else Color(0xFF111923))
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier.border(1.dp, Line, shape)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (focused) Accent else Muted, fontSize = 10.sp, maxLines = 1)
    }
}

/** 底部提示条上的键帽 */
@Composable
fun KeyCap(t: String) {
    Text(
        t, color = Color(0xFFD7E0EC), fontSize = 9.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF1B2531))
            .border(1.dp, Color(0xFF2A3546), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp), lineHeight = 11.sp
    )
}

/** 底部整条按键提示：左边键帽说明，右边版本与加速状态（设计稿同款） */
@Composable
fun HintBar(items: List<Pair<String, String>>, ver: String) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).background(Color(0xFF0A0D12))
            .drawBehind { drawRect(Color(0xFF1C2734), size = Size(size.width, 1f)) }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items.forEach { (k, d) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyCap(k)
                Text(d, color = Color(0xFF93A0B2), fontSize = 9.5.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("YG 影视 定制专版 v$ver · 专线加速中", color = Color(0xFF5E6A7C), fontSize = 9.sp, maxLines = 1)
    }
}

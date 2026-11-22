package com.yg.vodtv

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 直播控制器。防 bug 的几个关键点：
 * 1. 全程只有一个 ExoPlayer（点播也复用它）
 * 2. 频道索引 cur 是唯一状态源，面板/OSD 都读它
 * 3. 换台 300ms 防抖，并取消上一次未执行的加载
 * 4. 播放出错自动切换同名频道的下一个源
 */
class LiveController(private val ctx: Context, private val scope: CoroutineScope) {
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var cur by mutableIntStateOf(0); private set
    var failed by mutableStateOf(false); private set
    /** 当前是不是点播放的东西：点播出错要提示，不能自动滚到下一个频道 */
    var vod = false
    /** 视频原始尺寸与像素宽高比：画面比例「自动」档靠它和屏幕比算，必须参与重组 */
    var vw by mutableIntStateOf(0); private set
    var vh by mutableIntStateOf(0); private set
    var par by mutableFloatStateOf(1f); private set
    /** 点播失败时给界面报个信（参数是提示文案） */
    var onVodFail: ((String) -> Unit)? = null
    private var vodAlts: List<String> = emptyList()
    private var vodAlt = 0
    private var src = 0
    private var job: Job? = null
    /** 原始分组（频道表自带的那几个），不含「常看」 */
    private val baseGroups: List<String> get() = channels.map { it.group }.distinct()

    /**
     * 常看频道（存的是台名，不存索引）：频道表每次启动会从 assets/GitHub 重载，
     * 索引会错位，名字才是稳的。按最近看过的顺序，最多 12 台。
     */
    var recent by mutableStateOf<List<String>>(emptyList()); private set

    /** 面板左栏：有记录就把「常看」顶到第一格 */
    val groups: List<String>
        get() {
            val b = baseGroups
            return if (recent.isEmpty()) b else listOf(RECENT) + b
        }

    /**
     * 音量回写（移植自旧版 v2.20）：海信固件在解码器起音时会把扬声器增益推满，
     * Android 侧 index 不变；按一下音量键即可对齐。这里在就绪/出画时把当前 index
     * 原样再写一遍——音量值不变、不弹 OSD，相当于替用户做这次对齐。
     */
    private fun resyncVolume(why: String) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, cur, 0)
            Diag.w("play", "音量回写 " + cur + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "（" + why + "）")
        } catch (t: Throwable) {
            Diag.w("play", "音量回写失败 " + t.message)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            /** 老电视上 screencap 抓不到硬解画面，只能靠日志判断到底出没出画 */
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "缓冲中"
                    Player.STATE_READY -> { resyncVolume("就绪"); "就绪" }
                    Player.STATE_ENDED -> "播完"
                    else -> state.toString()
                }
                Diag.w("play", "状态 " + name + " 位置 " + player.currentPosition + "ms")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Diag.w("play", if (isPlaying) "开始播放" else "暂停/停住")
            }

            override fun onRenderedFirstFrame() { resyncVolume("出画") }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                vw = videoSize.width; vh = videoSize.height
                par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                Diag.w("play", "画面 " + videoSize.width + "x" + videoSize.height + " par=" + par)
            }

            override fun onPlayerError(error: PlaybackException) {
                Diag.w("play", "播放出错 " + error.errorCodeName + " " + error.message)
                if (!vod) { nextSource(); return }
                // 点播：换同名片的下一路地址；全挂才提示
                if (vodAlt + 1 < vodAlts.size) {
                    vodAlt++
                    Diag.w("play", "换备用线路 #" + (vodAlt + 1) + "/" + vodAlts.size + " " + vodAlts[vodAlt])
                    player.setMediaItem(MediaItem.fromUri(vodAlts[vodAlt]))
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    onVodFail?.invoke("这个片子暂时播放不了，换一部试试")
                }
            }
        })
    }

    suspend fun load() {
        channels = runCatching { LiveData.table(ctx) }.getOrDefault(emptyList())
        val last = Prefs.channel(ctx)
        cur = channels.indexOfFirst { it.name == last }.coerceAtLeast(0)
        refreshRecent()
    }

    /** 读一次常看列表；频道表里已经没有的台（下架/改名）要顺手剔掉，否则面板里出现按不进去的格子 */
    fun refreshRecent() {
        val names = channels.map { it.name }.toHashSet()
        recent = History.liveTop(ctx, 12).filter { names.contains(it) }
    }

    private fun start() {
        val c = channels.getOrNull(cur) ?: return
        failed = false
        player.setMediaItem(MediaItem.fromUri(c.urls[src]))
        player.prepare(); player.playWhenReady = true
    }

    private fun nextSource() {
        val c = channels.getOrNull(cur) ?: return
        if (src + 1 < c.urls.size) { src++; start() } else failed = true
    }

    fun startCurrent() { src = 0; start() }

    /** 点播：alts 是同名片的候选地址，播放中出错会自动往后换 */
    fun playVod(url: String, alts: List<String>) {
        vod = true; vodAlts = alts; vodAlt = 0
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare(); player.playWhenReady = true
    }
    fun stop() { job?.cancel(); player.stop() }

    fun switchTo(i: Int, immediate: Boolean = false) {
        if (channels.isEmpty()) return
        cur = ((i % channels.size) + channels.size) % channels.size
        Prefs.setChannel(ctx, channels[cur].name)
        // 切台即记：常看列表的顺序就是最近观看看过的顺序
        History.touchLive(ctx, channels[cur].name)
        refreshRecent()
        src = 0; job?.cancel()
        if (immediate) start() else job = scope.launch { delay(300); start() }
    }

    /** 某一组的频道；RECENT 组按常看顺序排，其余按频道表原顺序 */
    fun inGroup(g: Int): List<IndexedValue<Channel>> {
        val name = groups.getOrNull(g) ?: return emptyList()
        if (name == RECENT) {
            val map = channels.withIndex().associateBy { it.value.name }
            return recent.mapNotNull { map[it] }
        }
        return channels.withIndex().filter { it.value.group == name }
    }

    fun sizeOf(g: Int) = inGroup(g).size
    fun globalIndex(g: Int, i: Int) = inGroup(g).getOrNull(i)?.index ?: cur
    fun locate(): Pair<Int, Int> {
        val c = channels.getOrNull(cur) ?: return 0 to 0
        // 常看组在最前面：正在看的这台如果也在常看里，面板默认落在常看组更顺手
        val gs = groups
        val ri = gs.indexOf(RECENT)
        if (ri >= 0) {
            val k = inGroup(ri).indexOfFirst { it.index == cur }
            if (k >= 0) return ri to k
        }
        val base = gs.indexOf(c.group)
        return base to channels.filter { it.group == c.group }.indexOf(c)
    }
    fun release() { player.release() }

    companion object {
        /** 「常看」是虚拟分组，不在频道表里；用不可见字符避免和真实组名撞车 */
        const val RECENT = "★ 常看"
    }
}

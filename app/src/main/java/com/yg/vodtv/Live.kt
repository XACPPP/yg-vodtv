package com.yg.vodtv

import android.content.Context
import androidx.compose.runtime.getValue
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
    /** 点播失败时给界面报个信（参数是提示文案） */
    var onVodFail: ((String) -> Unit)? = null
    private var vodAlts: List<String> = emptyList()
    private var vodAlt = 0
    private var src = 0
    private var job: Job? = null
    val groups: List<String> get() = channels.map { it.group }.distinct()

    init {
        player.addListener(object : Player.Listener {
            /** 老电视上 screencap 抓不到硬解画面，只能靠日志判断到底出没出画 */
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "缓冲中"
                    Player.STATE_READY -> "就绪"
                    Player.STATE_ENDED -> "播完"
                    else -> state.toString()
                }
                Diag.w("play", "状态 " + name + " 位置 " + player.currentPosition + "ms")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Diag.w("play", if (isPlaying) "开始播放" else "暂停/停住")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Diag.w("play", "画面 " + videoSize.width + "x" + videoSize.height)
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
        src = 0; job?.cancel()
        if (immediate) start() else job = scope.launch { delay(300); start() }
    }

    fun sizeOf(g: Int) = channels.count { it.group == groups.getOrNull(g) }
    fun inGroup(g: Int) = channels.withIndex().filter { it.value.group == groups.getOrNull(g) }
    fun globalIndex(g: Int, i: Int) = inGroup(g).getOrNull(i)?.index ?: cur
    fun locate(): Pair<Int, Int> {
        val c = channels.getOrNull(cur) ?: return 0 to 0
        return groups.indexOf(c.group) to channels.filter { it.group == c.group }.indexOf(c)
    }
    fun release() { player.release() }
}

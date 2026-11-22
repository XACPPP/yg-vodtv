package com.yg.vodtv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

class MainActivity : ComponentActivity() {
    private var state: UiState? = null

    /**
     * 遥控器「直播键 / 影视键」经壳 APK（com.jamdeo.tv.vod）转进来时带 mode=live|vod。
     * singleTask 的二次拉起走 onNewIntent，不进 onCreate，所以两条路都要接。
     * 冷启动时 setContent 还没建好 UiState，先存在这里，等界面起来后由 LaunchedEffect 消费。
     */
    @Volatile
    private var pendingRoute: String? = null

    private fun readRoute(intent: android.content.Intent?) {
        // 路由扫频会每 300ms 把本界面顶回前台一次，那些只是"回前台"，不能重放切换动作。
        if (intent?.action == "com.jamdeo.tv.vod.ASSERT") return
        val m = intent?.getStringExtra("mode") ?: return
        if (m != "live" && m != "vod") return
        intent.removeExtra("mode")   // 别让配置变更重建时又切一次
        val s = state
        if (s != null) {
            s.applyRoute(m)
        } else {
            pendingRoute = m        // 界面还没建好，等 LaunchedEffect 消费
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Diag.w("key", "onNewIntent mode=" + intent.getStringExtra("mode"))
        readRoute(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Diag.attach(applicationContext)
        // Media3 的 HttpDataSource 走 HttpsURLConnection，进程级也放行老信任库，否则播放域名连不上
        runCatching {
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(Net.sslFactory)
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        }
        // 海报走同一个宽松 TLS 的 OkHttp（Coil 2 默认就是 OkHttp）
        runCatching {
            coil.Coil.setImageLoader(
                coil.ImageLoader.Builder(this)
                    .okHttpClient { Net.ok }
                    // 海报盘缓存 60MB + 分类列表盘 40MB（VodRepo.LIST_BUDGET）= 总计 ≤100MB
                    .diskCache {
                        coil.disk.DiskCache.Builder()
                            .directory(java.io.File(cacheDir, "coil"))
                            .maxSizeBytes(60L * 1024 * 1024)
                            .build()
                    }
                    .build()
            )
        }
        Diag.w("app", "启动 v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"))
        readRoute(intent)                        // 冷启动带的 mode 先存进 pendingRoute
        setContent {
            val scope = rememberCoroutineScope()
            val s = remember { UiState(applicationContext, scope) { finish() }.also { state = it } }
            val fr = remember { FocusRequester() }
            BackHandler { s.onKey(Rk.B) }          // 遥控器返回键
            LaunchedEffect(Unit) {
                fr.requestFocus()
                // 冷启动带的 mode：等 UiState 建好再切，否则直播控制器还没 load 完
                pendingRoute?.let { m -> pendingRoute = null; s.applyRoute(m) }
            }
            Box(
                Modifier.fillMaxSize().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                    val k = when (e.key) {
                        Key.DirectionLeft -> Rk.L
                        Key.DirectionRight -> Rk.R
                        Key.DirectionUp, Key.ChannelUp -> Rk.U
                        Key.DirectionDown, Key.ChannelDown -> Rk.D
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> Rk.OK
                        Key.Escape, Key.Backspace -> Rk.B
                        // 遥控器「菜单」键循环画面比例（自动/适应/铺满），与旧 Java 版一致
                        Key.Menu -> Rk.M
                        else -> null
                    }
                    if (k == null) false else { if (e.type == KeyEventType.KeyDown) s.onKey(k); true }
                }
            ) { App(s) }
        }
    }

    override fun onPause() {
        // 本机固件的 tile 栈会让「回桌面」后的 activity 停在 visible=true、迟迟不进 onStop，
        // 所以停播放在 onPause：它是这里唯一稳定会被调到的生命周期回调。
        android.util.Log.i("YGLife", "onPause")
        state?.onBackground()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // 同样地，这里只有 onResume 会回来（没有 onStart），恢复也只能挂这儿。
        android.util.Log.i("YGLife", "onResume")
        state?.onForeground()
    }

    override fun onStart() {
        android.util.Log.i("YGLife", "onStart")
        super.onStart()
    }

    override fun onDestroy() { android.util.Log.i("YGLife", "onDestroy"); state?.live?.release(); super.onDestroy() }
}

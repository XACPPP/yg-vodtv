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
                    .build()
            )
        }
        Diag.w("app", "启动 v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"))
        setContent {
            val scope = rememberCoroutineScope()
            val s = remember { UiState(applicationContext, scope) { finish() }.also { state = it } }
            val fr = remember { FocusRequester() }
            BackHandler { s.onKey(Rk.B) }          // 遥控器返回键
            LaunchedEffect(Unit) { fr.requestFocus() }
            Box(
                Modifier.fillMaxSize().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                    val k = when (e.key) {
                        Key.DirectionLeft -> Rk.L
                        Key.DirectionRight -> Rk.R
                        Key.DirectionUp, Key.ChannelUp -> Rk.U
                        Key.DirectionDown, Key.ChannelDown -> Rk.D
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> Rk.OK
                        Key.Escape, Key.Backspace -> Rk.B
                        else -> null
                    }
                    if (k == null) false else { if (e.type == KeyEventType.KeyDown) s.onKey(k); true }
                }
            ) { App(s) }
        }
    }

    override fun onDestroy() { state?.live?.release(); super.onDestroy() }
}

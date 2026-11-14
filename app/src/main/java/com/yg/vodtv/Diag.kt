package com.yg.vodtv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘日志。这台海信固件不把三方 App 的 logcat 吐出来（`adb logcat` 里看不到自己的行），
 * 所以出问题时把关键事实写进 files/diag.log，PC 侧用
 * `adb shell run-as com.yg.vodtv cat files/diag.log` 直接看。
 */
object Diag {
    private const val MAX = 128 * 1024
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile
    private var file: File? = null

    fun attach(c: Context) {
        if (file != null) return
        file = File(c.filesDir, "diag.log")
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX) f.renameTo(File(f.parentFile, "diag.1.log"))
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        }
    }
}

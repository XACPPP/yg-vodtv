package com.yg.vodtv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Locale

/* ============================ 在线更新 ============================ */

/** 浮层阶段：下载 → 就绪 → 已拉起安装器 / 出错 */
enum class UpdPhase { DOWNLOAD, READY, LAUNCH, ERROR }

/**
 * 更新浮层的数据。只有 `UiState.upd` 不为空时才画浮层，
 * 所以这个类同时充当「有没有更新在跑」的开关。
 */
data class UpdState(
    val phase: UpdPhase,
    val tag: String,
    val cur: String,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val err: String = "",
) {
    val pct: Int get() = if (total <= 0L) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
    val doneMb: String get() = String.format(Locale.US, "%.1f", done / 1048576.0)
    val totalMb: String get() = String.format(Locale.US, "%.1f", total / 1048576.0)
    val speedMb: String get() = String.format(Locale.US, "%.1f", speed / 1048576.0)

    /** 浮层主状态行 */
    val line: String
        get() = when (phase) {
            UpdPhase.DOWNLOAD -> if (total > 0L) "$pct%  $doneMb / $totalMb MB   $speedMb MB/s"
            else "$doneMb MB   $speedMb MB/s"
            UpdPhase.READY -> "下载完成，正在拉起系统安装器…"
            UpdPhase.LAUNCH -> "已打开系统安装界面，用遥控器选「安装」即可完成升级"
            UpdPhase.ERROR -> "失败：$err"
        }
}

object Upd {
    /**
     * 安装包最终落在哪。
     *
     * API<24：必须放 `files/` 并把目录和文件权限放开到全局可读——Android 5.x 的安装器
     * 以 system 身份直接按 file:// 路径读文件，私有目录 0700/0660 它进不去，会静默失败。
     * API>=24：外置私有目录 + FileProvider content://（file:// 已被 FileUriExposedException 拦死）。
     *
     * 注意海信这台机的 /storage/sdcard 没正常挂载，`getExternalFilesDir()` 有返回值但写不进去，
     * 所以宁可先落内部目录，别赌外置存储。
     */
    fun apkFile(ctx: Context, tag: String): File =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) File(ctx.filesDir, "YGTV-$tag.apk")
        else File(pickExtDir(ctx), "YGTV-$tag.apk")

    /** 外置私有目录，探到能写才用；探不到退回 files/update */
    private fun pickExtDir(ctx: Context): File {
        val cands = ArrayList<File>(2)
        ctx.getExternalFilesDir(null)?.let { cands.add(File(it, "update")) }
        cands.add(File(ctx.filesDir, "update"))
        for (d in cands) {
            if (!d.exists() && !d.mkdirs()) continue
            val probe = File(d, ".probe")
            val ok = runCatching { probe.writeBytes(byteArrayOf(1)); probe.delete(); true }.getOrDefault(false)
            if (ok) return d
            Diag.w("upd", "目录不可写，换下一个：" + d.absolutePath)
        }
        return File(ctx.filesDir, "update")
    }

    /** 开机清掉上次留下的安装包（一个 12MB，别一直躺着占电视那点空间） */
    fun cleanup(ctx: Context) {
        runCatching {
            // 只删自己下的包：files/ 里还躺着 diag.log / live.json，不能整目录清
            ctx.filesDir.listFiles()?.forEach {
                if (it.isFile && it.name.startsWith("YGTV-") && it.name.endsWith(".apk")) runCatching { it.delete() }
            }
            File(ctx.filesDir, "update").listFiles()?.forEach { runCatching { it.delete() } }
            ctx.getExternalFilesDir(null)?.let { ext ->
                File(ext, "update").listFiles()?.forEach { runCatching { it.delete() } }
            }
        }
    }

    /**
     * 边下边报进度。每 400ms 回调一次（done, total, speed），total 未知时给 -1。
     * 下完校验：长度必须与 Content-Length 一致、且大于 1MB（GitHub 偶尔回 HTML 错误页）。
     */
    suspend fun download(
        ctx: Context,
        tag: String,
        url: String,
        onTick: (done: Long, total: Long, speed: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val out = apkFile(ctx, tag)
        val tmp = File(out.parentFile, out.name + ".part")
        if (tmp.exists()) tmp.delete()
        val req = Request.Builder().url(url).header("User-Agent", Config.UA).build()
        Net.ok.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("服务器返回空内容")
            val len = body.contentLength()
            val total = if (len > 0L) len else -1L
            var done = 0L
            var markT = System.currentTimeMillis()
            var markN = 0L
            var speed = 0L
            tmp.outputStream().use { o ->
                body.byteStream().use { i ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - markT >= 400L) {
                            speed = (done - markN) * 1000L / (now - markT).coerceAtLeast(1L)
                            onTick(done, total, speed)
                            markT = now
                            markN = done
                        }
                    }
                }
                o.flush()
            }
            if (tmp.length() < 1024L * 1024L) error("安装包只有 ${tmp.length()} 字节，不像 APK")
            if (total > 0L && tmp.length() != total) error("下载不完整 ${tmp.length()}/$total")
            onTick(tmp.length(), if (total > 0L) total else tmp.length(), 0L)
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            // Android 5.x 的安装器以 system 身份按 file:// 读这个包，必须放开目录和文件权限
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) giftToSystem(out)
            out
        }
    }

    /**
     * 把包和它所在的 files/ 目录放开成 system 能读的样子。
     * 私有目录默认 0700/0660，安装器（system，非 root）连目录都进不去，会静默失败——
     * 这就是「下完了但一点反应都没有」的典型原因。
     */
    private fun giftToSystem(f: File) {
        runCatching {
            f.parentFile?.setExecutable(true, false)
            f.parentFile?.setReadable(true, false)
            f.setReadable(true, false)
        }
    }

    /**
     * 拉起系统安装器。返回空串表示已成功打开，否则是给用户看的失败原因。
     *
     * API 24 起 file:// 会被 FileUriExposedException 拦，必须走 FileProvider 的 content://；
     * 反过来 Android 5.x 的安装器不认 content://，只能 file://。两条路都留着。
     */
    fun install(ctx: Context, f: File): String {
        if (!f.exists() || f.length() < 1024L * 1024L) return "安装包不见了，按 OK 重新下载"

        // Android 8.0+ 还要单独打开「安装未知应用」，否则 startActivity 直接抛
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return "请先在设置里允许「安装未知应用」，再按 OK 重试"
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        } else {
            Uri.fromFile(f)
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            ctx.startActivity(intent)
            ""
        }.getOrElse { e -> "打开安装界面失败：${e.javaClass.simpleName}:${e.message}" }
    }
}

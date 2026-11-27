package com.azu.bookkeeper.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 拉起系统安装器（v1.3.1）。
 *
 * Android 不允许普通 App 静默安装（除非系统签名 / root），所以「自动开始重新安装」
 * 的合法实现是：下载完 APK 后立刻把系统安装界面推到前台，用户只需点一次「安装」。
 * 这里不做任何越权尝试，也不伪装成系统更新。
 */
object UpdateInstaller {

    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 从用户选择的本地 APK（例如电脑上用代理下载后传到手机）安装。
     * 系统文件选择器的 URI 权限是一次性的，先复制到应用私有目录再授权给安装器。
     */
    fun installFromUri(context: Context, uri: Uri): Boolean = runCatching {
        val dir = File(context.cacheDir, "incoming-apk").apply { mkdirs() }
        val target = File(dir, "incoming.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        } ?: return@runCatching false
        if (target.length() < 1024L) {
            target.delete()
            return@runCatching false
        }
        install(context, target)
    }.getOrDefault(false)
}

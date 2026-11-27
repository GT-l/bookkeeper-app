package com.azu.bookkeeper.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val releaseName: String? = null,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L
)

/** 一个可安装的更新：版本号 + APK 直链。 */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val pageUrl: String
)

data class CheckOutcome(val update: UpdateInfo?, val error: String?)

/**
 * GitHub Releases 更新检查 + APK 下载（v1.3.1）。
 *
 * 只用 JDK 自带的 HttpURLConnection + 项目已有的 kotlinx.serialization，
 * 不引入 OkHttp / Retrofit，避免为了一个手动触发的功能增加依赖和包体。
 * 全程在调用方给的 IO 协程里执行，不碰主线程。
 */
object GithubUpdateChecker {

    private const val API_URL = "https://api.github.com/repos/GT-l/bookkeeper-app/releases/latest"
    private const val PAGE_URL = "https://github.com/GT-l/bookkeeper-app/releases"
    private const val ASSET_NAME = "bookkeeper-debug.apk"
    private val json = Json { ignoreUnknownKeys = true }

    /** 检查是否有比当前更新的 Debug APK。网络/解析失败返回 error，不抛异常。 */
    fun check(currentVersion: String): CheckOutcome = runCatching {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "bookkeeper-app-update-check")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            return@runCatching CheckOutcome(null, "GitHub 返回 HTTP $code")
        }
        val release = json.decodeFromString<GithubRelease>(body)
        val latest = release.tagName.trim()
            .removePrefix("v").substringBefore("-")
        if (!isNewer(latest, currentVersion)) {
            return@runCatching CheckOutcome(null, null)
        }
        val asset = release.assets.firstOrNull { it.name == ASSET_NAME }
            ?: return@runCatching CheckOutcome(null, "最新版本没有 $ASSET_NAME 附件")
        CheckOutcome(
            UpdateInfo(
                version = latest,
                downloadUrl = asset.browserDownloadUrl,
                assetName = asset.name,
                sizeBytes = asset.size,
                pageUrl = PAGE_URL
            ),
            null
        )
    }.getOrElse { t -> CheckOutcome(null, "${t.javaClass.simpleName}: ${t.message}") }

    /** 下载到应用私有目录；返回文件，失败返回 null（不留下半个文件）。 */
    fun download(url: String, target: File, onProgress: (Int) -> Unit): File? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "bookkeeper-app-update-check")
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return@runCatching null
        }
        val total = conn.contentLength
        var done = 0L
        target.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (total > 0L) onProgress(((done * 100L) / total).toInt())
                }
                out.flush()
            }
        }
        conn.disconnect()
        if (target.length() < 1024L) {
            target.delete()
            return@runCatching null
        }
        target
    }.getOrElse {
        runCatching { target.delete() }
        null
    }

    /** 纯数字版本比较：1.3.1 > 1.3.0；无法解析的段按 0 处理。 */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(a.size, b.size, 3)
        for (i in 0 until size) {
            val av = a.getOrNull(i) ?: 0
            val bv = b.getOrNull(i) ?: 0
            if (av != bv) return av > bv
        }
        return false
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "未知大小"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

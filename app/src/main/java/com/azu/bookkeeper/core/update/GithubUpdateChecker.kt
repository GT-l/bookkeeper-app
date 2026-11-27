package com.azu.bookkeeper.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
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
    val id: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L
)

/**
 * 一个可安装的更新。
 *
 * [downloadUrl] 优先走 api.github.com 的 asset 接口（用户已验证可访问），
 * [fallbackUrl] 保留 releases/download 直链作为备用通道；[browserUrl] 给外部浏览器用。
 */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val fallbackUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val pageUrl: String
) {
    val browserUrl: String get() = fallbackUrl.ifBlank { downloadUrl }
}

data class CheckOutcome(val update: UpdateInfo?, val error: String?)

/** 下载结果：失败时 error 必须说明 HTTP 码 / 域名 / 异常类型，不再吞成一句「请检查网络」。 */
data class DownloadOutcome(val file: File?, val error: String?) {
    val ok: Boolean get() = file != null && error == null
}

/**
 * GitHub Releases 更新检查 + APK 下载（v1.3.3）。
 *
 * 只依赖 JDK 的 HttpURLConnection + 项目已有的 kotlinx.serialization。
 *
 * v1.3.3 改动背景：用户本地网络访问 api.github.com 正常、但下载 release asset 失败；
 * 开启 Clash 后反而报 HTTP 错误。根因是下载会 302 跳到 CDN 域名
 * release-assets.githubusercontent.com，代理规则没放行时该跳返回 4xx/5xx，
 * 旧实现 instanceFollowRedirects=true 把错误吞成 null，UI 只显示「请检查网络」。
 * 这里改为手动逐跳跟随并保留每跳的 HTTP 码与 host，双通道自动降级，失败给出可操作的提示。
 */
object GithubUpdateChecker {

    private const val API_URL = "https://api.github.com/repos/GT-l/bookkeeper-app/releases/latest"
    private const val REPO_API = "https://api.github.com/repos/GT-l/bookkeeper-app"
    private const val PAGE_URL = "https://github.com/GT-l/bookkeeper-app/releases"
    private const val ASSET_NAME = "bookkeeper-debug.apk"
    private const val UA = "bookkeeper-app-update-check"
    private const val MAX_REDIRECTS = 5
    private val json = Json { ignoreUnknownKeys = true }

    /** 检查是否有比当前更新的 Debug APK。网络/解析失败返回 error，不抛异常。 */
    fun check(currentVersion: String): CheckOutcome = runCatching {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            return@runCatching CheckOutcome(
                null,
                "api.github.com 返回 HTTP $code${if (code == 403) "（可能是代理或 GitHub 限流）" else ""}"
            )
        }
        val release = json.decodeFromString<GithubRelease>(body)
        val latest = release.tagName.trim()
            .removePrefix("v").substringBefore("-")
        if (!isNewer(latest, currentVersion)) {
            return@runCatching CheckOutcome(null, null)
        }
        val asset = release.assets.firstOrNull { it.name == ASSET_NAME }
            ?: return@runCatching CheckOutcome(null, "最新版本没有 $ASSET_NAME 附件")
        val apiDownloadUrl = if (asset.id > 0L) "$REPO_API/releases/assets/${asset.id}" else ""
        CheckOutcome(
            UpdateInfo(
                version = latest,
                downloadUrl = apiDownloadUrl.ifBlank { asset.browserDownloadUrl },
                fallbackUrl = asset.browserDownloadUrl,
                assetName = asset.name,
                sizeBytes = asset.size,
                pageUrl = PAGE_URL
            ),
            null
        )
    }.getOrElse { t ->
        CheckOutcome(null, "${t.javaClass.simpleName}: ${t.message ?: "无详细消息"}")
    }

    /**
     * 下载到应用私有目录。先试 API asset 通道，失败再试 releases/download 直链。
     * 成功返回文件；失败返回带原因的 [DownloadOutcome.error]，并清理半截文件。
     */
    fun download(update: UpdateInfo, target: File, onProgress: (Int) -> Unit): DownloadOutcome {
        val candidates = LinkedHashSet<String>()
        if (update.downloadUrl.isNotBlank()) candidates.add(update.downloadUrl)
        if (update.fallbackUrl.isNotBlank()) candidates.add(update.fallbackUrl)
        if (candidates.isEmpty()) return DownloadOutcome(null, "更新信息里没有下载地址")

        val failures = mutableListOf<String>()
        candidates.forEachIndexed { index, url ->
            cleanPart(target)
            val result = downloadOne(url, target, onProgress)
            if (result.ok) return result
            result.error?.let { failures += "通道${index + 1}（${hostOf(url)}）：$it" }
        }
        cleanPart(target)
        return DownloadOutcome(null, failures.joinToString("；"))
    }

    private fun downloadOne(startUrl: String, target: File, onProgress: (Int) -> Unit): DownloadOutcome {
        var current = startUrl
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    conn.apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty("Accept", "application/octet-stream, */*")
                        instanceFollowRedirects = false // 自己跟，才能看到每一跳的 HTTP 码
                    }
                    when (val code = conn.responseCode) {
                        in 200..299 -> return saveBody(conn, current, target, onProgress)
                        301, 302, 303, 307, 308 -> {
                            val location = conn.getHeaderField("Location")
                            if (location.isNullOrBlank()) {
                                return DownloadOutcome(
                                    null,
                                    "HTTP $code @ ${hostOf(current)} 但没有 Location 跳转地址"
                                )
                            }
                            current = URL(URL(current), location).toString()
                        }
                        else -> {
                            val body = runCatching {
                                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                    ?.take(160)
                            }.getOrNull().orEmpty()
                            return DownloadOutcome(
                                null,
                                "HTTP $code @ ${hostOf(current)}" +
                                    (if (body.isNotBlank()) "：$body" else "") +
                                    hint(code, hostOf(current))
                            )
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            return DownloadOutcome(
                null,
                "重定向超过 $MAX_REDIRECTS 次，最后停在 ${hostOf(current)}；请检查 Clash 规则是否把下载请求循环代理"
            )
        } catch (e: SocketTimeoutException) {
            return DownloadOutcome(
                null,
                "连接/读取超时 @ ${hostOf(current)}：代理未放行 GitHub 下载域名，或网络不稳定"
            )
        } catch (e: IOException) {
            return DownloadOutcome(
                null,
                "网络异常 @ ${hostOf(current)}：${e.javaClass.simpleName} ${e.message ?: ""}"
            )
        } catch (t: Throwable) {
            return DownloadOutcome(null, "${t.javaClass.simpleName}: ${t.message ?: "无详细消息"}")
        }
    }

    private fun saveBody(
        conn: HttpURLConnection,
        url: String,
        target: File,
        onProgress: (Int) -> Unit
    ): DownloadOutcome {
        val total = conn.contentLength
        var done = 0L
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        try {
            FileOutputStream(part).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0L) onProgress(((done * 100L) / total).toInt().coerceIn(0, 99))
                    }
                    out.flush()
                }
            }
            if (done < 1024L || !looksLikeApk(part)) {
                return DownloadOutcome(
                    null,
                    "${hostOf(url)} 返回的内容不是有效 APK（$done 字节）。通常是代理拦截页，请放行 githubusercontent.com 后重试"
                )
            }
            if (target.exists() && !target.delete()) {
                return DownloadOutcome(null, "旧apk文件无法覆盖：${target.absolutePath}")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                if (!part.delete()) part.deleteOnExit()
            }
            onProgress(100)
            return DownloadOutcome(target, null)
        } finally {
            runCatching { if (part.exists()) part.delete() }
        }
    }

    /** APK 本质是 ZIP，头两个字节必须是 PK；顺带挡掉代理返回的 HTML 错误页。 */
    private fun looksLikeApk(file: File): Boolean = runCatching {
        FileInputStream(file).use { input -> input.read() == 'P'.code && input.read() == 'K'.code }
    }.getOrDefault(false)

    private fun cleanPart(target: File) {
        runCatching { File(target.parentFile, "${target.name}.part").delete() }
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** 把最常见的失败原因翻译成用户能照着做的提示。 */
    fun hint(code: Int, host: String): String = when {
        code == 403 || code == 404 ->
            "（$host 是 GitHub 下载 CDN，Clash 需放行 githubusercontent.com；" +
                "若用 HTTP 代理模式请改 TUN/全局模式，或直接用浏览器下载）"
        code in 500..599 -> "（GitHub/CDN 服务暂时不可用，稍后重试）"
        code == 429 -> "（请求过于频繁，稍后再试）"
        else -> ""
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

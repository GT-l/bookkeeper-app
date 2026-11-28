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
import java.util.Locale

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
 * v1.3.15 起通道顺序反转：[downloadUrl] 是 `releases/download` 直链（一跳到底），
 * [fallbackUrl] 是 `api.github.com` 的 asset 接口（会多一次 302 握手）作为兜底。
 *
 * 依据：实测两条通道最终都 302 到同一个 `release-assets.githubusercontent.com`，
 * 所以 API 通道没有任何速度优势，只是多一跳；把它放在前面等于每次都白等一次握手。
 */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val fallbackUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val pageUrl: String
) {
    /** 浏览器 / 外部打开用：优先真实下载直链，缺失时退回兜底地址。 */
    val browserUrl: String get() = downloadUrl.ifBlank { fallbackUrl }
}

data class CheckOutcome(val update: UpdateInfo?, val error: String?)

/**
 * 下载进度（v1.3.15）。
 *
 * 旧实现只回传一个整数百分比：88MB 的安装包下 1% ≈ 0.9MB，慢速网络里前几十秒一直显示 0%，
 * 用户看到的就是「卡住不动」。这里补上已下载字节、实时速率与剩余时间，让「慢」和「停了」能区分。
 */
data class DownloadProgress(
    val percent: Int,
    val bytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long
) {
    /** 「12.3 MB / 88.3 MB · 480 KB/s · 剩余 3 分 12 秒」；速率未知时只显示字节数。 */
    fun detailText(): String {
        val totalText = if (totalBytes > 0L) GithubUpdateChecker.formatSize(totalBytes) else "未知大小"
        val head = "${GithubUpdateChecker.formatSize(bytes)} / $totalText"
        val speed = GithubUpdateChecker.formatSpeed(bytesPerSecond)
        if (speed.isBlank()) return head
        val eta = GithubUpdateChecker.formatEta(etaSeconds)
        return if (eta.isBlank()) "$head · $speed" else "$head · $speed · $eta"
    }
}

/** 下载结果：失败时 error 必须说明 HTTP 码 / 域名 / 异常类型，不再吞成一句「请检查网络」。 */
data class DownloadOutcome(
    val file: File?,
    val error: String?,
    val channelHost: String = "",
    val bytes: Long = 0L,
    val elapsedMs: Long = 0L,
    val resumed: Boolean = false,
    val reused: Boolean = false
) {
    val ok: Boolean get() = file != null && error == null
    val avgBytesPerSecond: Long get() = if (elapsedMs <= 0L) 0L else bytes * 1000L / elapsedMs
}

/** 进度节流窗口：88MB 的包按 128KB 读也有 700 多次回调，压缩到每秒最多 5 次。 */
private const val PROGRESS_INTERVAL_MS = 200L

/**
 * GitHub Releases 更新检查 + APK 下载。
 *
 * 只依赖 JDK 的 HttpURLConnection + 项目已有的 kotlinx.serialization。
 *
 * v1.3.3 背景：用户本地网络访问 api.github.com 正常、但下载 release asset 失败；
 * 开启 Clash 后反而报 HTTP 错误。根因是下载会 302 跳到 CDN 域名，代理规则没放行时该跳
 * 返回 4xx/5xx，旧实现 instanceFollowRedirects=true 把错误吞成 null，UI 只显示「请检查网络」。
 * 改为手动逐跳跟随并保留每跳的 HTTP 码与 host，双通道自动降级，失败给出可操作的提示。
 *
 * v1.3.15（用户反馈「下载变慢了」）：
 * 1. 断点续传：`Range: bytes=<已下字节>-` + `.part` 续写，代理抖动断流不再从 0 重下 88MB；
 * 2. 进度可读：200ms 节流 + 实时速率 + 剩余时间（见 [DownloadProgress]）；
 * 3. 通道合并：直链优先，API 兜底，省掉一次无收益的 302 握手；
 * 4. 断点安全：服务器忽略 Range（回 200）就截断重下；416 视为「断点文件已完整」直接收尾；
 *    落盘后 fsync，进程被杀也不会留下「缺一截却看着像完整」的文件。
 */
object GithubUpdateChecker {

    private const val API_URL = "https://api.github.com/repos/GT-l/bookkeeper-app/releases/latest"
    private const val REPO_API = "https://api.github.com/repos/GT-l/bookkeeper-app"
    private const val PAGE_URL = "https://github.com/GT-l/bookkeeper-app/releases"
    private const val ASSET_NAME = "bookkeeper-debug.apk"
    private const val UA = "bookkeeper-app-update-check"
    private const val MAX_REDIRECTS = 5

    /** 一次性 read 的缓冲：32KB 在 88MB 的单线程下载里 syscall 偏多，128KB 更省。 */
    private const val BUFFER_BYTES = 128 * 1024

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
                // v1.3.15：直链优先（一跳），API asset 只做兜底
                downloadUrl = asset.browserDownloadUrl,
                fallbackUrl = apiDownloadUrl,
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
     * 下载到应用私有目录，支持断点续传。先试直链通道，失败再试 API asset 通道。
     * 成功返回文件；失败返回带原因的 [DownloadOutcome.error]。
     */
    fun download(
        update: UpdateInfo,
        target: File,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadOutcome {
        val startedAt = System.currentTimeMillis()
        val part = partOf(target)

        // 上次已经下完、只是没来得及装（进程被杀 / 用户没点安装）：大小对得上就直接复用。
        // 88MB 的包没必要因为「差最后一步」重下一次。
        if (target.exists() && target.length() > 1024L && looksLikeApk(target) &&
            (update.sizeBytes <= 0L || target.length() == update.sizeBytes)
        ) {
            onProgress(
                DownloadProgress(100, target.length(), maxOf(update.sizeBytes, target.length()), 0L, 0L)
            )
            return DownloadOutcome(target, null, bytes = target.length(), reused = true)
        }

        val candidates = LinkedHashSet<String>()
        if (update.downloadUrl.isNotBlank()) candidates.add(update.downloadUrl)
        if (update.fallbackUrl.isNotBlank() && update.fallbackUrl != update.downloadUrl) {
            candidates.add(update.fallbackUrl)
        }
        if (candidates.isEmpty()) return DownloadOutcome(null, "更新信息里没有下载地址")

        // 断点比远端还大 → 一定是脏文件（换过包 / 换过通道），丢掉重来
        var resumeFrom = if (part.exists()) part.length() else 0L
        if (update.sizeBytes > 0L && resumeFrom > update.sizeBytes) {
            runCatching { part.delete() }
            resumeFrom = 0L
        }
        val hadResume = resumeFrom > 0L

        val failures = mutableListOf<String>()
        candidates.forEachIndexed { index, url ->
            val result = downloadOne(url, target, resumeFrom, update.sizeBytes, onProgress)
            if (result.ok) {
                return result.copy(
                    channelHost = hostOf(url),
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    resumed = hadResume || result.resumed
                )
            }
            result.error?.let { failures += "通道${index + 1}（${hostOf(url)}）：$it" }
            // 换通道时保留断点：同一个 asset，能接着下就接着下
            resumeFrom = if (part.exists()) part.length() else 0L
        }
        return DownloadOutcome(null, failures.joinToString("；"))
    }

    private fun downloadOne(
        startUrl: String,
        target: File,
        initialResume: Long,
        expectedSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadOutcome {
        var current = startUrl
        var resumeFrom = initialResume
        var rangeRejected = false
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    // v1.3.7 关键修复：GitHub asset API 只有在 Accept 精确为
                    // application/octet-stream 时才 302 到 CDN。旧代码写成
                    // "application/octet-stream, */*"，内容协商会退化成 JSON 元数据（约 1.6KB），
                    // 用户看到的就是「api.github.com 返回的内容不是有效 APK (1668 字节)」。
                    val isAssetApi = current.contains("api.github.com") &&
                        current.contains("/releases/assets/")
                    conn.apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty(
                            "Accept",
                            if (isAssetApi) "application/octet-stream" else "application/octet-stream, */*"
                        )
                        // v1.3.15：Range 续传必须拿到原始字节流，别让中间层压缩改写长度
                        setRequestProperty("Accept-Encoding", "identity")
                        if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
                        instanceFollowRedirects = false // 自己跟，才能看到每一跳的 HTTP 码
                    }
                    when (val code = conn.responseCode) {
                        in 200..299 -> {
                            // 如果拿到的仍是 JSON 元数据（Accept 被代理改写等），解析出直链再下一跳
                            val contentType = conn.contentType.orEmpty()
                            if (contentType.contains("json", ignoreCase = true)) {
                                val body = conn.inputStream.bufferedReader(Charsets.UTF_8)
                                    .use { it.readText() }
                                val asset = runCatching {
                                    json.decodeFromString<GithubAsset>(body)
                                }.getOrNull()
                                val next = asset?.browserDownloadUrl.orEmpty()
                                if (next.isNotBlank() && next != current) {
                                    current = next
                                    continue // 跳到 releases/download 直链继续下载
                                }
                                return DownloadOutcome(
                                    null,
                                    "api.github.com 返回的是 JSON 元数据而不是安装包；" +
                                        "请稍后重试，或改用浏览器下载"
                                )
                            }
                            // 206 = 服务器认可 Range，接着断点写；200 = 忽略 Range，必须从头写
                            val append = code == 206 && resumeFrom > 0L
                            val remaining = runCatching { conn.contentLengthLong }.getOrDefault(-1L)
                            val total = when {
                                append && remaining > 0L -> resumeFrom + remaining
                                remaining > 0L -> remaining
                                else -> expectedSize
                            }
                            return saveBody(
                                conn = conn,
                                url = current,
                                target = target,
                                startAt = if (append) resumeFrom else 0L,
                                total = total,
                                expectedSize = expectedSize,
                                onProgress = onProgress
                            )
                        }
                        // 断点文件已经和远端一样大（上次只差最后一步落盘）：直接收尾，不再重下
                        416 -> {
                            val part = partOf(target)
                            if (part.length() > 1024L && looksLikeApk(part) &&
                                (expectedSize <= 0L || part.length() >= expectedSize)
                            ) {
                                return finishApk(part, target, part.length(), onProgress)
                            }
                            runCatching { part.delete() }
                            if (rangeRejected) {
                                return DownloadOutcome(
                                    null,
                                    "HTTP 416 @ ${hostOf(current)}：断点文件与远端不匹配，已丢弃"
                                )
                            }
                            rangeRejected = true
                            resumeFrom = 0L
                            continue // 不带 Range 重新请求
                        }
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

    /**
     * 把响应体写进 `.part`。v1.3.15：
     * - [startAt] > 0 表示续写（追加模式打开，进度从已下字节起算）；
     * - 网络中断时**保留**断点文件（旧实现在 finally 里无脑删除，等于每次断流都重下 88MB）；
     * - 只有「内容不是 APK」「大小对不上」这类确定性失败才删断点。
     */
    private fun saveBody(
        conn: HttpURLConnection,
        url: String,
        target: File,
        startAt: Long,
        total: Long,
        expectedSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadOutcome {
        val part = partOf(target)
        target.parentFile?.mkdirs()
        // 断点文件在请求过程中被清掉了 → 追加会写出空洞，退回从头写
        var from = startAt
        if (from > 0L && !part.exists()) from = 0L
        var done = from
        var keepPart = false
        var completed = false
        val emitter = ProgressEmitter(onProgress)
        emitter.emit(done, total, force = true)
        try {
            FileOutputStream(part, from > 0L).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        emitter.emit(done, total)
                    }
                    out.flush()
                    // 断点续传的前提是「已下到的字节真的在文件里」：进程被杀也不丢进度
                    runCatching { out.fd.sync() }
                }
            }
            completed = true
        } catch (e: IOException) {
            keepPart = done > from
            throw e
        } finally {
            if (!completed && !keepPart) runCatching { if (part.exists()) part.delete() }
        }
        if (done < 1024L || !looksLikeApk(part)) {
            runCatching { part.delete() }
            return DownloadOutcome(
                null,
                "${hostOf(url)} 返回的内容不是有效 APK（$done 字节）。通常是代理拦截页，请放行 githubusercontent.com 后重试"
            )
        }
        // 「看着下完、其实少一截」是断点续传最容易引入的坑：大小对不上就保留断点，下次续
        if (expectedSize > 0L && done < expectedSize) {
            return DownloadOutcome(
                null,
                "下载不完整（$done/$expectedSize 字节）@ ${hostOf(url)}，已保留断点，重试会接着下"
            )
        }
        return finishApk(part, target, done, onProgress)
    }

    /** 落盘收尾：`.part` 改成正式文件名，并回报 100%。 */
    private fun finishApk(
        part: File,
        target: File,
        bytes: Long,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadOutcome {
        if (target.exists() && !target.delete()) {
            return DownloadOutcome(null, "旧apk文件无法覆盖：${target.absolutePath}")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            if (!part.delete()) part.deleteOnExit()
        }
        onProgress(DownloadProgress(100, bytes, bytes, 0L, 0L))
        return DownloadOutcome(target, null, bytes = bytes)
    }

    /** APK 本质是 ZIP，头两个字节必须是 PK；顺带挡掉代理返回的 HTML 错误页。 */
    private fun looksLikeApk(file: File): Boolean = runCatching {
        FileInputStream(file).use { input -> input.read() == 'P'.code && input.read() == 'K'.code }
    }.getOrDefault(false)

    private fun partOf(target: File): File = File(target.parentFile, "${target.name}.part")

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

    /** 实时速率（v1.3.15）：0 表示还没测出来，返回空串让调用方省掉这一段。 */
    fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond <= 0L -> ""
        bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024L * 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    /** 剩余时间：不足 1 分钟只给秒，超过 1 小时给小时+分。 */
    fun formatEta(seconds: Long): String = when {
        seconds <= 1L -> ""
        seconds < 60L -> "剩余 $seconds 秒"
        seconds < 3600L -> "剩余 ${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "剩余 ${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
    }
}

/**
 * 进度节流器（v1.3.15）。
 *
 * 88MB 按 128KB 读也有 700 多次回调；旧实现按 32KB 回调接近 2800 次，每次都从 IO 线程
 * 推一次 StateFlow，主线程被重组淹没后进度条反而「不动」。这里：
 * - 200ms 或百分比变化才推一次；
 * - 速率用四次采样平滑，避免数字乱跳；
 * - 剩余时间按当前速率折算，速率为 0 时不猜。
 */
private class ProgressEmitter(private val sink: (DownloadProgress) -> Unit) {
    private var lastEmitAt = 0L
    private var lastPercent = -1
    private var lastSampleAt = 0L
    private var lastBytes = 0L
    private var speed = 0L

    fun emit(bytes: Long, total: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val percent = if (total > 0L) ((bytes * 1000L) / total).toInt().coerceIn(0, 100) else 0
        if (!force && now - lastEmitAt < PROGRESS_INTERVAL_MS && percent == lastPercent) return
        if (lastSampleAt > 0L && now > lastSampleAt) {
            val instant = (bytes - lastBytes) * 1000L / (now - lastSampleAt)
            speed = if (speed <= 0L) instant else (speed * 3L + instant) / 4L
        }
        lastSampleAt = now
        lastBytes = bytes
        lastEmitAt = now
        lastPercent = percent
        val eta = if (total > bytes && speed > 0L) (total - bytes) / speed else 0L
        sink(DownloadProgress(percent, bytes, total, speed, eta))
    }
}

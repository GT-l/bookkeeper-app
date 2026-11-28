package com.azu.bookkeeper.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.1：关于页自动更新的纯 JVM 逻辑（版本比较 / 大小格式化）。 */
class GithubUpdateCheckerTest {

    @Test
    fun `版本号按数字段比较`() {
        assertTrue(GithubUpdateChecker.isNewer("1.3.1", "1.3.0"))
        assertTrue(GithubUpdateChecker.isNewer("1.4.0", "1.3.9"))
        assertFalse(GithubUpdateChecker.isNewer("1.3.0", "1.3.0"))
        assertFalse(GithubUpdateChecker.isNewer("1.2.9", "1.3.0"))
    }

    @Test
    fun `APK 大小格式化人类可读`() {
        assertEquals("1.0 MB", GithubUpdateChecker.formatSize(1024L * 1024L))
        assertEquals("1.0 KB", GithubUpdateChecker.formatSize(1024L))
        assertEquals("未知大小", GithubUpdateChecker.formatSize(0L))
    }

    @Test
    fun `错误提示包含 CDN 域名放行建议`() {
        val forbidden = GithubUpdateChecker.hint(403, "release-assets.githubusercontent.com")
        assertTrue(forbidden.contains("githubusercontent.com"))
        assertTrue(forbidden.contains("TUN"))
        assertTrue(GithubUpdateChecker.hint(502, "release-assets.githubusercontent.com").contains("稍后重试"))
        assertEquals("", GithubUpdateChecker.hint(200, "api.github.com"))
    }

    @Test
    fun `浏览器下载链接优先用直链，缺失时回退兜底地址`() {
        // v1.3.15 语义反转：downloadUrl 是 releases/download 直链（一跳），fallbackUrl 是 API asset
        val info = UpdateInfo(
            version = "1.3.15",
            downloadUrl = "https://github.com/GT-l/bookkeeper-app/releases/download/v1.3.15-debug/bookkeeper-debug.apk",
            fallbackUrl = "https://api.github.com/repos/GT-l/bookkeeper-app/releases/assets/1",
            assetName = "bookkeeper-debug.apk",
            sizeBytes = 1L,
            pageUrl = "https://github.com/GT-l/bookkeeper-app/releases"
        )
        assertEquals(info.downloadUrl, info.browserUrl)
        assertEquals(
            "https://api.github.com/repos/x/assets/1",
            info.copy(downloadUrl = "").browserUrl
        )
    }

    @Test
    fun `下载结果按文件与错误判定成功失败`() {
        assertTrue(DownloadOutcome(null, "HTTP 403 @ cdn").error!!.contains("HTTP 403"))
        assertFalse(DownloadOutcome(null, "HTTP 403 @ cdn").ok)
        assertTrue(DownloadOutcome(null, null).error == null)
        assertFalse(DownloadOutcome(null, null).ok)
    }

    // ---------- v1.3.15：下载进度可读性 ----------

    @Test
    fun `速率按数量级选择单位`() {
        assertEquals("", GithubUpdateChecker.formatSpeed(0L))
        assertEquals("900 B/s", GithubUpdateChecker.formatSpeed(900L))
        assertEquals("480 KB/s", GithubUpdateChecker.formatSpeed(491_520L))
        assertEquals("2.5 MB/s", GithubUpdateChecker.formatSpeed(2_621_440L))
    }

    @Test
    fun `剩余时间分档显示`() {
        assertEquals("", GithubUpdateChecker.formatEta(0L))
        assertEquals("", GithubUpdateChecker.formatEta(1L))
        assertEquals("剩余 42 秒", GithubUpdateChecker.formatEta(42L))
        assertEquals("剩余 3 分 12 秒", GithubUpdateChecker.formatEta(192L))
        assertEquals("剩余 1 小时 2 分", GithubUpdateChecker.formatEta(3_720L))
    }

    @Test
    fun `进度明细包含已下字节与速率`() {
        val p = DownloadProgress(
            percent = 12,
            bytes = 11_000_000L,
            totalBytes = 92_578_651L,
            bytesPerSecond = 491_520L,
            etaSeconds = 166L
        )
        val text = p.detailText()
        assertTrue(text.startsWith("10.5 MB / 88.3 MB"))
        assertTrue(text.contains("480 KB/s"))
        assertTrue(text.contains("剩余 2 分 46 秒"))
        // 速率未知时不应显示「0 B/s · 剩余 0 秒」这类噪声
        val unknown = p.copy(bytesPerSecond = 0L, etaSeconds = 0L).detailText()
        assertFalse(unknown.contains("B/s"))
        assertFalse(unknown.contains("剩余"))
    }

    @Test
    fun `平均速率按耗时折算`() {
        val outcome = DownloadOutcome(
            file = null, error = "x", bytes = 92_578_651L, elapsedMs = 120_000L
        )
        assertEquals(771_488L, outcome.avgBytesPerSecond)
        assertEquals(0L, DownloadOutcome(null, null).avgBytesPerSecond)
    }

    @Test
    fun `进度百分比是 0 到 100 而不是千分位`() {
        // 用整千总量做精确断言；88MB 那种不能被 100 整除的量会向下取整，属预期
        val total = 1000L
        assertEquals(0, GithubUpdateChecker.percentOf(0L, total))
        assertEquals(10, GithubUpdateChecker.percentOf(100L, total))
        assertEquals(50, GithubUpdateChecker.percentOf(500L, total))
        assertEquals(99, GithubUpdateChecker.percentOf(999L, total))
        assertEquals(100, GithubUpdateChecker.percentOf(total, total))
        // 总量未知（chunked）时不瞎猜；超出总量也不允许大于 100
        assertEquals(0, GithubUpdateChecker.percentOf(1024L, 0L))
        assertEquals(100, GithubUpdateChecker.percentOf(2000L, total))
        // 真实包大小：下到 10% 必须是 10，曾经这里返回 100
        val apk = 92_578_651L
        assertEquals(10, GithubUpdateChecker.percentOf(apk / 10 + 1, apk))
        assertEquals(88, GithubUpdateChecker.percentOf(apk * 88 / 100 + 1, apk))
    }
}

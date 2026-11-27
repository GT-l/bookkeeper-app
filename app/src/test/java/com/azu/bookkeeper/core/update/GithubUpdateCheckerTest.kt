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
    fun `浏览器下载链接在备用地址缺失时回退到主地址`() {
        val info = UpdateInfo(
            version = "1.3.3",
            downloadUrl = "https://api.github.com/repos/GT-l/bookkeeper-app/releases/assets/1",
            fallbackUrl = "",
            assetName = "bookkeeper-debug.apk",
            sizeBytes = 1L,
            pageUrl = "https://github.com/GT-l/bookkeeper-app/releases"
        )
        assertEquals(info.downloadUrl, info.browserUrl)
        assertEquals(
            "https://github.com/x.apk",
            info.copy(fallbackUrl = "https://github.com/x.apk").browserUrl
        )
    }

    @Test
    fun `下载结果按文件与错误判定成功失败`() {
        assertTrue(DownloadOutcome(null, "HTTP 403 @ cdn").error!!.contains("HTTP 403"))
        assertFalse(DownloadOutcome(null, "HTTP 403 @ cdn").ok)
        assertTrue(DownloadOutcome(null, null).error == null)
        assertFalse(DownloadOutcome(null, null).ok)
    }
}

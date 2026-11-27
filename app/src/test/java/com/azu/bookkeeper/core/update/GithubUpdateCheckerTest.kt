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
}

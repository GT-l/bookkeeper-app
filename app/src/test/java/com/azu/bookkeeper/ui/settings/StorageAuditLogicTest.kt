package com.azu.bookkeeper.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.0 新增：存储排查的纯逻辑单测。
 *
 * 这些函数没有 Android 依赖，能在 JVM 上直接跑；界面部分（扫描私有目录 / 勾选 / 删除）
 * 需要真机，见 docs/testing/v1.1.0/TEST_CASES.md 的 T-AUDIT-UI-* 用例。
 */
class StorageAuditLogicTest {

    @Test
    fun `体积格式化 - 0 字节`() {
        assertEquals("0 B", formatStorageSize(0L))
    }

    @Test
    fun `体积格式化 - 小于 1KB 用 B`() {
        assertEquals("1023 B", formatStorageSize(1023L))
    }

    @Test
    fun `体积格式化 - 1KB 边界`() {
        assertEquals("1.0 KB", formatStorageSize(1024L))
    }

    @Test
    fun `体积格式化 - MB 与 GB`() {
        assertEquals("1.0 MB", formatStorageSize(1024L * 1024L))
        assertEquals("1.00 GB", formatStorageSize(1024L * 1024L * 1024L))
    }

    @Test
    fun `时间格式化 - 0 显示占位符`() {
        assertEquals("—", formatStorageTime(0L))
        assertEquals("—", formatStorageTime(-1L))
    }

    @Test
    fun `时间格式化 - 有值输出年月日时分`() {
        val text = formatStorageTime(1_764_000_000_000L)
        assertTrue(text.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    @Test
    fun `条目展示文本使用统一格式化`() {
        val item = StorageAuditViewModel.Item(
            path = "/data/user/0/com.azu.bookkeeper/databases/old.db",
            name = "old.db",
            sizeBytes = 2048L,
            lastModified = 0L,
            kind = StorageAuditViewModel.Kind.DATABASE,
            note = "疑似旧版本遗留的数据库"
        )
        assertEquals("2.0 KB", item.sizeText)
        assertEquals("—", item.timeText)
        assertEquals(false, item.locked)
    }
}

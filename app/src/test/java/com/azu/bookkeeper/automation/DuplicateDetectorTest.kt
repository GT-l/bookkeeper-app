package com.azu.bookkeeper.automation

import com.azu.bookkeeper.automation.dedup.DuplicateDetector
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.repository.BillRepository
import io.mockk.any
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 去重逻辑单元测试（PRD 8.2 / 22.9 / AC-6 / AC-16）。
 * 精确键 = bookId + 秒级时间 + 金额分 + 账户 + 归一化备注；短信容差键刻意不含 remark。
 */
class DuplicateDetectorTest {

    private val repository = mockk<BillRepository>(relaxed = true)
    private val detector = DuplicateDetector(repository)

    private fun candidate(
        bookId: Long = 1L,
        time: Long = 1_700_000_000_999L,
        amount: Long = 12_345L,
        account: Long = 7L,
        merchant: String = "  星巴克  ",
        type: CaptureType = CaptureType.NOTIFICATION
    ) = CaptureCandidate(
        bookId = bookId,
        captureType = type,
        amountFen = amount,
        merchant = merchant,
        billTime = time,
        accountId = account
    )

    @Test
    fun `精确键由五元组组成且秒级取整`() {
        val key = detector.buildKey(candidate())
        assertEquals("1|1700000000|12345|7|星巴克", key)
    }

    @Test
    fun `备注空白差异归一化为同一键`() {
        assertEquals(
            detector.buildKey(candidate(merchant = "  星   巴克 ")),
            detector.buildKey(candidate(merchant = "星 巴克"))
        )
    }

    @Test
    fun `跨秒或跨金额必然不同键`() {
        assertNotEquals(detector.buildKey(candidate()), detector.buildKey(candidate(time = 1_700_000_001_000L)))
        assertNotEquals(detector.buildKey(candidate()), detector.buildKey(candidate(amount = 12_346L)))
        assertNotEquals(detector.buildKey(candidate()), detector.buildKey(candidate(account = 8L)))
        assertNotEquals(detector.buildKey(candidate()), detector.buildKey(candidate(bookId = 2L)))
    }

    @Test
    fun `toDedupKey 与 buildKey 使用同一秒级语义`() {
        val c = candidate()
        val dedupKey = detector.toDedupKey(c)
        assertEquals(1_700_000_000_000L, dedupKey.billTime)
        assertEquals(12_345L, dedupKey.amountFen)
        assertEquals("星巴克", dedupKey.remark)
        assertEquals(
            1_700_000_000L.toString(),
            (dedupKey.billTime / 1000L).toString()
        )
    }

    @Test
    fun `内存精确去重首次false再次true`() = runBlocking {
        val key = detector.buildKey(candidate())
        assertFalse(detector.isDuplicate(key))
        assertTrue(detector.isDuplicate(key))
        assertTrue(detector.isDuplicate(candidate())) // 重载同样命中
    }

    @Test
    fun `LRU 超过2000键后最旧键失效`() = runBlocking {
        val start = 1_700_000_000_000L
        repeat(2_001) { i ->
            detector.isDuplicate(candidate(time = start + i * 1_000L, amount = i.toLong() + 1L))
        }
        val evicted = candidate(time = start, amount = 1L)
        assertFalse("最旧键应被淘汰", detector.isDuplicate(evicted))
    }

    @Test
    fun `非短信源传入大容差仍只走2秒窗口且未命中返回false`() = runBlocking {
        // F-03 修复后：非短信源忽略调用方传入的 180s，只用 CROSS_SOURCE_WINDOW_SEC=2s，
        // 且要求备注一致；relaxed mock 下库查返回 null，结果应为 false。
        val c = candidate(type = CaptureType.ACCESSIBILITY_TEXT)
        coEvery { repository.findDuplicate(any()) } returns Result.Success(null)
        coEvery {
            repository.findDuplicateInTimeRange(any(), any(), any(), any(), any())
        } returns Result.Success(null)
        assertFalse(detector.isDuplicateWithTolerance(c, 180L))
    }

    @Test
    fun `短信源容差回查命中库内账单`() = runBlocking {
        val c = candidate(type = CaptureType.SMS)
        val existing = Bill(bookId = 1L, amountFen = 12_345L, billTime = 1_700_000_000_000L)
        coEvery {
            repository.findDuplicateInTimeRange(
                bookId = 1L,
                amountFen = 12_345L,
                accountFrom = 7L,
                startMillis = 1_700_000_000_999L - 180_000L,
                endMillis = 1_700_000_000_999L + 180_000L
            )
        } returns Result.Success(existing)
        assertTrue(detector.isDuplicateWithTolerance(c, 180L))
    }

    @Test
    fun `容差为0时短信源退化为精确键`() = runBlocking {
        val c = candidate(type = CaptureType.SMS)
        assertFalse(detector.isDuplicateWithTolerance(c, 0L))
        assertTrue(detector.isDuplicateWithTolerance(c, 0L)) // 第二次命中内存精确键
    }

    // ---------- F-03 回归：库级回查 + 跨源跨秒窗口 ----------

    @Test
    fun `F03回归 新实例内存为空时库级精确键回查仍判重复`() = runBlocking {
        // 模拟进程重启：detector 内存 seen 为空，只有库里已有同键账单
        val restarted = DuplicateDetector(repository)
        val c = candidate(type = CaptureType.NOTIFICATION)
        val existing = Bill(
            bookId = 1L,
            amountFen = 12_345L,
            billTime = 1_700_000_000_000L,
            accountFrom = 7L,
            remark = "星巴克"
        )
        coEvery { repository.findDuplicate(any()) } returns Result.Success(existing)
        assertTrue("进程重启后库内同键账单必须拦住重复建单", restarted.isDuplicateWithTolerance(c, 0L))
    }

    @Test
    fun `F03回归 非短信源读屏与通知跨秒且备注一致命中2秒窗口`() = runBlocking {
        val c = candidate(type = CaptureType.ACCESSIBILITY_TEXT, time = 1_700_000_001_100L)
        coEvery { repository.findDuplicate(any()) } returns Result.Success(null)
        // 通知在 12:00:00.900 已入账，读屏在 12:00:01.100 捕获，备注一致
        val existing = Bill(
            bookId = 1L,
            amountFen = 12_345L,
            billTime = 1_700_000_000_900L,
            accountFrom = 7L,
            remark = "星巴克"
        )
        coEvery {
            repository.findDuplicateInTimeRange(any(), any(), any(), any(), any())
        } returns Result.Success(existing)
        assertTrue(detector.isDuplicateWithTolerance(c, 0L))
    }

    @Test
    fun `F03回归 非短信源跨秒窗口备注不同不误合并`() = runBlocking {
        val c = candidate(type = CaptureType.ACCESSIBILITY_TEXT, time = 1_700_000_001_100L)
        coEvery { repository.findDuplicate(any()) } returns Result.Success(null)
        val existing = Bill(
            bookId = 1L,
            amountFen = 12_345L,
            billTime = 1_700_000_000_900L,
            accountFrom = 7L,
            remark = "瑞幸咖啡"
        )
        coEvery {
            repository.findDuplicateInTimeRange(any(), any(), any(), any(), any())
        } returns Result.Success(existing)
        assertFalse("同额同账户但商家不同，不得因 2s 窗口误合并两笔真实消费", detector.isDuplicateWithTolerance(c, 0L))
    }
}

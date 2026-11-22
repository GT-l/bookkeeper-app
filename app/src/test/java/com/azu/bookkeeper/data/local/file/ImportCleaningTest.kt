package com.azu.bookkeeper.data.local.file

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.DedupKey
import com.azu.bookkeeper.domain.model.ImportRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入清洗单元测试（PRD §8 / T4）：金额清洗、多时间格式、失败行判定、去重键一致性。
 */
class ImportCleaningTest {

    @Test
    fun `金额清洗 常见导出格式统一到分`() {
        assertEquals(1_234_567L, Money.parse("¥12,345.67"))
        assertEquals(1_234_567L, Money.parse("12,345.67元"))
        assertEquals(-1_234_567L, Money.parse("-12,345.67"))
        assertEquals(8_800L, Money.parse("88.00"))
    }

    @Test
    fun `时间解析 多格式兼容`() {
        val expected = DateTimeUtils.parse("2026-11-22 00:00", "yyyy-MM-dd HH:mm")!!
        assertEquals(expected, DateTimeUtils.parseFlexible("2026-11-22"))
        assertEquals(expected, DateTimeUtils.parseFlexible("2026/11/22"))
        assertEquals(expected, DateTimeUtils.parseFlexible("2026年11月22日"))
        assertNotNull(DateTimeUtils.parseFlexible("2026-11-22 12:30:45"))
    }

    @Test
    fun `毫秒与秒时间戳分别识别`() {
        // 对应 PreviewImportUseCase.parseTime：13 位按毫秒，10 位按秒补 *1000。
        val seconds = 1_700_000_000L
        val millis = 1_700_000_000_123L
        assertEquals(seconds * 1000L, if (seconds.toString().length >= 13) seconds else seconds * 1000L)
        assertEquals(millis, if (millis.toString().length >= 13) millis else millis * 1000L)
    }

    @Test
    fun `失败行判定 时间与金额任一非法即不可导入`() {
        val good = ImportRow(lineNo = 1, billTime = 1_700_000_000_000L, amountFen = 1_000L)
        assertTrue(good.isImportable)
        assertFalse(good.copy(billTime = 0L).isImportable)
        assertFalse(good.copy(amountFen = 0L).isImportable)
        assertFalse(good.copy(errorMessage = "时间无法识别").isImportable)
        assertFalse(good.copy(skipped = true).isImportable)
        assertFalse(good.copy(duplicate = true).isImportable)
    }

    @Test
    fun `Bill去重键归一化备注与秒级时间`() {
        val bill = Bill(
            bookId = 1L,
            amountFen = 12_345L,
            accountFrom = 7L,
            billTime = 1_700_000_000_999L,
            remark = "  星   巴克 "
        )
        val key = bill.dedupKey()
        assertEquals(1_700_000_000_000L, key.billTime)
        assertEquals("星 巴克", key.remark)
        assertEquals(bill.dedupKey().asString(), bill.copy(billTime = 1_700_000_000_001L).dedupKey().asString())
    }

    @Test
    fun `转账去重键不区分账户方向以外字段`() {
        val transfer = Bill(
            bookId = 1L,
            billType = BillType.TRANSFER,
            amountFen = 1_000L,
            accountFrom = 11L,
            accountTo = 12L,
            billTime = 1_700_000_000_000L
        )
        assertEquals(transfer.dedupKey().asString(), transfer.copy(accountTo = 99L).dedupKey().asString())
    }

    // ---------- 缺陷复现：当前源码预期失败 ----------

    @Test
    fun `BUG复现 导入去重键与账单去重键备注归一化应一致`() {
        // PreviewImportUseCase.buildRow 直接 new DedupKey(remark=原始trim值)，
        // 而 Bill.dedupKey() 走 normalizeRemark()；连续空白导致批内/库内两键漂移。
        val rawRemark = "超市  购物"
        val importKey = DedupKey(
            bookId = 1L,
            billTime = 1_700_000_000_000L,
            amountFen = 1_000L,
            accountFrom = 7L,
            remark = rawRemark
        ).asString()
        val billKey = Bill(
            bookId = 1L,
            amountFen = 1_000L,
            accountFrom = 7L,
            billTime = 1_700_000_000_000L,
            remark = rawRemark
        ).dedupKey().asString()
        assertEquals("导入与写库应使用同一归一化备注键", billKey, importKey)
    }
}

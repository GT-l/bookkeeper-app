package com.azu.bookkeeper.domain.model

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.md5
import com.azu.bookkeeper.core.normalizeRemark

/**
 * 账单领域模型。金额单位固定为「分」，时间固定为毫秒时间戳。
 * Entity 只在 Data 层流动，本类是 Domain / UI 层唯一账单类型。
 */
data class Bill(
    val billId: Long = 0L,
    val bookId: Long,
    val billType: BillType = BillType.EXPENSE,
    val amountFen: Long,
    val categoryId: Long = 0L,
    val tagIds: List<Long> = emptyList(),
    val accountFrom: Long = 0L,
    val accountTo: Long = 0L,
    val billTime: Long,
    val remark: String = "",
    val imagePaths: List<String> = emptyList(),
    val cycleType: CycleType = CycleType.NONE,
    val cycleEndTime: Long = 0L,
    val source: BillSource = BillSource.MANUAL,
    val captureType: CaptureType = CaptureType.NONE,
    val captureImagePath: String = "",
    val ocrConfidence: Float = 0f,
    val ocrRawText: String = "",
    val verifyStatus: VerifyStatus = VerifyStatus.AUTO_PASSED,
    val createAt: Long = System.currentTimeMillis(),
    val updateAt: Long = System.currentTimeMillis()
) {
    val isTransfer: Boolean get() = billType == BillType.TRANSFER

    val isAdjustment: Boolean get() = source == BillSource.ADJUSTMENT

    /** 统一去重键：账本 + 秒级时间 + 金额 + 账户 + 归一化备注（AC-6 / §8.2）。 */
    fun dedupKey(): DedupKey = DedupKey(
        bookId = bookId,
        billTime = DateTimeUtils.truncateToSecond(billTime),
        amountFen = amountFen,
        accountFrom = accountFrom,
        remark = remark.normalizeRemark()
    )
}

/** 去重键；billTime 已在构造时按秒取整，DAO 直接等值匹配。 */
data class DedupKey(
    val bookId: Long,
    val billTime: Long,
    val amountFen: Long,
    val accountFrom: Long,
    val remark: String
) {
    /**
     * 稳定字符串键，供内存 LRU（双路捕获）与导入去重共用。
     * remark 在此统一归一化：与 Bill.dedupKey() / 写库时 Bill.normalized() 保持同一语义，
     * 避免「超市  购物」与「超市 购物」被当成两条账单（QA F-07）。
     */
    fun asString(): String =
        "$bookId|$billTime|$amountFen|$accountFrom|${remark.normalizeRemark().md5()}"
}

/** 搜索 / 高级筛选条件（PRD §15）。 */
data class BillQuery(
    val bookId: Long,
    val keyword: String = "",
    val billType: BillType? = null,
    val categoryIds: List<Long> = emptyList(),
    val accountIds: List<Long> = emptyList(),
    val tagIds: List<Long> = emptyList(),
    val sources: List<BillSource> = emptyList(),
    val startMillis: Long = 0L,
    val endMillis: Long = Long.MAX_VALUE,
    val minAmountFen: Long? = null,
    val maxAmountFen: Long? = null,
    val sortDescending: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0
)

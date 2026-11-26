package com.azu.bookkeeper.domain.model

/** 分期计划：创建时按本金生成首期账单，剩余期数在每期确认时逐笔生成支出账单。 */
data class Installment(
    val installmentId: Long = 0L,
    val bookId: Long,
    val title: String,
    val totalFen: Long,
    val periods: Int,
    val paidPeriods: Int = 0,
    /** 每期应还金额（分），最后一期吸收除不尽的尾差。 */
    val perPeriodFen: Long,
    val accountId: Long = 0L,
    val categoryId: Long = 0L,
    val note: String = "",
    val createAt: Long = System.currentTimeMillis()
) {
    val remainingPeriods: Int get() = (periods - paidPeriods).coerceAtLeast(0)
    val finished: Boolean get() = paidPeriods >= periods
    /** 下一期金额：最后一期把累计误差补齐，保证各期之和严格等于 totalFen。 */
    fun amountForNextPeriod(): Long = if (remainingPeriods <= 1) {
        (totalFen - perPeriodFen * paidPeriods).coerceAtLeast(0L)
    } else perPeriodFen
}

/** 分期某一期的还款流水。 */
data class InstallmentPayment(
    val paymentId: Long = 0L,
    val installmentId: Long,
    val periodIndex: Int,
    val amountFen: Long,
    val payTime: Long,
    val billId: Long = 0L
)

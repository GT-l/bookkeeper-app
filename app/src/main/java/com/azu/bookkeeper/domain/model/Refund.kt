package com.azu.bookkeeper.domain.model

/** 退款：关联原支出账单，退款金额不超过原账单金额（分）。 */
data class Refund(
    val refundId: Long = 0L,
    val bookId: Long,
    val sourceBillId: Long,
    val amountFen: Long,
    val refundTime: Long,
    val reason: String = "",
    val createAt: Long = System.currentTimeMillis()
)

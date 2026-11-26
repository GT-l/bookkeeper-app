package com.azu.bookkeeper.domain.model

/** 报销状态：待提交 / 待到账 / 已到账 / 已驳回。 */
enum class ReimburseStatus(val code: Int, val label: String) {
    PENDING(0, "待提交"),
    SUBMITTED(1, "待到账"),
    RECEIVED(2, "已到账"),
    REJECTED(3, "已驳回");

    companion object {
        fun fromCode(code: Int): ReimburseStatus = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

/** 报销单：记录垫付支出，到账时生成一笔收入账单并回填 incomeBillId。 */
data class Reimbursement(
    val reimbursementId: Long = 0L,
    val bookId: Long,
    val title: String,
    val amountFen: Long,
    val status: ReimburseStatus = ReimburseStatus.PENDING,
    val sourceBillId: Long = 0L,
    val incomeBillId: Long = 0L,
    val accountId: Long = 0L,
    val createAt: Long = System.currentTimeMillis(),
    val updateAt: Long = System.currentTimeMillis()
)

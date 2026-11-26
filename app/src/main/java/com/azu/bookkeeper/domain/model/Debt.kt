package com.azu.bookkeeper.domain.model

/** 债务方向：借入（我欠别人）/ 借出（别人欠我）。 */
enum class DebtDirection(val code: Int, val label: String) {
    BORROW(0, "借入"),
    LEND(1, "借出");

    companion object {
        fun fromCode(code: Int): DebtDirection = entries.firstOrNull { it.code == code } ?: BORROW
    }
}

enum class DebtStatus(val code: Int, val label: String) {
    ONGOING(0, "进行中"),
    SETTLED(1, "已结清");

    companion object {
        fun fromCode(code: Int): DebtStatus = entries.firstOrNull { it.code == code } ?: ONGOING
    }
}

/**
 * 债务 / 借贷。借入时生成一笔收入账单（钱进账），借出时生成一笔支出账单（钱出账）；
 * 每次还款/收款生成反向账单并累加 settledFen，全部走 BillRepository 事务入口。
 */
data class Debt(
    val debtId: Long = 0L,
    val bookId: Long,
    val direction: DebtDirection,
    val counterparty: String,
    val totalFen: Long,
    val settledFen: Long = 0L,
    val accountId: Long = 0L,
    val dueDate: String = "",
    val note: String = "",
    val status: DebtStatus = DebtStatus.ONGOING,
    val createAt: Long = System.currentTimeMillis(),
    val updateAt: Long = System.currentTimeMillis()
) {
    val remainingFen: Long get() = (totalFen - settledFen).coerceAtLeast(0L)
    val settledPercent: Int
        get() = if (totalFen <= 0L) 100 else ((settledFen * 100) / totalFen).coerceIn(0, 100)
}

/** 单笔还款记录。 */
data class DebtRepayment(
    val repaymentId: Long = 0L,
    val debtId: Long,
    val amountFen: Long,
    val repayTime: Long,
    val billId: Long = 0L,
    val note: String = ""
)

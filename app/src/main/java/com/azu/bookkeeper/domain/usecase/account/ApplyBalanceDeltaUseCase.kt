package com.azu.bookkeeper.domain.usecase.account

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/**
 * 账户余额增减：不直接写 Account 表，而是生成一条 ADJUSTMENT 账单，
 * 经 BillRepository 的单一事务入口入账（不计入收支统计 / 预算）。
 */
class ApplyBalanceDeltaUseCase @Inject constructor(
    private val billRepository: BillRepository
) {

    suspend operator fun invoke(
        bookId: Long,
        accountId: Long,
        deltaFen: Long,
        billTime: Long = DateTimeUtils.nowMillis(),
        remark: String = "余额调整"
    ): Result<Long> {
        if (accountId <= 0L || deltaFen == 0L) {
            return Result.Error(AppError.Balance(userMessage = "账户或调整金额无效"))
        }
        val bill = Bill(
            bookId = bookId,
            billType = if (deltaFen > 0L) BillType.INCOME else BillType.EXPENSE,
            amountFen = Money.absFen(deltaFen),
            accountFrom = accountId,
            billTime = billTime,
            remark = remark,
            source = BillSource.ADJUSTMENT
        )
        return billRepository.addWithBalanceTx(bill)
    }
}

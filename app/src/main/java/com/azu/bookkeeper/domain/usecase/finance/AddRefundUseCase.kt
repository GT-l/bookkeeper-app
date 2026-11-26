package com.azu.bookkeeper.domain.usecase.finance

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.data.local.db.dao.RefundDao
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.Refund
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/**
 * 登记退款：把原支出账单的一部分/全部退回来。
 *
 * 主流做法（钱迹 / 随手记）：退款生成一笔关联原账单的收入流水，余额自动加回，
 * 退款记录本身只存「关联关系 + 金额」，不单独改余额——避免两处写余额导致对不上。
 * 同一账单可多次部分退款，累计不超过原账单金额。
 */
class AddRefundUseCase @Inject constructor(
    private val refundDao: RefundDao,
    private val billRepository: BillRepository
) {
    suspend operator fun invoke(refund: Refund): Result<Long> {
        if (refund.amountFen <= 0L) {
            return Result.Error(AppError.Validation(code = "E_REFUND_001", userMessage = "退款金额必须大于 0"))
        }
        val original = billRepository.getBill(refund.sourceBillId).getOrNull()
            ?: return Result.Error(AppError.Validation(code = "E_REFUND_002", userMessage = "原账单不存在"))
        val alreadyRefunded = refundDao.sumByBill(refund.sourceBillId)
        if (alreadyRefunded + refund.amountFen > original.amountFen) {
            return Result.Error(
                AppError.Validation(
                    code = "E_REFUND_003",
                    userMessage = "累计退款不能超过原账单金额"
                )
            )
        }
        // 生成退款收入账单：走单一事务写入口，余额由 BillRepository 统一处理
        val incomeResult = billRepository.addWithBalanceTx(
            Bill(
                bookId = refund.bookId,
                billType = com.azu.bookkeeper.domain.model.BillType.INCOME,
                amountFen = refund.amountFen,
                categoryId = original.categoryId,
                accountFrom = original.accountFrom,
                billTime = refund.refundTime.takeIf { it > 0L } ?: DateTimeUtils.nowMillis(),
                remark = "退款 · ${original.remark.ifBlank { "原账单" }}".take(80),
                source = com.azu.bookkeeper.domain.model.BillSource.MANUAL
            )
        )
        if (incomeResult is Result.Error) return Result.Error(incomeResult.error)
        val saved = refund.copy(refundTime = refund.refundTime.takeIf { it > 0L } ?: DateTimeUtils.nowMillis())
        return runCatching { Result.Success(refundDao.insert(saved.toEntity())) }
            .getOrElse { Result.Error(AppError.from(it)) }
    }
}

package com.azu.bookkeeper.domain.usecase.finance

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.data.local.db.dao.DebtDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Debt
import com.azu.bookkeeper.domain.model.DebtDirection
import com.azu.bookkeeper.domain.model.DebtRepayment
import com.azu.bookkeeper.domain.model.DebtStatus
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/**
 * 新增债务：借入 → 生成收入账单（钱进自己账户）；借出 → 生成支出账单（钱出去）。
 * 与账单共用事务入口，债务表只记「对方、期限、已还」等元数据。
 */
class AddDebtUseCase @Inject constructor(
    private val dao: DebtDao,
    private val billRepository: BillRepository
) {
    suspend operator fun invoke(debt: Debt): Result<Long> {
        if (debt.counterparty.isBlank()) {
            return Result.Error(AppError.Validation(code = "E_DEBT_001", userMessage = "请填写对方姓名"))
        }
        if (debt.totalFen <= 0L) {
            return Result.Error(AppError.Validation(code = "E_DEBT_002", userMessage = "金额必须大于 0"))
        }
        val type = if (debt.direction == DebtDirection.BORROW) BillType.INCOME else BillType.EXPENSE
        val billResult = billRepository.addWithBalanceTx(
            Bill(
                bookId = debt.bookId,
                billType = type,
                amountFen = debt.totalFen,
                categoryId = 0L,
                accountFrom = debt.accountId,
                billTime = DateTimeUtils.nowMillis(),
                remark = "${debt.direction.label} · ${debt.counterparty}".take(80)
            )
        )
        if (billResult is Result.Error) return Result.Error(billResult.error)
        val now = DateTimeUtils.nowMillis()
        return runCatching {
            Result.Success(dao.insert(debt.copy(createAt = now, updateAt = now).toEntity()))
        }.getOrElse { Result.Error(AppError.from(it)) }
    }
}

/**
 * 登记一笔还款 / 收款：借入的还款是支出，借出的收款是收入。
 * 累加 settledFen；还清时自动把债务标记为已结清。
 */
class RepayDebtUseCase @Inject constructor(
    private val dao: DebtDao,
    private val billRepository: BillRepository
) {
    suspend operator fun invoke(
        debtId: Long,
        amountFen: Long,
        note: String = ""
    ): Result<Long> {
        if (amountFen <= 0L) {
            return Result.Error(AppError.Validation(code = "E_DEBT_003", userMessage = "金额必须大于 0"))
        }
        val debt = dao.findById(debtId)?.toDomain()
            ?: return Result.Error(AppError.Validation(code = "E_DEBT_004", userMessage = "债务不存在"))
        if (amountFen > debt.remainingFen) {
            return Result.Error(AppError.Validation(code = "E_DEBT_005", userMessage = "还款金额不能超过剩余欠款"))
        }
        val type = if (debt.direction == DebtDirection.BORROW) BillType.EXPENSE else BillType.INCOME
        val billResult = billRepository.addWithBalanceTx(
            Bill(
                bookId = debt.bookId,
                billType = type,
                amountFen = amountFen,
                categoryId = 0L,
                accountFrom = debt.accountId,
                billTime = DateTimeUtils.nowMillis(),
                remark = "${if (debt.direction == DebtDirection.BORROW) "还款" else "收回借款"} · ${debt.counterparty}".take(80)
            )
        )
        if (billResult is Result.Error) return Result.Error(billResult.error)
        val billId = billResult.getOrNull() ?: 0L
        val settled = (debt.settledFen + amountFen).coerceAtMost(debt.totalFen)
        val updated = debt.copy(
            settledFen = settled,
            status = if (settled >= debt.totalFen) DebtStatus.SETTLED else DebtStatus.ONGOING,
            updateAt = DateTimeUtils.nowMillis()
        )
        return runCatching {
            dao.update(updated.toEntity())
            Result.Success(
                dao.insertRepayment(
                    DebtRepayment(
                        debtId = debtId, amountFen = amountFen,
                        repayTime = DateTimeUtils.nowMillis(), billId = billId, note = note
                    ).toEntity()
                )
            )
        }.getOrElse { Result.Error(AppError.from(it)) }
    }
}

/** 删除债务（连同还款记录；账单一并回滚由调用方处理，这里只删元数据）。 */
class DeleteDebtUseCase @Inject constructor(private val dao: DebtDao) {
    suspend operator fun invoke(debtId: Long): Result<Unit> = runCatching {
        dao.deleteById(debtId)
        Result.Success(Unit)
    }.getOrElse { Result.Error(AppError.from(it)) }
}

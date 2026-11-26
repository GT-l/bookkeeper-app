package com.azu.bookkeeper.domain.usecase.finance

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.data.local.db.dao.InstallmentDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Installment
import com.azu.bookkeeper.domain.model.InstallmentPayment
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 建立分期计划：立刻生成首期支出账单，后续每期在确认时逐笔生成。 */
class AddInstallmentUseCase @Inject constructor(
    private val dao: InstallmentDao,
    private val billRepository: BillRepository
) {
    suspend operator fun invoke(plan: Installment): Result<Long> {
        if (plan.title.isBlank()) {
            return Result.Error(AppError.Validation(code = "E_INST_001", userMessage = "请填写分期名称"))
        }
        if (plan.totalFen <= 0L) {
            return Result.Error(AppError.Validation(code = "E_INST_002", userMessage = "总额必须大于 0"))
        }
        if (plan.periods !in 2..60) {
            return Result.Error(AppError.Validation(code = "E_INST_003", userMessage = "期数需在 2~60 之间"))
        }
        val perPeriod = plan.totalFen / plan.periods
        val normalized = plan.copy(perPeriodFen = perPeriod, paidPeriods = 0)
        val firstResult = billRepository.addWithBalanceTx(
            Bill(
                bookId = plan.bookId,
                billType = BillType.EXPENSE,
                amountFen = normalized.amountForNextPeriod(),
                categoryId = plan.categoryId,
                accountFrom = plan.accountId,
                billTime = DateTimeUtils.nowMillis(),
                remark = "${plan.title} · 第1/${plan.periods}期".take(80)
            )
        )
        if (firstResult is Result.Error) return Result.Error(firstResult.error)
        val planId = runCatching { dao.insert(normalized.toEntity()) }
            .getOrElse { return Result.Error(AppError.from(it)) }
        val firstBillId = firstResult.getOrNull() ?: 0L
        runCatching {
            dao.insertPayment(
                InstallmentPayment(
                    installmentId = planId, periodIndex = 1,
                    amountFen = normalized.amountForNextPeriod(),
                    payTime = DateTimeUtils.nowMillis(), billId = firstBillId
                ).toEntity()
            )
        }
        return Result.Success(planId)
    }
}

/** 确认下一期：生成该期支出账单，paidPeriods +1，最后一期自动补齐尾差。 */
class PayInstallmentUseCase @Inject constructor(
    private val dao: InstallmentDao,
    private val billRepository: BillRepository
) {
    suspend operator fun invoke(planId: Long): Result<Long> {
        val plan = dao.findById(planId)?.toDomain()
            ?: return Result.Error(AppError.Validation(code = "E_INST_004", userMessage = "分期计划不存在"))
        if (plan.finished) {
            return Result.Error(AppError.Validation(code = "E_INST_005", userMessage = "该分期已还清"))
        }
        val nextIndex = plan.paidPeriods + 1
        val amount = plan.amountForNextPeriod()
        val billResult = billRepository.addWithBalanceTx(
            Bill(
                bookId = plan.bookId,
                billType = BillType.EXPENSE,
                amountFen = amount,
                categoryId = plan.categoryId,
                accountFrom = plan.accountId,
                billTime = DateTimeUtils.nowMillis(),
                remark = "${plan.title} · 第$nextIndex/${plan.periods}期".take(80)
            )
        )
        if (billResult is Result.Error) return Result.Error(billResult.error)
        val billId = billResult.getOrNull() ?: 0L
        return runCatching {
            dao.update(plan.copy(paidPeriods = nextIndex).toEntity())
            Result.Success(
                dao.insertPayment(
                    InstallmentPayment(
                        installmentId = planId, periodIndex = nextIndex,
                        amountFen = amount, payTime = DateTimeUtils.nowMillis(), billId = billId
                    ).toEntity()
                )
            )
        }.getOrElse { Result.Error(AppError.from(it)) }
    }
}

class DeleteInstallmentUseCase @Inject constructor(private val dao: InstallmentDao) {
    suspend operator fun invoke(planId: Long): Result<Unit> = runCatching {
        dao.deleteById(planId)
        Result.Success(Unit)
    }.getOrElse { Result.Error(AppError.from(it)) }
}

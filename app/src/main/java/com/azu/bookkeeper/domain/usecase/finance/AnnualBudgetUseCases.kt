package com.azu.bookkeeper.domain.usecase.finance

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.data.local.db.dao.AnnualBudgetDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.AnnualBudget
import javax.inject.Inject

/** 保存年度预算（0 表示清除该年度预算）。 */
class SaveAnnualBudgetUseCase @Inject constructor(private val dao: AnnualBudgetDao) {
    suspend operator fun invoke(budget: AnnualBudget): Result<Unit> {
        if (budget.bookId <= 0L || budget.year < 2000) {
            return Result.Error(AppError.Validation(code = "E_ANNB_001", userMessage = "年度预算参数不合法"))
        }
        return runCatching {
            if (budget.totalLimitFen <= 0L) {
                dao.delete(budget.bookId, budget.year)
            } else {
                dao.insert(budget.toEntity())
            }
            Result.Success(Unit)
        }.getOrElse { Result.Error(AppError.from(it)) }
    }
}

class ObserveAnnualBudgetUseCase @Inject constructor(private val dao: AnnualBudgetDao) {
    operator fun invoke(bookId: Long, year: Int) = dao.observe(bookId, year)
}

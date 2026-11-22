package com.azu.bookkeeper.domain.usecase.budget

import com.azu.bookkeeper.domain.model.Budget
import com.azu.bookkeeper.domain.repository.BudgetRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 预算相关用例集合。 */

class ObserveBudgetUseCase @Inject constructor(
    private val repository: BudgetRepository
) {
    operator fun invoke(bookId: Long, month: Int): Flow<Budget?> =
        repository.observeByBookAndMonth(bookId, month)
}

class SaveBudgetUseCase @Inject constructor(
    private val repository: BudgetRepository
) {
    suspend operator fun invoke(budget: Budget) = repository.save(budget)
}

class DeleteBudgetUseCase @Inject constructor(
    private val repository: BudgetRepository
) {
    suspend operator fun invoke(budgetId: Long) = repository.delete(budgetId)
}

class ResetBudgetUseCase @Inject constructor(
    private val repository: BudgetRepository
) {
    suspend operator fun invoke(bookId: Long, month: Int) = repository.resetMonth(bookId, month)
}

class GetBudgetUseCase @Inject constructor(
    private val repository: BudgetRepository
) {
    suspend operator fun invoke(bookId: Long, month: Int) =
        repository.findByBookAndMonth(bookId, month)
}

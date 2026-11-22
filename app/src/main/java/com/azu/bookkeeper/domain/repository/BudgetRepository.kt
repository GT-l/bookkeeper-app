package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Budget
import kotlinx.coroutines.flow.Flow

interface BudgetRepository {

    fun observeByBookAndMonth(bookId: Long, month: Int): Flow<Budget?>

    suspend fun findByBookAndMonth(bookId: Long, month: Int): Result<Budget?>

    suspend fun save(budget: Budget): Result<Long>

    suspend fun delete(budgetId: Long): Result<Unit>

    /** 每月 1 号重置：清空当月已用（预算上限保留由调用方决定）。 */
    suspend fun resetMonth(bookId: Long, month: Int): Result<Unit>
}

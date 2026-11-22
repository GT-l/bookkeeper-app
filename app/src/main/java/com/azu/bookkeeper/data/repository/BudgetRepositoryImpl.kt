package com.azu.bookkeeper.data.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.dao.BudgetDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Budget
import com.azu.bookkeeper.domain.repository.BudgetRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepositoryImpl @Inject constructor(
    private val budgetDao: BudgetDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BudgetRepository {

    override fun observeByBookAndMonth(bookId: Long, month: Int): Flow<Budget?> =
        budgetDao.observeByBookAndMonth(bookId, month).map { it?.toDomain() }

    override suspend fun findByBookAndMonth(bookId: Long, month: Int): Result<Budget?> = safeResult {
        withContext(ioDispatcher) { budgetDao.findByBookAndMonth(bookId, month)?.toDomain() }
    }

    override suspend fun save(budget: Budget): Result<Long> = safeResult {
        withContext(ioDispatcher) { budgetDao.insert(budget.toEntity()) }
    }

    override suspend fun delete(budgetId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) { budgetDao.deleteById(budgetId) }
    }

    /**
     * 新月重置：沿用上月预算上限，使用量由当月账单实时统计，故天然归零。
     * 上月无预算时清除当月记录。
     */
    override suspend fun resetMonth(bookId: Long, month: Int): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val yearMonth = YearMonth.of(month / 100, month % 100)
            val previousKey = yearMonth.minusMonths(1).let { it.year * 100 + it.monthValue }
            val previous = budgetDao.findByBookAndMonth(bookId, previousKey)
            if (previous != null) {
                budgetDao.insert(previous.copy(budgetId = 0L, month = month))
            } else {
                budgetDao.findByBookAndMonth(bookId, month)?.let { budgetDao.deleteById(it.budgetId) }
            }
        }
    }
}

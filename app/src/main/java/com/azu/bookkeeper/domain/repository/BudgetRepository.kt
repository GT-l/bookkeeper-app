package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Budget
import kotlinx.coroutines.flow.Flow

interface BudgetRepository {

    fun observeByBookAndMonth(bookId: Long, month: Int): Flow<Budget?>

    suspend fun findByBookAndMonth(bookId: Long, month: Int): Result<Budget?>

    suspend fun save(budget: Budget): Result<Long>

    suspend fun delete(budgetId: Long): Result<Unit>

    /**
     * 生效总预算（分）：显式设置的总预算优先，未设置（<= 0）时按各分类预算之和兜底。
     *
     * 这是「总预算未设置 → 分类预算相加」这条规则的统一读取入口：
     * 预算页、超支提醒 Worker、桌面组件、统计概览都从这里取数，避免各处重复实现。
     * 读取失败或该月无预算记录时返回 0（等价于“没有预算”，不触发超支告警）。
     */
    suspend fun effectiveTotalLimit(bookId: Long, month: Int): Long

    /** 每月 1 号重置：清空当月已用（预算上限保留由调用方决定）。 */
    suspend fun resetMonth(bookId: Long, month: Int): Result<Unit>
}

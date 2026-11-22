package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Account
import kotlinx.coroutines.flow.Flow

/**
 * 账户仓储契约。仅提供元数据 CRUD 与只读余额观察；
 * 余额变更一律经 BillRepository 的 @Transaction 单一写入口。
 */
interface AccountRepository {

    fun observeAll(bookId: Long): Flow<List<Account>>

    suspend fun findById(accountId: Long): Result<Account?>

    suspend fun findByName(bookId: Long, name: String): Result<Account?>

    suspend fun insert(account: Account): Result<Long>

    suspend fun update(account: Account): Result<Unit>

    suspend fun delete(accountId: Long): Result<Unit>

    suspend fun totalBalance(bookId: Long): Result<Long>

    fun observeTotalBalance(bookId: Long): Flow<Long>
}

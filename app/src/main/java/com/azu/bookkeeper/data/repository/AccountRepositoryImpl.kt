package com.azu.bookkeeper.data.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.dao.AccountDao
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: AccountDao,
    private val billDao: BillDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AccountRepository {

    override fun observeAll(bookId: Long): Flow<List<Account>> =
        accountDao.observeAll(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun findById(accountId: Long): Result<Account?> = safeResult {
        withContext(ioDispatcher) { accountDao.findById(accountId)?.toDomain() }
    }

    override suspend fun findByName(bookId: Long, name: String): Result<Account?> = safeResult {
        withContext(ioDispatcher) { accountDao.findByName(bookId, name.trim())?.toDomain() }
    }

    override suspend fun insert(account: Account): Result<Long> = safeResult {
        withContext(ioDispatcher) { accountDao.insert(account.toEntity()) }
    }

    override suspend fun update(account: Account): Result<Unit> = safeResult {
        withContext(ioDispatcher) { accountDao.update(account.toEntity()) }
    }

    /** 有账单引用的账户不允许直接删除，须先迁移账单账户。 */
    override suspend fun delete(accountId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            if (billDao.countByAccount(accountId) > 0) {
                throw IllegalArgumentException("account $accountId still has bills, migrate first")
            }
            accountDao.deleteById(accountId)
        }
    }

    override suspend fun totalBalance(bookId: Long): Result<Long> = safeResult {
        withContext(ioDispatcher) { accountDao.totalBalance(bookId) }
    }

    override fun observeTotalBalance(bookId: Long): Flow<Long> = accountDao.observeTotalBalance(bookId)
}

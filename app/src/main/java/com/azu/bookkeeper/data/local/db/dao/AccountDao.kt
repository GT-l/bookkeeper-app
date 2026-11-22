package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE book_id = :bookId ORDER BY enabled DESC, account_id ASC")
    fun observeAll(bookId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE book_id = :bookId AND enabled = 1 ORDER BY account_id ASC")
    fun observeEnabled(bookId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE account_id = :accountId")
    suspend fun findById(accountId: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE book_id = :bookId AND name = :name LIMIT 1")
    suspend fun findByName(bookId: Long, name: String): AccountEntity?

    /**
     * 余额增减的唯一底层原语，调用方必须处于 withTransaction 事务中，
     * 且只能由 BillRepositoryImpl 驱动（架构 N4：UI 禁止直写账户表）。
     */
    @Query("UPDATE accounts SET balance_fen = balance_fen + :deltaFen WHERE account_id = :accountId")
    suspend fun updateBalance(accountId: Long, deltaFen: Long): Int

    @Query("SELECT COALESCE(SUM(balance_fen), 0) FROM accounts WHERE book_id = :bookId AND enabled = 1")
    fun observeTotalBalance(bookId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(balance_fen), 0) FROM accounts WHERE book_id = :bookId AND enabled = 1")
    suspend fun totalBalance(bookId: Long): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>): List<Long>

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE account_id = :accountId")
    suspend fun deleteById(accountId: Long)

    @Query("DELETE FROM accounts WHERE book_id = :bookId")
    suspend fun deleteAllByBook(bookId: Long)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

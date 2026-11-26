package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.DebtEntity
import com.azu.bookkeeper.data.local.db.entity.DebtRepaymentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts WHERE book_id = :bookId ORDER BY create_at DESC")
    fun observeByBook(bookId: Long): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE debt_id = :id")
    suspend fun findById(id: Long): DebtEntity?

    @Insert
    suspend fun insert(debt: DebtEntity): Long

    @Update
    suspend fun update(debt: DebtEntity)

    @Query("DELETE FROM debts WHERE debt_id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM debt_repayments WHERE debt_id = :debtId ORDER BY repay_time DESC")
    fun observeRepayments(debtId: Long): Flow<List<DebtRepaymentEntity>>

    @Query("SELECT * FROM debt_repayments WHERE debt_id = :debtId ORDER BY repay_time DESC")
    suspend fun repaymentsOf(debtId: Long): List<DebtRepaymentEntity>

    @Insert
    suspend fun insertRepayment(repayment: DebtRepaymentEntity): Long
}

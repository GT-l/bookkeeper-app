package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.InstallmentEntity
import com.azu.bookkeeper.data.local.db.entity.InstallmentPaymentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallmentDao {
    @Query("SELECT * FROM installments WHERE book_id = :bookId ORDER BY create_at DESC")
    fun observeByBook(bookId: Long): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE installment_id = :id")
    suspend fun findById(id: Long): InstallmentEntity?

    @Insert
    suspend fun insert(plan: InstallmentEntity): Long

    @Update
    suspend fun update(plan: InstallmentEntity)

    @Query("DELETE FROM installments WHERE installment_id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM installment_payments WHERE installment_id = :planId ORDER BY period_index ASC")
    fun observePayments(planId: Long): Flow<List<InstallmentPaymentEntity>>

    @Insert
    suspend fun insertPayment(payment: InstallmentPaymentEntity): Long
}

package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.azu.bookkeeper.data.local.db.entity.RefundEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RefundDao {
    @Query("SELECT * FROM refunds WHERE book_id = :bookId ORDER BY refund_time DESC")
    fun observeByBook(bookId: Long): Flow<List<RefundEntity>>

    @Query("SELECT COALESCE(SUM(amount_fen), 0) FROM refunds WHERE source_bill_id = :billId")
    suspend fun sumByBill(billId: Long): Long

    @Query("SELECT * FROM refunds WHERE source_bill_id = :billId")
    suspend fun findByBill(billId: Long): List<RefundEntity>

    @Insert
    suspend fun insert(refund: RefundEntity): Long

    @Query("DELETE FROM refunds WHERE refund_id = :refundId")
    suspend fun deleteById(refundId: Long)
}

package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.ReimbursementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReimbursementDao {
    @Query("SELECT * FROM reimbursements WHERE book_id = :bookId ORDER BY create_at DESC")
    fun observeByBook(bookId: Long): Flow<List<ReimbursementEntity>>

    @Query("SELECT COALESCE(SUM(amount_fen), 0) FROM reimbursements WHERE book_id = :bookId AND status IN (0,1)")
    suspend fun sumPending(bookId: Long): Long

    @Query("SELECT * FROM reimbursements WHERE reimbursement_id = :id")
    suspend fun findById(id: Long): ReimbursementEntity?

    @Insert
    suspend fun insert(entity: ReimbursementEntity): Long

    @Update
    suspend fun update(entity: ReimbursementEntity)

    @Query("DELETE FROM reimbursements WHERE reimbursement_id = :id")
    suspend fun deleteById(id: Long)
}

package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.azu.bookkeeper.data.local.db.entity.AnnualBudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnualBudgetDao {
    @Query("SELECT * FROM annual_budgets WHERE book_id = :bookId AND year = :year LIMIT 1")
    fun observe(bookId: Long, year: Int): Flow<AnnualBudgetEntity?>

    @Query("SELECT * FROM annual_budgets WHERE book_id = :bookId AND year = :year LIMIT 1")
    suspend fun find(bookId: Long, year: Int): AnnualBudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AnnualBudgetEntity): Long

    @Query("DELETE FROM annual_budgets WHERE book_id = :bookId AND year = :year")
    suspend fun delete(bookId: Long, year: Int)
}

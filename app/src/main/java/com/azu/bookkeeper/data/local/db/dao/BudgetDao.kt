package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE book_id = :bookId AND month = :month LIMIT 1")
    fun observeByBookAndMonth(bookId: Long, month: Int): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE book_id = :bookId AND month = :month LIMIT 1")
    suspend fun findByBookAndMonth(bookId: Long, month: Int): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE book_id = :bookId AND month BETWEEN :startMonth AND :endMonth")
    suspend fun findByRange(bookId: Long, startMonth: Int, endMonth: Int): List<BudgetEntity>

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: BudgetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(budgets: List<BudgetEntity>): List<Long>

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE budget_id = :budgetId")
    suspend fun deleteById(budgetId: Long)

    @Query("DELETE FROM budgets WHERE book_id = :bookId")
    suspend fun deleteAllByBook(bookId: Long)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

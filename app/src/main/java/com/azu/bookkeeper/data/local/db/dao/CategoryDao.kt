package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE book_id = :bookId AND kind = :kind ORDER BY sort_order ASC, category_id ASC")
    fun observeByBook(bookId: Long, kind: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE book_id = :bookId ORDER BY kind ASC, sort_order ASC")
    suspend fun findByBook(bookId: Long): List<CategoryEntity>

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE category_id = :categoryId")
    suspend fun findById(categoryId: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE book_id = :bookId AND kind = :kind AND name = :name LIMIT 1")
    suspend fun findByName(bookId: Long, kind: Int, name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM bills WHERE category_id = :categoryId")
    suspend fun countBills(categoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE category_id = :categoryId")
    suspend fun deleteById(categoryId: Long)

    @Query("DELETE FROM categories WHERE book_id = :bookId")
    suspend fun deleteAllByBook(bookId: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

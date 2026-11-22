package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY is_default DESC, created_at ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY created_at ASC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE book_id = :bookId")
    suspend fun findById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE is_default = 1 LIMIT 1")
    suspend fun findDefault(): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("UPDATE books SET is_default = CASE WHEN book_id = :bookId THEN 1 ELSE 0 END")
    suspend fun setDefault(bookId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookEntity>): List<Long>

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE book_id = :bookId")
    suspend fun deleteById(bookId: Long)

    @Query("DELETE FROM books")
    suspend fun deleteAll()
}

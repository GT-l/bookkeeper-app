package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags WHERE book_id = :bookId ORDER BY tag_id ASC")
    fun observeByBook(bookId: Long): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE book_id = :bookId ORDER BY tag_id ASC")
    suspend fun findByBook(bookId: Long): List<TagEntity>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE tag_id IN (:tagIds)")
    suspend fun findByIds(tagIds: List<Long>): List<TagEntity>

    @Query("SELECT * FROM tags WHERE tag_id = :tagId")
    suspend fun findById(tagId: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE book_id = :bookId AND name = :name LIMIT 1")
    suspend fun findByName(bookId: Long, name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagEntity>): List<Long>

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE tag_id = :tagId")
    suspend fun deleteById(tagId: Long)

    @Query("DELETE FROM tags WHERE book_id = :bookId")
    suspend fun deleteAllByBook(bookId: Long)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}

package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Book
import kotlinx.coroutines.flow.Flow

interface BookRepository {

    fun observeAll(): Flow<List<Book>>

    suspend fun findById(bookId: Long): Result<Book?>

    suspend fun findDefault(): Result<Book?>

    suspend fun insert(book: Book): Result<Long>

    suspend fun update(book: Book): Result<Unit>

    suspend fun archive(bookId: Long, archived: Boolean): Result<Unit>

    suspend fun setDefault(bookId: Long): Result<Unit>

    /** 删除账本及其全部子数据（强提醒 + 二次确认由 UI 负责）。 */
    suspend fun delete(bookId: Long): Result<Unit>

    suspend fun canAddBills(bookId: Long): Result<Boolean>
}

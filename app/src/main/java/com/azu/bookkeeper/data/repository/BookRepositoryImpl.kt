package com.azu.bookkeeper.data.repository

import androidx.room.withTransaction
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.BookkeeperDatabase
import com.azu.bookkeeper.data.local.db.dao.AccountDao
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.BookDao
import com.azu.bookkeeper.data.local.db.dao.BudgetDao
import com.azu.bookkeeper.data.local.db.dao.CategoryDao
import com.azu.bookkeeper.data.local.db.dao.TagDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.BookStatus
import com.azu.bookkeeper.domain.repository.BookRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val database: BookkeeperDatabase,
    private val bookDao: BookDao,
    private val billDao: BillDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val budgetDao: BudgetDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BookRepository {

    override fun observeAll(): Flow<List<Book>> =
        bookDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun findById(bookId: Long): Result<Book?> = safeResult {
        withContext(ioDispatcher) { bookDao.findById(bookId)?.toDomain() }
    }

    override suspend fun findDefault(): Result<Book?> = safeResult {
        withContext(ioDispatcher) { bookDao.findDefault()?.toDomain() }
    }

    override suspend fun insert(book: Book): Result<Long> = safeResult {
        withContext(ioDispatcher) {
            database.withTransaction {
                val id = bookDao.insert(book.toEntity())
                if (book.isDefault) bookDao.setDefault(id)
                id
            }
        }
    }

    override suspend fun update(book: Book): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            database.withTransaction {
                bookDao.update(book.toEntity())
                if (book.isDefault) bookDao.setDefault(book.bookId)
            }
        }
    }

    override suspend fun archive(bookId: Long, archived: Boolean): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val entity = bookDao.findById(bookId)
                ?: throw IllegalArgumentException("book $bookId not found")
            bookDao.update(
                entity.copy(status = if (archived) BookStatus.ARCHIVED.code else BookStatus.ACTIVE.code)
            )
        }
    }

    override suspend fun setDefault(bookId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val exists = bookDao.findById(bookId) != null
            if (!exists) throw IllegalArgumentException("book $bookId not found")
            bookDao.setDefault(bookId)
        }
    }

    /** 删除账本及其下全部账单/账户/分类/标签/预算（不可恢复）。 */
    override suspend fun delete(bookId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            database.withTransaction {
                billDao.deleteAllByBook(bookId)
                accountDao.deleteAllByBook(bookId)
                categoryDao.deleteAllByBook(bookId)
                tagDao.deleteAllByBook(bookId)
                budgetDao.deleteAllByBook(bookId)
                bookDao.deleteById(bookId)
            }
        }
    }

    override suspend fun canAddBills(bookId: Long): Result<Boolean> = safeResult {
        withContext(ioDispatcher) {
            val book = bookDao.findById(bookId)
            book != null && book.status == BookStatus.ACTIVE.code
        }
    }
}

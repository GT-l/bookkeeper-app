package com.azu.bookkeeper.data.repository

import androidx.room.withTransaction
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.BookkeeperDatabase
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.CategoryDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val database: BookkeeperDatabase,
    private val categoryDao: CategoryDao,
    private val billDao: BillDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : CategoryRepository {

    override fun observeByBook(bookId: Long, kind: CategoryKind): Flow<List<Category>> =
        categoryDao.observeByBook(bookId, kind.code).map { list -> list.map { it.toDomain() } }

    override suspend fun findById(categoryId: Long): Result<Category?> = safeResult {
        withContext(ioDispatcher) { categoryDao.findById(categoryId)?.toDomain() }
    }

    override suspend fun findByName(
        bookId: Long,
        kind: CategoryKind,
        name: String
    ): Result<Category?> = safeResult {
        withContext(ioDispatcher) { categoryDao.findByName(bookId, kind.code, name)?.toDomain() }
    }

    override suspend fun insert(category: Category): Result<Long> = safeResult {
        withContext(ioDispatcher) { categoryDao.insert(category.toEntity()) }
    }

    override suspend fun update(category: Category): Result<Unit> = safeResult {
        withContext(ioDispatcher) { categoryDao.update(category.toEntity()) }
    }

    /** 有账单绑定时必须先迁移；migrateToCategoryId=0 时拒绝删除并交由 UI 提示。 */
    override suspend fun delete(categoryId: Long, migrateToCategoryId: Long): Result<Boolean> =
        safeResult {
            withContext(ioDispatcher) {
                val bound = billDao.countBills(categoryId) > 0
                if (bound && migrateToCategoryId == 0L) {
                    false
                } else {
                    database.withTransaction {
                        if (bound) billDao.migrateCategory(categoryId, migrateToCategoryId)
                        categoryDao.deleteById(categoryId)
                    }
                    true
                }
            }
        }

    override suspend fun ensureDefaults(bookId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            database.withTransaction {
                val existing = categoryDao.findByBook(bookId)
                if (existing.isNotEmpty()) return@withTransaction
                val expense = listOf(
                    "餐饮", "交通", "住房", "购物", "医疗", "娱乐", "通讯", "学习", "人情", "其他支出"
                )
                val income = listOf("工资", "奖金", "红包", "兼职", "理财收益", "其他收入")
                val entities = mutableListOf<com.azu.bookkeeper.data.local.db.entity.CategoryEntity>()
                expense.forEachIndexed { index, name ->
                    entities.add(
                        Category(
                            bookId = bookId,
                            kind = CategoryKind.EXPENSE,
                            name = name,
                            sortOrder = index
                        ).toEntity()
                    )
                }
                income.forEachIndexed { index, name ->
                    entities.add(
                        Category(
                            bookId = bookId,
                            kind = CategoryKind.INCOME,
                            name = name,
                            sortOrder = index
                        ).toEntity()
                    )
                }
                categoryDao.insertAll(entities)
            }
        }
    }

    override suspend fun matchByName(
        bookId: Long,
        kind: CategoryKind,
        name: String
    ): Result<Category?> = safeResult {
        withContext(ioDispatcher) {
            categoryDao.findByName(bookId, kind.code, name.trim())?.toDomain()
        }
    }
}

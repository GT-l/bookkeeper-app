package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {

    fun observeByBook(bookId: Long, kind: CategoryKind): Flow<List<Category>>

    suspend fun findById(categoryId: Long): Result<Category?>

    suspend fun findByName(bookId: Long, kind: CategoryKind, name: String): Result<Category?>

    suspend fun insert(category: Category): Result<Long>

    suspend fun update(category: Category): Result<Unit>

    /** 有账单绑定时先迁移再删，返回是否直接删除成功。 */
    suspend fun delete(categoryId: Long, migrateToCategoryId: Long = 0L): Result<Boolean>

    suspend fun ensureDefaults(bookId: Long): Result<Unit>

    suspend fun matchByName(bookId: Long, kind: CategoryKind, name: String): Result<Category?>
}

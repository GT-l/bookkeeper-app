package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface TagRepository {

    fun observeByBook(bookId: Long): Flow<List<Tag>>

    suspend fun findByIds(ids: List<Long>): Result<List<Tag>>

    suspend fun findByName(bookId: Long, name: String): Result<Tag?>

    /** 已存在同名标签时直接返回其 ID，供导入自动匹配。 */
    suspend fun getOrCreate(bookId: Long, name: String): Result<Long>

    suspend fun update(tag: Tag): Result<Unit>

    /** 删除标签只取消绑定，不删除账单。 */
    suspend fun delete(tagId: Long): Result<Unit>
}

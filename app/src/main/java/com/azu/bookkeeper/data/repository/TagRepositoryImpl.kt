package com.azu.bookkeeper.data.repository

import androidx.room.withTransaction
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.BookkeeperDatabase
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.TagDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Tag
import com.azu.bookkeeper.domain.repository.TagRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepositoryImpl @Inject constructor(
    private val database: BookkeeperDatabase,
    private val tagDao: TagDao,
    private val billDao: BillDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : TagRepository {

    override fun observeByBook(bookId: Long): Flow<List<Tag>> =
        tagDao.observeByBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun findByIds(ids: List<Long>): Result<List<Tag>> = safeResult {
        withContext(ioDispatcher) {
            if (ids.isEmpty()) emptyList() else tagDao.findByIds(ids).map { it.toDomain() }
        }
    }

    override suspend fun findByName(bookId: Long, name: String): Result<Tag?> = safeResult {
        withContext(ioDispatcher) { tagDao.findByName(bookId, name.trim())?.toDomain() }
    }

    override suspend fun getOrCreate(bookId: Long, name: String): Result<Long> = safeResult {
        withContext(ioDispatcher) {
            database.withTransaction {
                tagDao.findByName(bookId, name.trim())?.tagId
                    ?: tagDao.insert(Tag(bookId = bookId, name = name.trim()).toEntity())
            }
        }
    }

    override suspend fun update(tag: Tag): Result<Unit> = safeResult {
        withContext(ioDispatcher) { tagDao.update(tag.toEntity()) }
    }

    /** 删除标签只取消账单绑定，不删除账单本身。 */
    override suspend fun delete(tagId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val tag = tagDao.findById(tagId)
                ?: throw IllegalArgumentException("tag $tagId not found")
            database.withTransaction {
                billDao.findAllByBook(tag.bookId).forEach { entity ->
                    val bill = entity.toDomain()
                    if (tagId in bill.tagIds) {
                        billDao.update(
                            bill.copy(tagIds = bill.tagIds.filter { it != tagId }).toEntity()
                        )
                    }
                }
                tagDao.deleteById(tagId)
            }
        }
    }
}

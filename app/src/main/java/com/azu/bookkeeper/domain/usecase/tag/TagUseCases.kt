package com.azu.bookkeeper.domain.usecase.tag

import com.azu.bookkeeper.domain.model.Tag
import com.azu.bookkeeper.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 标签相关用例集合。 */

class ObserveTagsUseCase @Inject constructor(
    private val repository: TagRepository
) {
    operator fun invoke(bookId: Long): Flow<List<Tag>> = repository.observeByBook(bookId)
}

class AddTagUseCase @Inject constructor(
    private val repository: TagRepository
) {
    suspend operator fun invoke(tag: Tag) = repository.getOrCreate(tag.bookId, tag.name)
}

class UpdateTagUseCase @Inject constructor(
    private val repository: TagRepository
) {
    suspend operator fun invoke(tag: Tag) = repository.update(tag)
}

class DeleteTagUseCase @Inject constructor(
    private val repository: TagRepository
) {
    suspend operator fun invoke(tagId: Long) = repository.delete(tagId)
}

class GetTagsUseCase @Inject constructor(
    private val repository: TagRepository
) {
    suspend operator fun invoke(ids: List<Long>) = repository.findByIds(ids)
}

package com.azu.bookkeeper.domain.usecase.category

import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 分类相关用例集合。 */

class ObserveCategoriesUseCase @Inject constructor(
    private val repository: CategoryRepository
) {
    operator fun invoke(bookId: Long, kind: CategoryKind): Flow<List<Category>> =
        repository.observeByBook(bookId, kind)
}

class AddCategoryUseCase @Inject constructor(
    private val repository: CategoryRepository
) {
    suspend operator fun invoke(category: Category) = repository.insert(category)
}

class UpdateCategoryUseCase @Inject constructor(
    private val repository: CategoryRepository
) {
    suspend operator fun invoke(category: Category) = repository.update(category)
}

class DeleteCategoryUseCase @Inject constructor(
    private val repository: CategoryRepository
) {
    suspend operator fun invoke(categoryId: Long, migrateToCategoryId: Long = 0L) =
        repository.delete(categoryId, migrateToCategoryId)
}

class EnsureDefaultCategoriesUseCase @Inject constructor(
    private val repository: CategoryRepository
) {
    suspend operator fun invoke(bookId: Long) = repository.ensureDefaults(bookId)
}

class MatchCategoryUseCase @Inject constructor(
    private val repository: CategoryRepository
) {
    suspend operator fun invoke(bookId: Long, kind: CategoryKind, name: String) =
        repository.matchByName(bookId, kind, name)
}

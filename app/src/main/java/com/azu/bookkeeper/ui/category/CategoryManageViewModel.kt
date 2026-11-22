package com.azu.bookkeeper.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.category.AddCategoryUseCase
import com.azu.bookkeeper.domain.usecase.category.DeleteCategoryUseCase
import com.azu.bookkeeper.domain.usecase.category.EnsureDefaultCategoriesUseCase
import com.azu.bookkeeper.domain.usecase.category.UpdateCategoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryManageViewModel @Inject constructor(
    private val repository: CategoryRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository,
    private val addCategory: AddCategoryUseCase,
    private val updateCategory: UpdateCategoryUseCase,
    private val deleteCategory: DeleteCategoryUseCase,
    private val ensureDefaults: EnsureDefaultCategoriesUseCase
) : ViewModel() {

    data class UiState(
        val bookId: Long = 0L,
        val kind: CategoryKind = CategoryKind.EXPENSE,
        val categories: List<Category> = emptyList(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var categoriesJob: Job? = null

    init {
        viewModelScope.launch {
            val preferred = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId
                ?: bookRepository.insert(Book(name = "日常", isDefault = true)).getOrDefault(0L)
            ensureDefaults(preferred)
            _uiState.update { it.copy(bookId = preferred) }
            collectCategories()
        }
    }

    private fun collectCategories() {
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            repository.observeByBook(_uiState.value.bookId, _uiState.value.kind).collect { list ->
                _uiState.update { it.copy(categories = list) }
            }
        }
    }

    fun setKind(kind: CategoryKind) {
        _uiState.update { it.copy(kind = kind) }
        collectCategories()
    }

    fun add(name: String, parentId: Long = 0L) {
        if (name.isBlank()) return
        val state = _uiState.value
        viewModelScope.launch {
            when (val result = addCategory(
                Category(bookId = state.bookId, kind = state.kind, name = name.trim(), parentId = parentId)
            )) {
                is Result.Success -> _uiState.update { it.copy(message = "已添加分类") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun rename(category: Category, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = updateCategory(category.copy(name = name.trim()))) {
                is Result.Success -> _uiState.update { it.copy(message = "已重命名") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun delete(category: Category, migrateToCategoryId: Long) {
        viewModelScope.launch {
            when (val result = deleteCategory(category.categoryId, migrateToCategoryId)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        message = if (result.data) "已删除" else "该分类有账单绑定，请先选择迁移目标分类"
                    )
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}

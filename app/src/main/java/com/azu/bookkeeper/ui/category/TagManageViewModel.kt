package com.azu.bookkeeper.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Tag
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.repository.TagRepository
import com.azu.bookkeeper.domain.usecase.tag.DeleteTagUseCase
import com.azu.bookkeeper.domain.usecase.tag.UpdateTagUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagManageViewModel @Inject constructor(
    private val repository: TagRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository,
    private val updateTag: UpdateTagUseCase,
    private val deleteTag: DeleteTagUseCase
) : ViewModel() {

    data class UiState(
        val bookId: Long = 0L,
        val tags: List<Tag> = emptyList(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var tagsJob: Job? = null

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId ?: 0L
            _uiState.update { it.copy(bookId = bookId) }
            collectTags()
        }
    }

    private fun collectTags() {
        tagsJob?.cancel()
        tagsJob = viewModelScope.launch {
            repository.observeByBook(_uiState.value.bookId).collect { tags ->
                _uiState.update { it.copy(tags = tags) }
            }
        }
    }

    fun add(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.getOrCreate(_uiState.value.bookId, name.trim())) {
                is Result.Success -> _uiState.update { it.copy(message = "已添加标签") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun rename(tag: Tag, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = updateTag(tag.copy(name = name.trim()))) {
                is Result.Success -> _uiState.update { it.copy(message = "已重命名") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun delete(tagId: Long) {
        viewModelScope.launch {
            when (val result = deleteTag(tagId)) {
                is Result.Success -> _uiState.update { it.copy(message = "已删除标签（账单保留）") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}

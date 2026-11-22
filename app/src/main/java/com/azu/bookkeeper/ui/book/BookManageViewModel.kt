package com.azu.bookkeeper.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.BookStatus
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.usecase.book.AddBookUseCase
import com.azu.bookkeeper.domain.usecase.book.ArchiveBookUseCase
import com.azu.bookkeeper.domain.usecase.book.DeleteBookUseCase
import com.azu.bookkeeper.domain.usecase.book.SetDefaultBookUseCase
import com.azu.bookkeeper.domain.usecase.book.UpdateBookUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookManageViewModel @Inject constructor(
    private val repository: BookRepository,
    private val addBook: AddBookUseCase,
    private val updateBook: UpdateBookUseCase,
    private val archiveBook: ArchiveBookUseCase,
    private val setDefaultBook: SetDefaultBookUseCase,
    private val deleteBook: DeleteBookUseCase
) : ViewModel() {

    data class UiState(
        val books: List<Book> = emptyList(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { books -> _uiState.update { it.copy(books = books) } }
        }
    }

    fun add(name: String, icon: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            handle(addBook(Book(name = name.trim(), icon = icon)), "创建成功")
        }
    }

    fun rename(book: Book, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            handle(updateBook(book.copy(name = name.trim())), "已重命名")
        }
    }

    fun setDefault(bookId: Long) {
        viewModelScope.launch { handle(setDefaultBook(bookId), "已设为默认账本") }
    }

    fun toggleArchive(book: Book) {
        viewModelScope.launch {
            handle(
                archiveBook(book.bookId, book.status == BookStatus.ACTIVE),
                if (book.status == BookStatus.ACTIVE) "已归档" else "已恢复"
            )
        }
    }

    fun delete(bookId: Long) {
        viewModelScope.launch { handle(deleteBook(bookId), "已删除账本及其全部数据") }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun handle(result: Result<Any>, successMessage: String) {
        when (result) {
            is Result.Success -> _uiState.update { it.copy(message = successMessage) }
            is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
            Result.Loading -> Unit
        }
    }
}

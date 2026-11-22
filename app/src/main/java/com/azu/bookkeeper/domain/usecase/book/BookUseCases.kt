package com.azu.bookkeeper.domain.usecase.book

import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 账本相关用例集合（与架构文件清单一致，集中在一个文件）。 */

class ObserveBooksUseCase @Inject constructor(
    private val repository: BookRepository
) {
    operator fun invoke(): Flow<List<Book>> = repository.observeAll()
}

class AddBookUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(book: Book) = repository.insert(book)
}

class UpdateBookUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(book: Book) = repository.update(book)
}

class ArchiveBookUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: Long, archived: Boolean) =
        repository.archive(bookId, archived)
}

class SetDefaultBookUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: Long) = repository.setDefault(bookId)
}

class DeleteBookUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: Long) = repository.delete(bookId)
}

class CanAddBillUseCase @Inject constructor(
    private val repository: BookRepository
) {
    suspend operator fun invoke(bookId: Long) = repository.canAddBills(bookId)
}

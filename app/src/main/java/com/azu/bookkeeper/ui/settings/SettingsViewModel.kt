package com.azu.bookkeeper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val bookRepository: BookRepository,
    private val accountRepository: AccountRepository
) : ViewModel() {

    data class UiState(
        val books: List<Book> = emptyList(),
        val accounts: List<Account> = emptyList(),
        val defaultBookId: Long = 0L,
        val defaultAccountId: Long = 0L,
        val currency: String = "CNY",
        val dateFormat: String = "yyyy-MM-dd HH:mm",
        val weekStartDay: Int = 1,
        val appLockEnabled: Boolean = false,
        val appLockPin: String = "",
        val autoBackupEnabled: Boolean = false,
        val pinDraft: String = "",
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            bookRepository.observeAll().collect { books ->
                _uiState.update { it.copy(books = books) }
            }
        }
        viewModelScope.launch {
            settings.defaultBookId.collect { id -> _uiState.update { it.copy(defaultBookId = id) } }
        }
        viewModelScope.launch {
            settings.defaultAccountId.collect { id -> _uiState.update { it.copy(defaultAccountId = id) } }
        }
        viewModelScope.launch {
            settings.currency.collect { value -> _uiState.update { it.copy(currency = value) } }
        }
        viewModelScope.launch {
            settings.dateFormat.collect { value -> _uiState.update { it.copy(dateFormat = value) } }
        }
        viewModelScope.launch {
            settings.weekStartDay.collect { value -> _uiState.update { it.copy(weekStartDay = value) } }
        }
        viewModelScope.launch {
            settings.appLockEnabled.collect { value -> _uiState.update { it.copy(appLockEnabled = value) } }
        }
        viewModelScope.launch {
            settings.appLockPin.collect { value -> _uiState.update { it.copy(appLockPin = value) } }
        }
        viewModelScope.launch {
            settings.autoBackupEnabled.collect { value -> _uiState.update { it.copy(autoBackupEnabled = value) } }
        }
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            accountRepository.observeAll(bookId).collect { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
    }

    fun setDefaultBook(id: Long) = viewModelScope.launch { settings.setDefaultBookId(id) }

    fun setDefaultAccount(id: Long) = viewModelScope.launch { settings.setDefaultAccountId(id) }

    fun setCurrency(value: String) = viewModelScope.launch { settings.setCurrency(value) }

    fun setDateFormat(value: String) = viewModelScope.launch { settings.setDateFormat(value) }

    fun setWeekStart(day: Int) = viewModelScope.launch { settings.setWeekStartDay(day.coerceIn(1, 7)) }

    fun setAppLock(enabled: Boolean) = viewModelScope.launch { settings.setAppLockEnabled(enabled) }

    fun setPinDraft(value: String) = _uiState.update { it.copy(pinDraft = value) }

    fun savePin() {
        val pin = _uiState.value.pinDraft.trim()
        if (pin.length < 4) {
            _uiState.update { it.copy(message = "密码至少 4 位") }
            return
        }
        viewModelScope.launch {
            settings.setAppLockPin(pin)
            _uiState.update { it.copy(message = "应用锁密码已保存", pinDraft = "") }
        }
    }

    fun setAutoBackup(enabled: Boolean) = viewModelScope.launch { settings.setAutoBackupEnabled(enabled) }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}

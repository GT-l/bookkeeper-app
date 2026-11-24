package com.azu.bookkeeper.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.account.CalibrateBalanceUseCase
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
class AccountListViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository,
    private val calibrate: CalibrateBalanceUseCase
) : ViewModel() {

    data class UiState(
        val bookId: Long = 0L,
        val accounts: List<Account> = emptyList(),
        val totalBalanceFen: Long = 0L,
        val message: String? = null
    ) {
        /** 信用卡负数按负债展示；净资产 = 各账户余额之和。 */
        val netAssetText: String get() = Money.format(totalBalanceFen)
        val liabilityText: String get() = Money.format(accounts.filter { it.type == AccountType.CREDIT_CARD && it.balanceFen < 0L }.sumOf { it.balanceFen })
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId ?: 0L
            _uiState.update { it.copy(bookId = bookId) }
            if (bookId > 0L) {
                observeJob?.cancel()
                observeJob = viewModelScope.launch {
                    repository.observeTotalBalance(bookId).collect { total ->
                        _uiState.update { it.copy(totalBalanceFen = total) }
                    }
                }
                repository.observeAll(bookId).collect { accounts ->
                    _uiState.update { it.copy(accounts = accounts) }
                }
            }
        }
    }

    /** 余额校准：生成 ADJUSTMENT 调整账单走事务入口，绝不直接写 Account 表。 */
    fun calibrate(accountId: Long, actualText: String) {
        val actualFen = Money.parse(actualText)
        if (actualFen == null) {
            _uiState.update { it.copy(message = "余额格式不正确") }
            return
        }
        viewModelScope.launch {
            when (val result = calibrate(accountId, actualFen)) {
                is Result.Success -> _uiState.update { it.copy(message = "已校准并生成调整账单") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun delete(accountId: Long) {
        viewModelScope.launch {
            when (val result = repository.delete(accountId)) {
                is Result.Success -> _uiState.update { it.copy(message = "已删除账户") }
                is Result.Error -> _uiState.update { it.copy(message = "删除失败：${result.error.userMessage}（请先迁移该账户的账单）") }
                Result.Loading -> Unit
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}

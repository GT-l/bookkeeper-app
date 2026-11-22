package com.azu.bookkeeper.ui.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.map
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账户元数据编辑；当前余额只能由校准账单变更，本页不写 balanceFen。 */
@HiltViewModel
class AccountEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AccountRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val accountId: Long = 0L,
        val bookId: Long = 0L,
        val name: String = "",
        val type: AccountType = AccountType.CUSTOM,
        val initialBalanceText: String = "0.00",
        val currentBalanceFen: Long = 0L,
        val icon: String = "",
        val remark: String = "",
        val enabled: Boolean = true,
        val message: String? = null,
        val saved: Boolean = false
    )

    private val accountId: Long = savedStateHandle.get<Long>(Screen.ARG_ACCOUNT_ID) ?: 0L
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId ?: 0L
            val existing = if (accountId > 0L) repository.findById(accountId).getOrNull() else null
            _uiState.update { state ->
                existing?.let { account ->
                    state.copy(
                        accountId = account.accountId,
                        bookId = account.bookId,
                        name = account.name,
                        type = account.type,
                        initialBalanceText = Money.format(account.initialBalanceFen, withSymbol = false),
                        currentBalanceFen = account.balanceFen,
                        icon = account.icon,
                        remark = account.remark,
                        enabled = account.enabled
                    )
                } ?: state.copy(bookId = bookId)
            }
        }
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value) }

    fun setType(value: AccountType) = _uiState.update { it.copy(type = value) }

    fun setInitialBalance(value: String) = _uiState.update { it.copy(initialBalanceText = value) }

    fun setIcon(value: String) = _uiState.update { it.copy(icon = value) }

    fun setRemark(value: String) = _uiState.update { it.copy(remark = value) }

    fun setEnabled(value: Boolean) = _uiState.update { it.copy(enabled = value) }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(message = "请输入账户名称") }
            return
        }
        val initialFen = Money.parse(state.initialBalanceText) ?: 0L
        val account = Account(
            accountId = state.accountId,
            bookId = state.bookId,
            name = state.name.trim(),
            type = state.type,
            initialBalanceFen = initialFen,
            balanceFen = if (state.accountId > 0L) state.currentBalanceFen else initialFen,
            icon = state.icon,
            remark = state.remark,
            enabled = state.enabled
        )
        viewModelScope.launch {
            val result = if (state.accountId > 0L) {
                repository.update(account).map { Unit }
            } else {
                repository.insert(account).map { Unit }
            }
            when (result) {
                is Result.Success -> {
                    _uiState.update { it.copy(saved = true) }
                    onSaved()
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }
}

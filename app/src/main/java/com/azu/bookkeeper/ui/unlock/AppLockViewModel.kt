package com.azu.bookkeeper.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
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
class AppLockViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val locked: Boolean = true,
        val pinInput: String = "",
        val configuredPin: String = "",
        val message: String? = null,
        val unlocked: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = settings.appLockEnabled.first()
            val lastUnlock = settings.lastUnlockAt.first()
            val pin = settings.appLockPin.first()
            val stillUnlocked = lastUnlock > 0L &&
                DateTimeUtils.nowMillis() - lastUnlock <= LOCK_TIMEOUT_MS
            _uiState.update {
                it.copy(
                    locked = enabled && !stillUnlocked,
                    unlocked = !enabled || stillUnlocked,
                    configuredPin = pin
                )
            }
        }
    }

    fun setPinInput(value: String) = _uiState.update { it.copy(pinInput = value) }

    fun submitPin() {
        val state = _uiState.value
        if (state.configuredPin.isBlank()) {
            _uiState.update { it.copy(message = "请先在设置中配置数字密码") }
            return
        }
        if (state.pinInput == state.configuredPin) {
            unlock()
        } else {
            _uiState.update { it.copy(message = "密码错误", pinInput = "") }
        }
    }

    fun unlockByBiometric() = unlock()

    private fun unlock() {
        viewModelScope.launch {
            settings.setLastUnlockAt(DateTimeUtils.nowMillis())
            _uiState.update { it.copy(locked = false, unlocked = true, message = null) }
        }
    }

    private companion object {
        const val LOCK_TIMEOUT_MS = 5L * 60L * 1000L
    }
}

package com.azu.bookkeeper.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.automation.service.AutoCaptureForegroundService
import com.azu.bookkeeper.data.local.datastore.NotificationDisplayPrefs
import com.azu.bookkeeper.data.local.datastore.NotificationDisplaySettings
import com.azu.bookkeeper.data.local.datastore.NotificationDisplayStyles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 常驻通知显示项与样式的设置状态。每次改动都立刻回写通知（服务在跑时）。 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displaySettings: NotificationDisplaySettings
) : ViewModel() {

    data class UiState(
        val prefs: NotificationDisplayPrefs = NotificationDisplayPrefs(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            displaySettings.settings.collect { prefs ->
                _uiState.update { it.copy(prefs = prefs) }
            }
        }
    }

    private fun apply(transform: (NotificationDisplayPrefs) -> NotificationDisplayPrefs) =
        viewModelScope.launch {
            displaySettings.update(transform)
            // 只刷新已存在的常驻通知，不启动服务（避免失败计数影响自动化开关）
            AutoCaptureForegroundService.refreshNow(context)
        }

    fun setStyle(style: Int) {
        if (style !in NotificationDisplayStyles.ALL) return
        apply { it.copy(style = style) }
    }

    fun setToday(value: Boolean) = apply { it.copy(showToday = value) }
    fun setMonth(value: Boolean) = apply { it.copy(showMonth = value) }
    fun setBudget(value: Boolean) = apply { it.copy(showBudget = value) }
    fun setLastBill(value: Boolean) = apply { it.copy(showLastBill = value) }
    fun setCount(value: Boolean) = apply { it.copy(showCount = value) }
    fun setQuickAdd(value: Boolean) = apply { it.copy(showQuickAdd = value) }
    fun setBallToggle(value: Boolean) = apply { it.copy(showBallToggle = value) }
}

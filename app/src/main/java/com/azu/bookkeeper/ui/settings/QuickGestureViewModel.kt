package com.azu.bookkeeper.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 系统手势设置：先探测本机硬件能力，再让用户自己选一个触发「记一笔」的手势。 */
@HiltViewModel
class QuickGestureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automation: AutomationSettingsDataStore,
    private val permissionGuide: PermissionGuide
) : ViewModel() {

    data class Option(
        val code: Int,
        val label: String,
        val description: String,
        val supported: Boolean
    )

    data class UiState(
        val options: List<Option> = emptyList(),
        val selected: Int = QuickGestureCodes.OFF,
        val accessibilityGranted: Boolean = false,
        val message: String? = null
    ) {
        val needsAccessibility: Boolean
            get() = selected != QuickGestureCodes.OFF && !accessibilityGranted
    }

    private val _uiState = MutableStateFlow(UiState(options = buildOptions()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(selected = automation.quickGesture.first()) }
        }
    }

    private fun buildOptions(): List<Option> {
        val pm = context.packageManager
        val fingerprint = runCatching {
            pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }.getOrDefault(false)
        val accelerometer = runCatching {
            pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        }.getOrDefault(true)
        return listOf(
            Option(
                QuickGestureCodes.OFF, "不启用",
                "只用桌面图标 / 小组件 / 常驻通知记一笔", true
            ),
            Option(
                QuickGestureCodes.FINGER_UP, "指纹上滑",
                "在指纹传感器上向上滑", fingerprint
            ),
            Option(
                QuickGestureCodes.FINGER_DOWN, "指纹下滑",
                "在指纹传感器上向下滑", fingerprint
            ),
            Option(
                QuickGestureCodes.FINGER_LEFT, "指纹左滑",
                "在指纹传感器上向左滑", fingerprint
            ),
            Option(
                QuickGestureCodes.FINGER_RIGHT, "指纹右滑",
                "在指纹传感器上向右滑", fingerprint
            ),
            Option(
                QuickGestureCodes.SHAKE, "摇一摇",
                "摇动手机立刻打开记一笔", accelerometer
            ),
            Option(
                QuickGestureCodes.ACCESSIBILITY_BUTTON, "无障碍按钮",
                "点系统导航栏上的无障碍按钮", true
            )
        )
    }

    fun refreshPermission() {
        _uiState.update {
            it.copy(
                accessibilityGranted = runCatching {
                    permissionGuide.isAccessibilityEnabled()
                }.getOrDefault(false)
            )
        }
    }

    fun select(code: Int) = viewModelScope.launch {
        val option = _uiState.value.options.firstOrNull { it.code == code }
        if (option == null || !option.supported) {
            _uiState.update { it.copy(message = "本机不支持这个手势") }
            return@launch
        }
        automation.setQuickGesture(code)
        refreshPermission()
        _uiState.update {
            it.copy(
                selected = code,
                message = if (code == QuickGestureCodes.OFF) {
                    "系统手势已关闭"
                } else {
                    "已选择「${QuickGestureCodes.label(code)}」"
                }
            )
        }
    }

    fun openAccessibilitySettings() {
        runCatching { context.startActivity(permissionGuide.accessibilitySettingsIntent()) }
            .onFailure {
                _uiState.update { it.copy(message = "打不开系统设置，请手动进入无障碍设置") }
            }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}

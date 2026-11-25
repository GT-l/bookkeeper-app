package com.azu.bookkeeper.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.core.ShakeActions
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.ui.quick.ShakeQuickAddService
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
        val shakeAction: Int = ShakeActions.OPEN_FORM,
        val accessibilityGranted: Boolean = false,
        val message: String? = null
    ) {
        /** 只有指纹手势依赖读屏服务；摇一摇走独立前台服务，不需要无障碍。 */
        val needsAccessibility: Boolean
            get() = QuickGestureCodes.isFingerprint(selected) && !accessibilityGranted
    }

    private val _uiState = MutableStateFlow(UiState(options = buildOptions()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            automation.shakeAction.collect { action ->
                _uiState.update { it.copy(shakeAction = action) }
            }
        }
        viewModelScope.launch {
            val stored = automation.quickGesture.first()
            val selected = if (stored in QuickGestureCodes.ALL) {
                stored
            } else {
                // 旧版「无障碍按钮」选项已移除，迁移为关闭
                automation.setQuickGesture(QuickGestureCodes.OFF)
                QuickGestureCodes.OFF
            }
            _uiState.update { it.copy(selected = selected) }
            if (selected == QuickGestureCodes.SHAKE) ShakeQuickAddService.start(context)
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
                "摇动手机立刻打开记一笔（独立运行，无需无障碍）", accelerometer
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
        // 摇一摇由独立前台服务负责；其他手势（含关闭）都停掉它
        if (code == QuickGestureCodes.SHAKE) {
            ShakeQuickAddService.start(context)
        } else {
            ShakeQuickAddService.stop(context)
        }
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

    /** 摇一摇命中后是打开表单还是直接识屏，改动即时生效。 */
    fun setShakeAction(action: Int) = viewModelScope.launch {
        if (action !in setOf(ShakeActions.OPEN_FORM, ShakeActions.SCREEN_CAPTURE)) return@launch
        automation.setShakeAction(action)
        ShakeQuickAddService.refresh(context)
        _uiState.update {
            it.copy(
                shakeAction = action,
                message = "摇一摇动作：${ShakeActions.label(action)}"
            )
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}

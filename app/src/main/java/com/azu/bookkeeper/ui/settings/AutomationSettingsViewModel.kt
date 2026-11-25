package com.azu.bookkeeper.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.automation.permission.BatteryOptimizationHelper
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.receiver.SmsBroadcastReceiver
import com.azu.bookkeeper.automation.service.AutoCaptureForegroundService
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import com.azu.bookkeeper.ui.quick.QuickBallService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 自动化设置页状态与动作（含能力说明固定文案与短信隐私开关）。 */
@HiltViewModel
class AutomationSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automation: AutomationSettingsDataStore,
    private val permissionGuide: PermissionGuide,
    private val batteryHelper: BatteryOptimizationHelper,
    private val screenCapture: ScreenCaptureManager,
    private val encryptedFiles: EncryptedFileDataSource
) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val serviceRunning: Boolean = false,
        val accessibilityGranted: Boolean = false,
        val notificationListenerGranted: Boolean = false,
        val overlayGranted: Boolean = false,
        val notificationGranted: Boolean = false,
        val smsPermissionGranted: Boolean = false,
        val smsEnabled: Boolean = false,
        val screenCaptureEnabled: Boolean = false,
        val ocrThreshold: Float = Constants.DEFAULT_OCR_THRESHOLD,
        val verifyMode: Int = 0,
        val dedupEnabled: Boolean = true,
        val smsDedupToleranceSec: Long = Constants.SMS_DEDUP_TOLERANCE_SEC,
        /** T7：悬浮球默认关闭（Q-DY-4），避免强打扰权限默认开启。 */
        val floatingBallEnabled: Boolean = false,
        val batteryWhitelisted: Boolean = false,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            automation.enabled.collect { v -> _uiState.update { it.copy(enabled = v) } }
        }
        viewModelScope.launch {
            automation.serviceRunning.collect { v -> _uiState.update { it.copy(serviceRunning = v) } }
        }
        viewModelScope.launch {
            automation.smsEnabled.collect { v -> _uiState.update { it.copy(smsEnabled = v) } }
        }
        viewModelScope.launch {
            automation.screenCaptureEnabled.collect { v ->
                _uiState.update { it.copy(screenCaptureEnabled = v) }
            }
        }
        viewModelScope.launch {
            automation.ocrThreshold.collect { v -> _uiState.update { it.copy(ocrThreshold = v) } }
        }
        viewModelScope.launch {
            automation.verifyMode.collect { v -> _uiState.update { it.copy(verifyMode = v) } }
        }
        viewModelScope.launch {
            automation.dedupEnabled.collect { v -> _uiState.update { it.copy(dedupEnabled = v) } }
        }
        viewModelScope.launch {
            automation.smsDedupToleranceSec.collect { v ->
                _uiState.update { it.copy(smsDedupToleranceSec = v) }
            }
        }
        viewModelScope.launch {
            automation.floatingBallEnabled.collect { v ->
                _uiState.update { it.copy(floatingBallEnabled = v) }
            }
        }
        refreshPermissions()
    }

    /** 把系统权限实时状态叠加到 DataStore 配置态上。 */
    fun refreshPermissions() {
        _uiState.update { applyPermissionsState(it) }
    }

    private fun applyPermissionsState(state: UiState): UiState = state.copy(
        accessibilityGranted = permissionGuide.isAccessibilityEnabled(),
        notificationListenerGranted = permissionGuide.isNotificationListenerEnabled(),
        overlayGranted = permissionGuide.canDrawOverlays(),
        notificationGranted = permissionGuide.hasNotificationPermission(),
        smsPermissionGranted = permissionGuide.hasSmsPermission(),
        batteryWhitelisted = batteryHelper.isIgnoringBatteryOptimizations()
    )

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        automation.setEnabled(enabled)
        if (enabled) {
            AutoCaptureForegroundService.start(context)
            if (automation.serviceRunning.first().not()) {
                _uiState.update { it.copy(message = "服务可能被系统限制，请检查电池优化设置") }
            }
        } else {
            screenCapture.release()
            AutoCaptureForegroundService.stop(context)
        }
    }

    fun setOcrThreshold(value: Float) = viewModelScope.launch { automation.setOcrThreshold(value) }

    fun setVerifyMode(mode: Int) = viewModelScope.launch { automation.setVerifyMode(mode.coerceIn(0, 3)) }

    fun setDedupEnabled(enabled: Boolean) = viewModelScope.launch { automation.setDedupEnabled(enabled) }

    fun setSmsDedupTolerance(seconds: Long) = viewModelScope.launch {
        automation.setSmsDedupToleranceSec(seconds)
    }

    /**
     * 短信识别开关（QA F-13）：关闭时禁用静态 receiver；开启需 READ_SMS + RECEIVE_SMS
     * 两项运行时权限同时授予（hasSmsPermission 已做双校验），否则只提示、不写开关。
     */
    fun setSmsEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled && !permissionGuide.hasSmsPermission()) {
            _uiState.update { it.copy(message = "请先授予「读取短信 / 接收短信」两项权限") }
            return@launch
        }
        automation.setSmsEnabled(enabled)
        SmsBroadcastReceiver.setEnabled(context, enabled)
        refreshPermissions()
    }

    /**
     * 运行时授权结果回调（QA F-13）：由设置页 RequestMultiplePermissions 回填。
     * 全部授予 → 打开短信识别开关并启用 receiver；否则保持关闭并给出「其他识别方式继续工作」的降级说明。
     */
    fun onSmsPermissionResult(allGranted: Boolean) = viewModelScope.launch {
        if (allGranted) {
            automation.setSmsEnabled(true)
            SmsBroadcastReceiver.setEnabled(context, true)
        } else {
            _uiState.update {
                it.copy(message = "短信权限被拒绝，短信识别保持关闭；读屏 / 通知 / 截屏识别继续工作")
            }
        }
        refreshPermissions()
    }

    /** T7 悬浮球：复用已有悬浮窗权限，未授权时引导开启且不写开关状态。 */
    /**
     * 悬浮球开关。
     *
     * 关键修复：先落库「用户意图」，再处理权限——否则用户去系统设置授权后返回，
     * 开关仍是关的、也没人再拉起服务，表现为「开了悬浮球却永远不出现」。
     * 授权返回后的自动恢复见 [retryStartFloatingBallIfNeeded]（页面 ON_RESUME 调用）。
     */
    fun setFloatingBallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            automation.setFloatingBallEnabled(enabled)
            if (!enabled) {
                QuickBallService.stop(context)
                _uiState.update { it.copy(floatingBallEnabled = false, message = "悬浮球已关闭") }
                return@launch
            }
            if (!permissionGuide.canDrawOverlays()) {
                _uiState.update {
                    it.copy(
                        floatingBallEnabled = true,
                        message = "已记录开启：请打开「显示在其他应用上层/悬浮窗」权限，返回本页后悬浮球会自动出现"
                    )
                }
                openOverlaySettings()
                return@launch
            }
            QuickBallService.start(context)
            _uiState.update { it.copy(floatingBallEnabled = true, message = "悬浮球已开启，可拖动到顺手的位置") }
        }
    }

    /**
     * 页面回到前台时调用：开关已开 + 权限已给 + 球没在跑 → 自动补拉起。
     * 覆盖「去系统设置授权后返回」和「进程被杀后重新打开 App」两种场景。
     */
    fun retryStartFloatingBallIfNeeded() {
        viewModelScope.launch {
            val enabled = automation.floatingBallEnabled.first()
            if (!enabled) return@launch
            val overlayOk = permissionGuide.canDrawOverlays()
            if (!overlayOk) return@launch
            if (QuickBallService.isRunning) return@launch
            QuickBallService.start(context)
            _uiState.update {
                it.copy(floatingBallEnabled = true, overlayGranted = true)
            }
        }
    }

    /** 截屏备选：开启时拉起 MediaProjection 授权弹窗（由 Activity 的 launcher 消费）。 */
    fun setScreenCaptureEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled && !screenCapture.hasActiveSession()) {
            _uiState.update { it.copy(message = "请在弹出的系统对话框中允许屏幕捕获") }
        }
        automation.setScreenCaptureEnabled(enabled)
        if (!enabled) screenCapture.release()
    }

    fun createCaptureIntent(): Intent = screenCapture.createCaptureIntent()

    fun startScreenCaptureSession(resultCode: Int, data: Intent) {
        val ok = screenCapture.startSession(resultCode, data) {
            viewModelScope.launch { automation.setScreenCaptureEnabled(false) }
        }
        if (!ok) _uiState.update { it.copy(message = "截屏授权失败，已降级为读屏/通知识别") }
    }

    fun openAccessibilitySettings() = openIntent(permissionGuide.accessibilitySettingsIntent())
    fun openNotificationListenerSettings() = openIntent(permissionGuide.notificationListenerSettingsIntent())
    fun openOverlaySettings() = openIntent(permissionGuide.overlaySettingsIntent())
    fun openAppDetails() = openIntent(permissionGuide.appDetailsIntent())

    fun requestBatteryWhitelist() {
        batteryHelper.requestIgnoreOptimizationsIntent()?.let { openIntent(it) }
            ?: batteryHelper.manufacturerAutoStartIntent()?.let { openIntent(it) }
            ?: _uiState.update { it.copy(message = "当前机型无自启设置入口，请手动允许后台运行") }
    }

    fun clearCaptures() = viewModelScope.launch {
        val count = encryptedFiles.clearAllCaptures()
        _uiState.update { it.copy(message = "已清除 $count 张捕获截图") }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun openIntent(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { _uiState.update { it.copy(message = "无法打开系统设置，请手动进入") } }
    }
}

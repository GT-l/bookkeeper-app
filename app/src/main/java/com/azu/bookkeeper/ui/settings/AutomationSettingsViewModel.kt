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
import com.azu.bookkeeper.core.SafeMode
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
import kotlinx.coroutines.withTimeoutOrNull
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
        /** 读屏服务是否真的连上过（系统开关在部分 ROM 上显示不准）。 */
        val accessibilityServiceConnected: Boolean = false,
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
        /** 安全模式：上次启动崩溃过，悬浮层服务暂不自动启动。 */
        val safeModeActive: Boolean = false,
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
        // 安全模式是纯 SharedPreferences 标记（不依赖 DataStore/DB），进页面就先读一次，
        // 否则「上次崩过」的提示要等用户切出去再回来才出现。
        _uiState.update {
            it.copy(safeModeActive = runCatching { SafeMode.isActive(context) }.getOrDefault(false))
        }
    }

    /** 把系统权限实时状态叠加到 DataStore 配置态上。 */
    fun refreshPermissions() {
        _uiState.update { applyPermissionsState(it) }
    }

    private fun applyPermissionsState(state: UiState): UiState = state.copy(
        accessibilityGranted = permissionGuide.isAccessibilityEnabled(),
        accessibilityServiceConnected = permissionGuide.accessibilityServiceEverConnected(),
        notificationListenerGranted = permissionGuide.isNotificationListenerEnabled(),
        overlayGranted = permissionGuide.canDrawOverlays(),
        notificationGranted = permissionGuide.hasNotificationPermission(),
        smsPermissionGranted = permissionGuide.hasSmsPermission(),
        batteryWhitelisted = batteryHelper.isIgnoringBatteryOptimizations()
    )

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        automation.setEnabled(enabled)
        if (!enabled) {
            screenCapture.release()
            AutoCaptureForegroundService.stop(context)
            _uiState.update { it.copy(message = "已关闭自动记账与常驻通知") }
            return@launch
        }
        val requested = AutoCaptureForegroundService.start(context)
        // start() 只代表系统受理了启动请求；服务真正进入前台后才会异步写 serviceRunning。
        // 之前在这里立刻读一次 serviceRunning.first()，读到的必然是旧值 false，
        // 于是刚刚开启成功也会误报「服务可能被系统限制」——改为带超时地等待写入。
        val running = withTimeoutOrNull(SERVICE_START_TIMEOUT_MS) {
            automation.serviceRunning.first { it }
        } != null
        val notificationsGranted = permissionGuide.hasNotificationPermission()
        val message = when {
            !requested -> "无法启动后台服务，请检查电池优化 / 自启动设置后重试"
            !running -> "服务可能被系统限制，请检查电池优化设置"
            notificationsGranted -> "已开启：通知栏已出现常驻通知，点击可回到本页"
            // 通知权限与总开关是两件事：没有权限时自动记账仍然工作，只是通知栏看不到常驻通知。
            else -> "已开启自动记账；未授予通知权限，通知栏不会显示常驻通知"
        }
        _uiState.update { it.copy(message = message) }
        refreshPermissions()
    }

    /**
     * Android 13+ POST_NOTIFICATIONS 运行时申请结果。
     *
     * @param granted 是否授予。
     * @param enableRequested 本次申请是否由「启用自动记账」总开关发起（是则授权后直接开启）。
     *
     * 关键修复：Android 13+ 在未授权期间投递的前台通知会被系统丢弃且**不会补发**，
     * 所以权限补齐后必须让已在运行的服务重新 startForeground 一次，通知才会出现在通知栏。
     */
    fun onNotificationPermissionResult(granted: Boolean, enableRequested: Boolean) =
        viewModelScope.launch {
            refreshPermissions()
            if (!granted) {
                _uiState.update {
                    it.copy(
                        message = "通知权限被拒绝：自动记账仍可运行，但通知栏不会显示常驻通知；" +
                            "可在应用详情里手动开启通知权限"
                    )
                }
                // 用户明确点了总开关：权限被拒也要把开关打开，识别能力仍然可用。
                if (enableRequested) setEnabled(true)
                return@launch
            }
            if (enableRequested) {
                setEnabled(true)
                return@launch
            }
            if (!automation.enabled.first()) {
                _uiState.update {
                    it.copy(message = "通知权限已授予：打开上方「启用自动记账」总开关后常驻通知才会出现")
                }
                return@launch
            }
            // 开关已开但此前没有通知权限：服务可能在跑但通知被系统丢弃，补发一次。
            val refreshed = AutoCaptureForegroundService.ensureRunning(context, automation)
            _uiState.update {
                it.copy(
                    message = if (refreshed) {
                        "通知权限已授予，常驻通知已出现在通知栏"
                    } else {
                        "通知权限已授予；自动记账未运行，打开总开关后会出现常驻通知"
                    }
                )
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
            // 用户显式开启 = 认可风险，退出安全模式；若再崩，CrashGuard 会重新进入安全模式
            runCatching { SafeMode.clear(context) }
            _uiState.update { it.copy(safeModeActive = false) }
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
     * 页面回到前台时调用：开关已开 + 权限已给 + 组件没在跑 → 自动补拉起。
     * 覆盖「去系统设置授权后返回」和「进程被杀后重新打开 App」两种场景。
     */
    fun retryStartFloatingBallIfNeeded() {
        viewModelScope.launch {
            // 安全模式先判定：即使悬浮窗权限没给也要把提示挂出来（安全模式期间不自动拉起）
            val safeMode = runCatching { SafeMode.isActive(context) }.getOrDefault(false)
            _uiState.update { it.copy(safeModeActive = safeMode) }
            if (safeMode) return@launch
            val overlayOk = permissionGuide.canDrawOverlays()
            if (!overlayOk) return@launch
            if (automation.floatingBallEnabled.first() && !QuickBallService.isRunning) {
                QuickBallService.start(context)
                _uiState.update { it.copy(floatingBallEnabled = true, overlayGranted = true) }
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

    // 暴露 Intent 给 Compose 的 ActivityResultLauncher（留在当前 task，返回不回桌面）
    fun accessibilitySettingsIntent() = permissionGuide.accessibilitySettingsIntent()
    fun notificationListenerSettingsIntent() = permissionGuide.notificationListenerSettingsIntent()
    fun overlaySettingsIntent() = permissionGuide.overlaySettingsIntent()
    fun appDetailsIntent() = permissionGuide.appDetailsIntent()

    // 保留旧入口供非 Compose 场景使用
    fun openAccessibilitySettings() = openIntent(accessibilitySettingsIntent())
    fun openNotificationListenerSettings() = openIntent(notificationListenerSettingsIntent())
    fun openOverlaySettings() = openIntent(overlaySettingsIntent())
    fun openAppDetails() = openIntent(appDetailsIntent())

    fun batteryWhitelistIntent() =
        batteryHelper.requestIgnoreOptimizationsIntent() ?: batteryHelper.manufacturerAutoStartIntent()

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

    companion object {
        /** 等待前台服务把 running=true 写回 DataStore 的上限；超时即认为启动失败（与 FGS 失败退避互相兜底）。 */
        private const val SERVICE_START_TIMEOUT_MS = 2_000L
    }
}

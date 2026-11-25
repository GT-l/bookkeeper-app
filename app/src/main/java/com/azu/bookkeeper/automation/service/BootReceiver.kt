package com.azu.bookkeeper.automation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.ui.quick.QuickBallService
import com.azu.bookkeeper.ui.quick.ThreeFingerGestureService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 开机 / 升级恢复（主架构 §5.4）：
 * 收到 BOOT_COMPLETED / MY_PACKAGE_REPLACED 且自动化开关开启时拉起前台服务。
 *
 * 限制（如实告知）：Android 3.1 起被系统「强行停止」(force-stop) 后，
 * 静态广播不再触发，本恢复方式无效；需用户手动打开一次 App。设置页固定文案说明。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var automationSettings: AutomationSettingsDataStore
    @Inject lateinit var permissionGuide: PermissionGuide

    private val scope = CoroutineScope(SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        scope.launch {
            try {
                if (!automationSettings.enabled.first()) return@launch
                // 开机恢复同样尊重失败退避，避免开机后立刻陷入反复拉起。
                if (!automationSettings.canStartService(System.currentTimeMillis())) return@launch
                if (automationSettings.serviceRunning.first()) return@launch
                val appContext = context.applicationContext
                if (!AutoCaptureForegroundService.start(appContext)) {
                    AutoCaptureForegroundService.handleStartFailure(appContext, automationSettings)
                }
            } finally {
                pending.finish()
            }
        }

        // 安全模式下不恢复悬浮层：上次启动崩过，恢复会再次把用户拖进崩溃循环
        val safeMode = com.azu.bookkeeper.core.SafeMode.isActive(context.applicationContext)
        // 悬浮球恢复与自动化总开关解耦：用户只要开了悬浮球，重启手机后也应恢复
        scope.launch {
            runCatching {
                if (safeMode) return@launch
                if (!automationSettings.floatingBallEnabled.first()) return@launch
                if (!permissionGuide.canDrawOverlays()) return@launch
                if (QuickBallService.isRunning) return@launch
                QuickBallService.start(context.applicationContext)
            }.onFailure { Timber.w(it, "restore quick ball on boot failed") }
        }

        // 三指手势条同样开机恢复
        scope.launch {
            runCatching {
                if (safeMode) return@launch
                if (!automationSettings.threeFingerEnabled.first()) return@launch
                if (!permissionGuide.canDrawOverlays()) return@launch
                if (ThreeFingerGestureService.isRunning) return@launch
                ThreeFingerGestureService.start(context.applicationContext)
            }.onFailure { Timber.w(it, "restore three-finger gesture on boot failed") }
        }
    }
}

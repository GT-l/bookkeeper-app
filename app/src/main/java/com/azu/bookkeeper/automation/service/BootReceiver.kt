package com.azu.bookkeeper.automation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.core.SafeMode
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.ui.quick.QuickBallService
import com.azu.bookkeeper.ui.quick.ShakeQuickAddService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 开机 / 升级恢复（主架构 §5.4）：
 * 收到 BOOT_COMPLETED / MY_PACKAGE_REPLACED 且自动化开关开启时拉起前台服务。
 *
 * 限制（如实告知）：Android 3.1 起被系统「强行停止」(force-stop) 后，
 * 静态广播不再触发，本恢复方式无效；需用户手动打开一次 App。设置页固定文案说明。
 *
 * 稳定性：不再使用 @AndroidEntryPoint 字段注入（注入失败会直接崩在广播里），
 * 改为 EntryPoint 按需取 + 整段 runCatching，开机阶段任何异常都不影响系统启动流程。
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        runCatching { handle(context, intent) }.onFailure { t ->
            Timber.w(t, "boot receiver failed (ignored)")
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, BootDepsEntryPoint::class.java)
        }.getOrNull() ?: return
        val automationSettings = runCatching { entry.automationSettings() }.getOrNull() ?: return
        val permissionGuide = runCatching { entry.permissionGuide() }.getOrNull()

        val pending = goAsync()
        scope.launch {
            try {
                if (!automationSettings.enabled.first()) return@launch
                // v1.3.2：无障碍守护已并入自动记账前台服务，不再单独启动（只保留一条常驻通知）
                // 开机恢复同样尊重失败退避，避免开机后立刻陷入反复拉起。
                if (!automationSettings.canStartService(System.currentTimeMillis())) return@launch
                if (automationSettings.serviceRunning.first()) return@launch
                val appContext = context.applicationContext
                if (!AutoCaptureForegroundService.start(appContext)) {
                    AutoCaptureForegroundService.handleStartFailure(appContext, automationSettings)
                }
            } catch (t: Throwable) {
                Timber.w(t, "boot restore automation failed")
            } finally {
                pending.finish()
            }
        }

        // 安全模式下不恢复悬浮层：上次启动崩过，恢复会再次把用户拖进崩溃循环
        val safeMode = runCatching { SafeMode.isActive(context.applicationContext) }.getOrDefault(false)
        // 悬浮球恢复与自动化总开关解耦：用户只要开了悬浮球，重启手机后也应恢复
        scope.launch {
            runCatching {
                if (safeMode) return@launch
                if (!automationSettings.floatingBallEnabled.first()) return@launch
                if (permissionGuide?.canDrawOverlays() != true) return@launch
                if (QuickBallService.isRunning) return@launch
                QuickBallService.start(context.applicationContext)
            }.onFailure { Timber.w(it, "restore quick ball on boot failed") }
        }

        // 摇一摇独立前台服务：开机时按设置恢复（与悬浮层安全模式无关）
        scope.launch {
            runCatching {
                if (automationSettings.quickGesture.first() == QuickGestureCodes.SHAKE) {
                    ShakeQuickAddService.start(context.applicationContext)
                }
            }.onFailure { Timber.w(it, "restore shake quick add on boot failed") }
        }
    }
}

/** 开机恢复需要的惰性依赖（避免广播接收器在注入阶段崩溃）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootDepsEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
    fun permissionGuide(): PermissionGuide
}

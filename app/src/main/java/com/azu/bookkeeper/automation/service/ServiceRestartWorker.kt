package com.azu.bookkeeper.automation.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 周期探活（主架构 §5.4）：每 15min 由 WorkManager 检查自动化开关与运行状态，
 * 开关开启但服务未运行时尝试拉起。Doze 下延迟，非实时（如实标注）。
 */
@HiltWorker
class ServiceRestartWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val automationSettings: AutomationSettingsDataStore
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): WorkResult {
        // 探活是纯后台动作：任何异常都返回 success，绝不让 WorkManager 把失败冒成崩溃
        return runCatching { doWorkSafely() }.getOrDefault(WorkResult.success())
    }

    private suspend fun doWorkSafely(): WorkResult {
        if (!automationSettings.enabled.first()) return WorkResult.success()
        // 连续启动失败时尊重退避窗口；达上限后开关已被关闭，这里也不会再拉起。
        if (!automationSettings.canStartService(System.currentTimeMillis())) return WorkResult.success()
        if (automationSettings.serviceRunning.first()) return WorkResult.success()
        if (!AutoCaptureForegroundService.start(applicationContext)) {
            AutoCaptureForegroundService.handleStartFailure(applicationContext, automationSettings)
        }
        // 统一返回 success：探活本身失败不重试，避免系统在 Doze 下反复唤醒
        return WorkResult.success()
    }

    companion object {
        const val WORK_NAME = "automation_service_restart"
        val INTERVAL_MINUTES = Constants.SERVICE_RESTART_INTERVAL_MIN
    }
}

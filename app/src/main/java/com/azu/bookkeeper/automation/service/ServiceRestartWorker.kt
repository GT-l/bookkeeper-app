package com.azu.bookkeeper.automation.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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

    override suspend fun doWork(): androidx.work.Result {
        val enabled = automationSettings.enabled.first()
        val running = automationSettings.serviceRunning.first()
        if (enabled && !running) {
            AutoCaptureForegroundService.start(applicationContext)
        }
        // 统一返回 success：探活本身失败不重试，避免系统在 Doze 下反复唤醒
        return androidx.work.Result.success()
    }

    companion object {
        const val WORK_NAME = "automation_service_restart"
        val INTERVAL_MINUTES = Constants.SERVICE_RESTART_INTERVAL_MIN
    }
}

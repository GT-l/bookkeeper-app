package com.azu.bookkeeper

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.azu.bookkeeper.automation.classify.SeedRuleInitializer
import com.azu.bookkeeper.automation.service.ServiceRestartWorker
import com.azu.bookkeeper.worker.NotificationWorker
import com.azu.bookkeeper.worker.RecurringBillWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 集成 Hilt 依赖图，并把 Hilt 的 [HiltWorkerFactory] 交给 WorkManager，
 * 供 T3 的周期账单 Worker / T5 的服务探活 Worker 使用。
 */
@HiltAndroidApp
class BookkeeperApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var seedRuleInitializer: SeedRuleInitializer

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "recurring_bills",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecurringBillWorker>(1, TimeUnit.DAYS).build()
        )
        workManager.enqueueUniquePeriodicWork(
            "daily_reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NotificationWorker>(1, TimeUnit.DAYS).build()
        )
        // T5：自动化服务探活（Doze 下延迟，非实时）
        workManager.enqueueUniquePeriodicWork(
            ServiceRestartWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ServiceRestartWorker>(
                ServiceRestartWorker.INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
        )
        // T6：首次启动幂等注入抖音 / 通用种子规则（不覆盖用户改动）
        appScope.launch {
            runCatching { seedRuleInitializer.initialize() }
                .onFailure { Timber.w(it, "seed rules init failed") }
        }
    }
}

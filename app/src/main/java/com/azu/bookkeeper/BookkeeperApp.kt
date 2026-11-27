package com.azu.bookkeeper

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.azu.bookkeeper.BuildConfig
import com.azu.bookkeeper.automation.classify.SeedRuleInitializer
import com.azu.bookkeeper.automation.service.ServiceRestartWorker
import com.azu.bookkeeper.core.FirstRunSeeder
import com.azu.bookkeeper.core.LegacyCleanup
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

    @Inject
    lateinit var firstRunSeeder: FirstRunSeeder

    @Inject
    lateinit var legacyCleanup: LegacyCleanup

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 崩溃自愈必须最先执行：检测「上次启动即崩」的循环（如强杀导致 SQLCipher 库损坏），
        // 备份并重建数据库，保证用户永远能打开 App。内部已自兜底，不允许抛出。
        runCatching { com.azu.bookkeeper.core.CrashGuard.install(this) }
        // 全局崩溃栈落盘：任何 Java 崩溃都会写文件，下次进「识屏自检」页可直接复制
        runCatching { com.azu.bookkeeper.core.CrashLog.install(this) }
        // 崩溃探针：始终开启，记录「崩溃前最后执行到哪一步」（native 崩溃/被系统杀进程时唯一线索）
        runCatching { com.azu.bookkeeper.core.BootTrace.init(this) }
        // 识屏日志：读取上次开关状态（用户开启识屏后开始记录）
        runCatching { com.azu.bookkeeper.core.CaptureLog.init(this) }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 悬浮层服务运行在独立进程 :overlay，只为「崩了不拖死主进程」；该进程不需要
        // WorkManager / 种子数据 / 识别规则，更要避免两个进程同时打开 SQLCipher 库。
        // Hilt 组件已在 super.onCreate() 里建好，这里提前返回不影响 EntryPointAccessors。
        if (!com.azu.bookkeeper.core.CrashGuard.isMainProcess(this)) return
        // 无障碍守护已并入自动记账前台服务（每 15 秒检查），不再单独占一条常驻通知。
        // 截屏通道对账：上次调用没收尾（进程被杀）→ 自动熔断该通道，避免反复闪退
        runCatching { com.azu.bookkeeper.core.CaptureBreakerStore.reconcile(this) }
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
        // 首启种子数据（默认账本 + 默认分类 + 默认账户）与 T6 种子规则串行执行：
        // 种子规则依赖 defaultBookId 指向的账本，必须先落账本再注入规则。
        // 失败只记日志不崩溃：用户仍可手动在账本管理页建账本。
        appScope.launch {
            runCatching { legacyCleanup.runIfNeeded() }
                .onFailure { Timber.w(it, "legacy cleanup failed") }
            runCatching { firstRunSeeder.ensureSeeded() }
                .onFailure { Timber.w(it, "first run seed failed") }
            runCatching { seedRuleInitializer.initialize() }
                .onFailure { Timber.w(it, "seed rules init failed") }
        }
    }
}

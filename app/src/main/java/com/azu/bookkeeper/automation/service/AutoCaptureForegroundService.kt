package com.azu.bookkeeper.automation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.verify.VerificationNotifier
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.ui.navigation.AppEntryRouter
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/** 确保自动化通知渠道存在；服务前台通知与失败降级提示共用同一渠道。 */
private fun ensureAutoCaptureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(Constants.CHANNEL_AUTO_CAPTURE) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_AUTO_CAPTURE,
                context.getString(R.string.notification_channel_auto_capture),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}

/**
 * 自动化常驻前台服务（主架构 §5.4）：
 * API 34+ 只以 specialUse 类型进入前台，避免无投影会话时声明 mediaProjection 被严格 ROM 拒绝；
 * 启动失败时快速失败并写入退避状态，连续失败后自动关闭开关，不依赖 START_STICKY 无限重启。
 */
@AndroidEntryPoint
class AutoCaptureForegroundService : Service() {

    @Inject lateinit var automationSettings: AutomationSettingsDataStore
    @Inject lateinit var verificationNotifier: VerificationNotifier

    private val scope = CoroutineScope(SupervisorJob())

    /** 只有真正进入前台后才置 true；失败路径不写回 running=true。 */
    private var startedSuccessfully = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ 只声明 specialUse；mediaProjection 会话按需另行处理，
                // 不在无投影时混用类型，避免严格 ROM 校验失败。
                ServiceCompat.startForeground(
                    this,
                    Constants.FOREGROUND_SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Constants.FOREGROUND_SERVICE_ID, notification)
            }
            val now = System.currentTimeMillis()
            startedSuccessfully = true
            scope.launch { automationSettings.markServiceStarted(now) }
            // 常驻通知刷新出简略信息（今日支出），失败保持默认文案，不影响服务
            refreshSummary()
            START_STICKY
        } catch (_: Exception) {
            // 失败状态必须同步落盘后再 stopSelf，否则 onDestroy 取消 scope 会丢掉失败计数。
            runBlocking {
                withTimeoutOrNull(STATE_WRITE_TIMEOUT_MS) {
                    AutoCaptureForegroundService.handleStartFailure(this@AutoCaptureForegroundService, automationSettings)
                }
            }
            stopSelf()
            // 关键：失败返回 NOT_STICKY，切断「stopSelf → 系统立即重建 → 再失败」死循环。
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        // 先取消未完成的异步写入（如 markServiceStarted），杜绝销毁后继续写库/反向覆盖。
        scope.cancel()
        if (startedSuccessfully) {
            runBlocking {
                withTimeoutOrNull(STATE_WRITE_TIMEOUT_MS) {
                    automationSettings.setServiceRunning(false)
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(todayExpenseFen: Long? = null): Notification =
        buildNotificationFor(this, todayExpenseFen)

    private fun createChannel() = ensureAutoCaptureChannel(this)

    /**
     * 查一次今日支出并刷新常驻通知文案。整段惰性取依赖 + runCatching：
     * 前台服务创建时不做任何重依赖注入，避免数据库异常拖垮通知甚至进程。
     */
    private fun refreshSummary() {
        scope.launch {
            runCatching {
                val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    applicationContext, SummaryEntryPoint::class.java
                )
                val bookId = ep.settings().defaultBookId.first().takeIf { it > 0L } ?: return@runCatching
                val now = System.currentTimeMillis()
                val sum = ep.billDao().sumExpenseBetween(
                    bookId,
                    com.azu.bookkeeper.core.DateTimeUtils.startOfDay(now),
                    com.azu.bookkeeper.core.DateTimeUtils.endOfDay(now)
                )
                NotificationManagerCompat.from(this@AutoCaptureForegroundService)
                    .notify(Constants.FOREGROUND_SERVICE_ID, buildNotification(sum))
            }.onFailure { Timber.w(it, "refresh fgs summary failed") }
        }
    }

    companion object {
        const val ACTION_START = "com.azu.bookkeeper.action.AUTO_CAPTURE_START"
        const val ACTION_STOP = "com.azu.bookkeeper.action.AUTO_CAPTURE_STOP"

        private const val AUTO_DISABLED_NOTIFICATION_ID = 1004
        private const val STATE_WRITE_TIMEOUT_MS = 500L

        /**
         * 拉起服务；不向调用方抛异常，避免后台启动受限时把设置页 / Worker / 开机广播打崩。
         * @return 系统是否接受了启动请求（不代表服务随后一定能成功进入前台）。
         */
        fun start(context: Context): Boolean = try {
            val intent = Intent(context, AutoCaptureForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoCaptureForegroundService::class.java).setAction(ACTION_STOP)
            context.stopService(intent)
        }

        /** 通知是否真正可见（Android 13+ 等价于 POST_NOTIFICATIONS 是否授予）。 */
        fun areNotificationsEnabled(context: Context): Boolean = try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Exception) {
            false
        }

        /**
         * 构造常驻通知（标题 + 今日支出简略信息 + 「记一笔」动作）。
         * 放在 companion 里是为了让「账单变化后原地刷新」不依赖 Service 实例。
         */
        private fun buildNotificationFor(context: Context, todayExpenseFen: Long?): Notification {
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            // 点击常驻通知直接进「自动化设置」页：用户想关掉它时不用再找入口。
            val contentIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                AppEntryRouter.openAutomationSettingsIntent(context),
                flags
            )
            val quickCaptureIntent = QuickCaptureLauncher.pendingIntent(
                context,
                QuickCaptureRequest(entry = QuickEntry.FG_NOTIFICATION),
                Constants.FOREGROUND_SERVICE_ID + 1
            )
            return NotificationCompat.Builder(context, Constants.CHANNEL_AUTO_CAPTURE)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle(context.getString(R.string.auto_capture_notification_title))
                .setContentText(
                    if (todayExpenseFen != null && todayExpenseFen > 0L) {
                        // 简略信息：今日支出 + 引导用「记一笔」按钮直接记录
                        "今日支出 ${com.azu.bookkeeper.core.Money.format(todayExpenseFen)} · 点「记一笔」随手记"
                    } else {
                        context.getString(R.string.auto_capture_notification_text)
                    }
                )
                // 常驻 + 只响一次：渠道本身是 IMPORTANCE_LOW（不响铃），这里再兜住刷新时不重复打扰。
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                // 第四入口：常驻通知直接拉起记账表单，复用 FGS + POST_NOTIFICATIONS，无新权限。
                .addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_quick_add,
                        context.getString(R.string.quick_add),
                        quickCaptureIntent
                    ).build()
                )
                .build()
        }

        /**
         * 账单变动（新增 / 删除 / 自动入账）后原地刷新常驻通知上的「今日支出」。
         *
         * 刻意不使用 startForegroundService：服务若启动失败会累加失败计数、最终把自动化开关关掉，
         * 而「记完账刷新一下文案」绝不该有这种副作用。所以只在通知当前确实可见时替换它，
         * 不可见就什么都不做（下次服务启动时会带上最新金额）。
         */
        suspend fun updateSummaryIfVisible(context: Context, todayExpenseFen: Long) {
            val appContext = context.applicationContext
            runCatching {
                if (!areNotificationsEnabled(appContext)) return
                val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                val visible = manager.activeNotifications.any { it.id == Constants.FOREGROUND_SERVICE_ID }
                if (!visible) return
                NotificationManagerCompat.from(appContext)
                    .notify(Constants.FOREGROUND_SERVICE_ID, buildNotificationFor(appContext, todayExpenseFen))
            }.onFailure { Timber.w(it, "update fgs summary failed") }
        }

        /**
         * 幂等恢复 / 刷新常驻通知，App 回到前台（MainActivity.onResume）时调用。
         *
         * 解决两类「通知栏里没有常驻通知」：
         * 1) 开关开着但服务已被系统回收 → 这里重新拉起（历史上只有 15min 探活 Worker 和开机广播负责恢复，
         *    用户重新打开 App 也不会恢复）；
         * 2) 服务在跑，但通知是在没有 POST_NOTIFICATIONS 时投递的 —— Android 13+ 会直接丢弃且**不会补发**，
         *    用户事后去系统设置授权也不会自动出现 → 这里再 startForeground 一次把通知刷出来。
         *
         * @return 是否发出了启动 / 刷新请求。
         */
        suspend fun ensureRunning(context: Context, settings: AutomationSettingsDataStore): Boolean {
            val appContext = context.applicationContext
            if (!settings.enabled.first()) return false
            val running = settings.serviceRunning.first()
            // 已在运行且通知权限没给：刷新也刷不出通知，等用户授权后会再次调用本方法。
            if (running && !areNotificationsEnabled(appContext)) return false
            if (!settings.canStartService(System.currentTimeMillis())) return false
            return start(appContext)
        }

        /**
         * 统一记录一次启动失败；达到上限时关闭自动化开关并提示用户。
         * @return 本次失败是否触发了自动关闭。
         */
        suspend fun handleStartFailure(
            context: Context,
            settings: AutomationSettingsDataStore,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            val shouldDisable = settings.recordServiceStartFailure(nowMillis)
            if (shouldDisable) {
                settings.setEnabled(false)
                showAutoDisabledNotice(context.applicationContext)
            }
            return shouldDisable
        }

        /** 用现有自动化通知渠道告知用户「已降级」，不新增 strings 资源。 */
        private fun showAutoDisabledNotice(context: Context) {
            ensureAutoCaptureChannel(context)
            val notification = NotificationCompat.Builder(context, Constants.CHANNEL_AUTO_CAPTURE)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle("后台自动化已关闭")
                .setContentText("本机无法稳定启动后台服务，已自动关闭；请检查电池优化或改用手动记账")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(AUTO_DISABLED_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // 无通知权限时静默降级；开关状态已在 DataStore 中关闭。
            }
        }
    }
}

/**
 * 常驻通知简略信息用的惰性依赖入口。
 * 不在服务 onCreate 字段注入：数据库/依赖图异常时只会让通知文案退回默认值，不会崩进程。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SummaryEntryPoint {
    fun billDao(): BillDao
    fun settings(): SettingsRepository
}

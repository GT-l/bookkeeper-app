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
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.verify.VerificationNotifier
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.data.local.datastore.NotificationDisplayPrefs
import com.azu.bookkeeper.data.local.datastore.NotificationDisplaySettings
import com.azu.bookkeeper.data.local.datastore.NotificationDisplayStyles
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.domain.repository.BudgetRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.ui.navigation.AppEntryRouter
import com.azu.bookkeeper.ui.quick.QuickBallToggleActivity
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
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
import kotlin.math.max

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

/** 常驻通知要展示的数字。全部本地计算，失败时退化为 0 / 空串。 */
data class NotificationSummary(
    val todayFen: Long = 0L,
    val monthFen: Long = 0L,
    val budgetLeftFen: Long = 0L,
    val lastBill: String = "",
    val todayCount: Int = 0
)

data class NotificationPayload(
    val prefs: NotificationDisplayPrefs = NotificationDisplayPrefs(),
    val summary: NotificationSummary = NotificationSummary()
)

/**
 * 自动化常驻前台服务（主架构 §5.4）：
 * API 34+ 只以 specialUse 类型进入前台，避免无投影会话时声明 mediaProjection 被严格 ROM 拒绝；
 * 启动失败时快速失败并写入退避状态，连续失败后自动关闭开关，不依赖 START_STICKY 无限重启。
 *
 * 通知内容不再写死：用户可在「通知栏显示内容」页选样式和要显示的项目。
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
            // 常驻通知刷新为用户选中的样式与内容，失败保持默认文案，不影响服务
            refreshSummary()
            START_STICKY
        } catch (_: Exception) {
            // 失败状态必须同步落盘后再 stopSelf，否则 onDestroy 取消 scope 会丢掉失败计数。
            // 落盘本身再兜一层：DataStore 写入异常（磁盘 / ROM 限制）绝不能把崩溃抛回系统，
            // 否则就是「开启通知权限 → startForeground 失败 → 状态落盘失败 → App 闪退」。
            runCatching {
                runBlocking {
                    withTimeoutOrNull(STATE_WRITE_TIMEOUT_MS) {
                        AutoCaptureForegroundService.handleStartFailure(this@AutoCaptureForegroundService, automationSettings)
                    }
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

    /** 进入前台时的默认通知；随后 refreshSummary 会替换成用户选中的样式。 */
    private fun buildNotification(): Notification = buildNotificationFor(this, NotificationPayload())

    private fun createChannel() = ensureAutoCaptureChannel(this)

    private fun refreshSummary() {
        scope.launch {
            runCatching {
                val payload = collectPayload(applicationContext)
                NotificationManagerCompat.from(this@AutoCaptureForegroundService)
                    .notify(Constants.FOREGROUND_SERVICE_ID, buildNotificationFor(this@AutoCaptureForegroundService, payload))
            }.onFailure { Timber.w(it, "refresh fgs notification failed") }
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
         * 账单变动或通知设置变更后，原地刷新常驻通知。
         *
         * 刻意不使用 startForegroundService：服务若启动失败会累加失败计数、最终把自动化开关关掉，
         * 而「刷新一下文案」绝不该有这种副作用。只在通知已经可见时替换它，不可见就什么都不做。
         */
        suspend fun refreshNow(context: Context) {
            val app = context.applicationContext
            runCatching {
                if (!areNotificationsEnabled(app)) return
                val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                if (manager.activeNotifications.none { it.id == Constants.FOREGROUND_SERVICE_ID }) return
                val payload = collectPayload(app)
                NotificationManagerCompat.from(app)
                    .notify(Constants.FOREGROUND_SERVICE_ID, buildNotificationFor(app, payload))
            }.onFailure { Timber.w(it, "refresh fgs notification failed") }
        }

        /** 惰性取依赖 + runCatching：数据库异常只让通知退回默认内容，不会崩进程。 */
        private suspend fun collectPayload(context: Context): NotificationPayload = runCatching {
            val ep = EntryPointAccessors.fromApplication(
                context.applicationContext, SummaryEntryPoint::class.java
            )
            val prefs = ep.displaySettings().settings.first()
            val bookId = ep.settings().defaultBookId.first().takeIf { it > 0L } ?: 0L
            val now = System.currentTimeMillis()
            val dayStart = DateTimeUtils.startOfDay(now)
            val dayEnd = DateTimeUtils.endOfDay(now)
            val monthStart = DateTimeUtils.startOfMonth(now)
            val monthEnd = DateTimeUtils.endOfMonth(now)

            val monthFen = if (bookId > 0L) {
                ep.billDao().sumExpenseBetween(bookId, monthStart, monthEnd)
            } else 0L
            val todayFen = if (bookId > 0L) {
                ep.billDao().sumExpenseBetween(bookId, dayStart, dayEnd)
            } else 0L
            val budgetLeft = if (bookId > 0L && prefs.showBudget) {
                val limit = ep.budgetRepository()
                    .effectiveTotalLimit(bookId, DateTimeUtils.monthKey(now))
                max(0L, limit - monthFen)
            } else 0L
            val lastBill = if (bookId > 0L && prefs.showLastBill) {
                ep.billDao().lastExpenseBetween(bookId, monthStart, monthEnd)
                    ?.let { "${it.remark.ifBlank { "未备注" }} ${Money.format(it.amountFen)}" }
                    .orEmpty()
            } else ""
            val count = if (bookId > 0L && prefs.showCount) {
                ep.billDao().countExpenseBetween(bookId, dayStart, dayEnd)
            } else 0

            NotificationPayload(
                prefs = prefs,
                summary = NotificationSummary(todayFen, monthFen, budgetLeft, lastBill, count)
            )
        }.getOrDefault(NotificationPayload())

        /**
         * 用 RemoteViews 生成专用通知布局；同时保留纯文本兜底，
         * 以免个别 ROM 不渲染自定义布局时通知变成空白。
         */
        private fun buildNotificationFor(context: Context, payload: NotificationPayload): Notification {
            val prefs = payload.prefs
            val summary = payload.summary
            val views = RemoteViews(context.packageName, R.layout.notification_quick_content)

            fun line(id: Int, enabled: Boolean, text: String) {
                views.setViewVisibility(id, if (enabled && text.isNotBlank()) View.VISIBLE else View.GONE)
                if (enabled) views.setTextViewText(id, text)
            }

            val compact = prefs.style == NotificationDisplayStyles.COMPACT
            if (compact) {
                val parts = mutableListOf<String>()
                if (prefs.showToday) parts += "今日 ${Money.format(summary.todayFen)}"
                if (prefs.showMonth) parts += "本月 ${Money.format(summary.monthFen)}"
                line(
                    R.id.nf_today,
                    parts.isNotEmpty(),
                    parts.joinToString(" · ").ifBlank { context.getString(R.string.auto_capture_notification_text) }
                )
                line(R.id.nf_month, false, "")
                line(R.id.nf_budget, false, "")
                line(R.id.nf_last, false, "")
                line(R.id.nf_count, false, "")
            } else {
                line(R.id.nf_today, prefs.showToday, "今日支出 ${Money.format(summary.todayFen)}")
                line(R.id.nf_month, prefs.showMonth, "本月支出 ${Money.format(summary.monthFen)}")
                line(R.id.nf_budget, prefs.showBudget, "预算剩余 ${Money.format(summary.budgetLeftFen)}")
                line(R.id.nf_last, prefs.showLastBill, "最近一笔 ${summary.lastBill}")
                line(R.id.nf_count, prefs.showCount, "今日 ${summary.todayCount} 笔")
                if (prefs.style == NotificationDisplayStyles.BIG) {
                    views.setTextViewTextSize(
                        R.id.nf_today, TypedValue.COMPLEX_UNIT_SP, 20f
                    )
                }
            }

            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            val contentIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                AppEntryRouter.openAutomationSettingsIntent(context),
                flags
            )
            val quickIntent = QuickCaptureLauncher.pendingIntent(
                context,
                QuickCaptureRequest(entry = QuickEntry.FG_NOTIFICATION),
                Constants.FOREGROUND_SERVICE_ID + 1
            )
            val ballIntent = android.app.PendingIntent.getActivity(
                context,
                Constants.FOREGROUND_SERVICE_ID + 2,
                Intent(context, QuickBallToggleActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags
            )
            views.setOnClickPendingIntent(R.id.nf_root, contentIntent)
            views.setViewVisibility(
                R.id.nf_quick_add,
                if (prefs.showQuickAdd) View.VISIBLE else View.GONE
            )
            views.setOnClickPendingIntent(R.id.nf_quick_add, quickIntent)
            views.setViewVisibility(
                R.id.nf_ball_toggle,
                if (prefs.showBallToggle) View.VISIBLE else View.GONE
            )
            views.setOnClickPendingIntent(R.id.nf_ball_toggle, ballIntent)
            views.setViewVisibility(
                R.id.nf_actions,
                if (prefs.showQuickAdd || prefs.showBallToggle) View.VISIBLE else View.GONE
            )

            val fallback = buildPlainText(context, prefs, summary)
            return NotificationCompat.Builder(context, Constants.CHANNEL_AUTO_CAPTURE)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle(context.getString(R.string.auto_capture_notification_title))
                .setContentText(fallback)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .setCustomContentView(views)
                .setCustomBigContentView(views)
                .addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_quick_add,
                        context.getString(R.string.quick_add),
                        quickIntent
                    ).build()
                )
                .build()
        }

        private fun buildPlainText(
            context: Context,
            prefs: NotificationDisplayPrefs,
            summary: NotificationSummary
        ): String {
            val parts = mutableListOf<String>()
            if (prefs.showToday) parts += "今日 ${Money.format(summary.todayFen)}"
            if (prefs.showMonth) parts += "本月 ${Money.format(summary.monthFen)}"
            if (prefs.showBudget) parts += "预算剩余 ${Money.format(summary.budgetLeftFen)}"
            if (prefs.showCount) parts += "${summary.todayCount} 笔"
            if (parts.isEmpty()) parts += context.getString(R.string.auto_capture_notification_text)
            return parts.joinToString(" · ") + " · 点「记一笔」随手记"
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
            } catch (_: Exception) {
                // 无通知权限时静默降级（部分 ROM 阻塞主线程而非抛 SecurityException，一并吞掉）；
                // 开关状态已在 DataStore 中关闭。
            }
        }

        /**
         * 幂等恢复 / 刷新常驻通知，App 回到前台（MainActivity.onResume）时调用。
         */
        suspend fun ensureRunning(context: Context, settings: AutomationSettingsDataStore): Boolean {
            val appContext = context.applicationContext
            if (!settings.enabled.first()) return false
            val running = settings.serviceRunning.first()
            if (running && !areNotificationsEnabled(appContext)) return false
            if (!settings.canStartService(System.currentTimeMillis())) return false
            return start(appContext)
        }
    }
}

/** 常驻通知需要的惰性依赖入口（不在服务 onCreate 阶段做字段注入重依赖）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SummaryEntryPoint {
    fun billDao(): BillDao
    fun settings(): SettingsRepository
    fun displaySettings(): NotificationDisplaySettings
    fun budgetRepository(): BudgetRepository
}

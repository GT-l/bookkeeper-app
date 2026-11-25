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
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    private fun buildNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = openIntent?.let {
            android.app.PendingIntent.getActivity(this, 0, it, flags)
        }
        val quickCaptureIntent = QuickCaptureLauncher.pendingIntent(
            this,
            QuickCaptureRequest(entry = QuickEntry.FG_NOTIFICATION),
            Constants.FOREGROUND_SERVICE_ID + 1
        )
        return NotificationCompat.Builder(this, Constants.CHANNEL_AUTO_CAPTURE)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle(getString(R.string.auto_capture_notification_title))
            .setContentText(getString(R.string.auto_capture_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            // T7 第四入口：常驻通知直接拉起记账表单，复用 FGS + POST_NOTIFICATIONS，无新权限。
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_quick_add,
                    getString(R.string.quick_add),
                    quickCaptureIntent
                ).build()
            )
            .build()
    }

    private fun createChannel() = ensureAutoCaptureChannel(this)

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

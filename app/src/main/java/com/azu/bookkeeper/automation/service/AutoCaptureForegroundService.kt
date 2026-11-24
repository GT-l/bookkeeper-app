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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 自动化常驻前台服务（主架构 §5.4）：
 * API 34+ 使用 specialUse 类型（mediaProjection 授权时由 ScreenCaptureManager 临时切换）；
 * 销毁（onDestroy）时将 service_running 置 false 完成降级，供 WorkManager 探活恢复。
 */
@AndroidEntryPoint
class AutoCaptureForegroundService : Service() {

    @Inject lateinit var automationSettings: AutomationSettingsDataStore
    @Inject lateinit var verificationNotifier: VerificationNotifier

    private val scope = CoroutineScope(SupervisorJob())

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
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 基础常驻用 specialUse；mediaProjection 类型仅在投影会话激活时由
                // ScreenCaptureManager 触发切换，避免「无投影却声明该类型」在 API34+ 抛 SecurityException。
                ServiceCompat.startForeground(
                    this,
                    Constants.FOREGROUND_SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Constants.FOREGROUND_SERVICE_ID, notification)
            }
            scope.launch {
                automationSettings.setServiceRunning(true)
                automationSettings.setLastServiceStartAt(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            // 后台启动受限时停止，交由探活 Worker 在用户交互后重试
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch {
            automationSettings.setServiceRunning(false)
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(Constants.CHANNEL_AUTO_CAPTURE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    Constants.CHANNEL_AUTO_CAPTURE,
                    getString(R.string.notification_channel_auto_capture),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        const val ACTION_START = "com.azu.bookkeeper.action.AUTO_CAPTURE_START"
        const val ACTION_STOP = "com.azu.bookkeeper.action.AUTO_CAPTURE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, AutoCaptureForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoCaptureForegroundService::class.java).setAction(ACTION_STOP)
            context.stopService(intent)
        }
    }
}

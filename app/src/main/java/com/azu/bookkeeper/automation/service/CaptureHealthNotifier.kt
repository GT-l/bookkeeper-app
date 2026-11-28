package com.azu.bookkeeper.automation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.NotifyGuard
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher

/**
 * 识屏不可用提醒（v1.3.8）。
 *
 * 为什么需要它：无障碍被系统撤销后，自动记账是「静默失效」的——用户付完钱什么都没发生，
 * 既没有账单也没有提示，只能自己怀疑是不是没记上。钱迹/一木都把「运行状态」放在通知栏，
 * 这里做成一條**可点击的通知**：点正文直接进「记一笔」，点操作直达无障碍设置。
 *
 * 设计约束：
 * - 非常驻、不响铃（IMPORTANCE_LOW），恢复后自动撤掉，不违反「只留一条常驻通知」；
 * - 30 分钟节流，避免无障碍反复被撤销时刷屏；
 * - 全链路 runCatching：通知权限缺失 / ROM 拦截都只降级，绝不影响宿主服务。
 */
object CaptureHealthNotifier {

    private const val PREFS = "capture_health_notice"
    private const val KEY_LAST_NOTICE_AT = "last_notice_at"
    private const val KEY_VISIBLE = "notice_visible"
    private const val MIN_INTERVAL_MS = 30L * 60L * 1000L

    /** 通知正文点击 → 打开「记一笔」；与常驻通知用不同的 requestCode，避免 PendingIntent 复用。 */
    private const val REQUEST_CODE_QUICK_ADD = 2101
    private const val REQUEST_CODE_SETTINGS = 2102
    private const val REQUEST_CODE_CONTENT = 2103

    /** 无障碍不可用时推一条提醒；同一条提醒 30 分钟内不重复推。 */
    fun notifyUnavailable(context: Context, reason: String) {
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_NOTICE_AT, 0L)
            if (now - last < MIN_INTERVAL_MS) return
            ensureChannel(app)
            prefs.edit()
                .putLong(KEY_LAST_NOTICE_AT, now)
                .putBoolean(KEY_VISIBLE, true)
                .apply()
            val notification = build(app, reason)
            NotifyGuard.safeNotify(app, Constants.CAPTURE_HEALTH_NOTIFICATION_ID, notification)
            com.azu.bookkeeper.core.CaptureLog.forceInfo(
                app, "A11Y", "识屏不可用通知已推送：$reason"
            )
        }
    }

    /** 识屏恢复后撤掉提醒，并把节流时间清零，下次失效能立刻再提醒。 */
    fun clear(context: Context) {
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_VISIBLE, false)) return
            NotificationManagerCompat.from(app).cancel(Constants.CAPTURE_HEALTH_NOTIFICATION_ID)
            prefs.edit()
                .putBoolean(KEY_VISIBLE, false)
                .putLong(KEY_LAST_NOTICE_AT, 0L)
                .apply()
        }
    }

    private fun build(context: Context, reason: String): android.app.Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val quickAdd = QuickCaptureLauncher.pendingIntent(
            context,
            QuickCaptureRequest(entry = QuickEntry.FG_NOTIFICATION),
            REQUEST_CODE_QUICK_ADD
        )
        val settingsIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_SETTINGS,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        // 正文点击也进「记一笔」：识屏坏了的时候用户最想做的就是先把这笔补上
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_CONTENT,
            QuickCaptureLauncher.intent(context, QuickCaptureRequest(entry = QuickEntry.FG_NOTIFICATION)),
            flags
        )
        return NotificationCompat.Builder(context, Constants.CHANNEL_CAPTURE_HEALTH)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle(context.getString(R.string.capture_health_title))
            .setContentText(reason)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$reason\n点这里先手动记一笔；想恢复自动识别就点「去开启」。")
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_quick_add, context.getString(R.string.quick_add), quickAdd)
            .addAction(0, context.getString(R.string.capture_health_action_enable), settingsIntent)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(Constants.CHANNEL_CAPTURE_HEALTH) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_CAPTURE_HEALTH,
                context.getString(R.string.capture_health_channel),
                // LOW：进通知栏但不响铃、不弹横幅，不给用户添噪音
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}

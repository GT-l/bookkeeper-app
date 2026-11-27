package com.azu.bookkeeper.automation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.permission.AccessibilityHealth
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.verify.OverlayVerifyView
import com.azu.bookkeeper.core.CaptureLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 无障碍存活守护（v1.3.1）。
 *
 * 用户反馈：长时间运行后系统会主动关闭无障碍开关。真机日志已证明这不是进程被杀，
 * 而是系统撤销授权 —— 所以「保活 App」无效，必须盯住 **系统开关本身**。
 *
 * 三件事：
 * 1. IMPORTANCE_MIN 的静默前台通知：折叠在通知栏底部、不弹横幅，降低系统回收概率；
 * 2. 每 60 秒检查系统开关：撤销时若已获 ADB / Shizuku 的 WRITE_SECURE_SETTINGS，
 *    直接写回授权自动恢复（[AccessibilityHealth.tryRestore]）；
 * 3. 没有该授权时，每 10 分钟最多弹一次底部轻卡片「去开启」，不常驻、不拉起 App。
 *
 * 所有环节 runCatching：守护服务自己绝不能崩，否则等于又少一层保护。
 */
class A11yWatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, WatchdogEntryPoint::class.java
        )
    }
    private val checkRunnable = object : Runnable {
        override fun run() {
            runCatching { checkOnce() }.onFailure { t ->
                CaptureLog.w(applicationContext, "A11Y", "守护检查异常（服务保持存活）", t)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            ensureChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            handler.removeCallbacks(checkRunnable)
            handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        }
        // START_STICKY：被系统回收后由系统重建，守护链不会因为一次回收而永久断掉
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { handler.removeCallbacks(checkRunnable) }
        super.onDestroy()
    }

    private fun checkOnce() {
        val guide = runCatching { entryPoint.permissionGuide() }.getOrNull() ?: return
        if (guide.isAccessibilityEnabled()) return
        // 从没连上过的用户不需要被提醒（可能根本没启用自动记账）
        if (!guide.accessibilityServiceEverConnected()) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_PROMPT, 0L) < PROMPT_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_PROMPT, now).apply()

        // 有 ADB / Shizuku 授权 → 静默恢复，不打扰用户
        if (AccessibilityHealth.tryRestore(applicationContext)) return

        // 无授权 → 底部轻卡片，12 秒自动消失；没有可用窗口通道就只记日志，不弹通知栏
        val shown = runCatching {
            entryPoint.overlay().showConfirm(
                title = "无障碍服务被系统关闭了",
                detail = "自动记账已暂停。点「去开启」回到系统无障碍页重新打开；" +
                    "若反复被关，可用 ADB 一次性授权，让守护服务以后自动恢复。",
                confirmLabel = "去开启",
                editLabel = null,
                onConfirm = {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onDiscard = {},
                autoDismissMs = 12_000L
            )
        }.getOrDefault(false)
        CaptureLog.forceInfo(
            applicationContext, "A11Y",
            "守护检测到无障碍开关被关闭，自动恢复不可用（未授予 WRITE_SECURE_SETTINGS），" +
                "轻提示已显示=$shown"
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "无障碍守护", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "低打扰常驻通知，降低系统回收无障碍服务的概率"
                }
            )
        }
    }

    private fun buildNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("少花点儿 · 无障碍守护")
            .setContentText("自动记账保障运行中")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val PREFS = "a11y_watchdog"
        private const val KEY_LAST_PROMPT = "last_prompt_at"
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val PROMPT_INTERVAL_MS = 10 * 60_000L
        private const val NOTIFICATION_ID = 1030
        private const val CHANNEL_ID = "channel_a11y_watchdog"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, A11yWatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context.applicationContext, intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            }
        }
    }
}

/** 守护服务的惰性依赖：不用 @AndroidEntryPoint 字段注入，避免启动期依赖异常拖垮服务。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WatchdogEntryPoint {
    fun permissionGuide(): PermissionGuide
    fun overlay(): OverlayVerifyView
}

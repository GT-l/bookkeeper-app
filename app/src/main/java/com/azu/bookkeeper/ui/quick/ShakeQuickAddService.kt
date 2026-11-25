package com.azu.bookkeeper.ui.quick

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import timber.log.Timber

/**
 * 摇一摇记一笔：独立前台服务，**不依赖无障碍服务**。
 *
 * 为什么迁出来：上一版把摇一摇放在 PaymentAccessibilityService 里，只要用户没开
 * 读屏服务（或 ColorOS 把无障碍快捷方式显示成系统悬浮球但服务实际未绑定），
 * onServiceConnected 不执行 → 传感器没注册 → 摇一摇完全没反应。
 * 现在由设置页直接启停本服务，常驻通知可见，用户能确认它确实在运行。
 *
 * 振动 + Toast 保证每次命中都有明确反馈；拉起表单失败时退化为一条可点开的通知。
 */
class ShakeQuickAddService : Service() {

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var lastShakeAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ 声明了 foregroundServiceType 就必须用带 type 的重载，否则抛
                // MissingForegroundServiceTypeException，服务起不来（ColorOS 15 等机型常见）
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            registerSensor()
            isRunning = true
            START_NOT_STICKY
        } catch (t: Throwable) {
            Timber.e(t, "ShakeQuickAddService start failed")
            Toast.makeText(applicationContext, "摇一摇启动失败，请检查传感器权限", Toast.LENGTH_LONG).show()
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun registerSensor() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = manager
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val x = event.values.getOrNull(0) ?: return
                val y = event.values.getOrNull(1) ?: return
                val z = event.values.getOrNull(2) ?: return
                // 静止时合加速度≈9.8（重力）；摇动会明显超过。24 ≈ 2.4g，
                // 配合 1.2s 冷却，正常走路/拿放手机不会误触。
                val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
                val now = System.currentTimeMillis()
                if (magnitude > SHAKE_THRESHOLD && now - lastShakeAt > COOLDOWN_MS) {
                    lastShakeAt = now
                    onShakeDetected()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = callback
        manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun onShakeDetected() {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80L)
            }
        }
        Toast.makeText(applicationContext, "摇一摇命中，打开记一笔", Toast.LENGTH_SHORT).show()
        val request = QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE)
        val launched = runCatching {
            startActivity(QuickCaptureLauncher.intent(applicationContext, request))
        }.isSuccess
        if (!launched) runCatching { showFallbackNotification(request) }
    }

    private fun showFallbackNotification(request: QuickCaptureRequest) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_AUTO_CAPTURE)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("摇一摇已触发")
            .setContentText("点这里记一笔")
            .setAutoCancel(true)
            .setContentIntent(QuickCaptureLauncher.pendingIntent(this, request, 2002))
            .build()
        NotificationManagerCompat.from(this).notify(2002, notification)
    }

    private fun ensureChannel() {
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

    private fun buildNotification(): android.app.Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openSettings = PendingIntent.getActivity(
            this,
            2003,
            Intent(this, com.azu.bookkeeper.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            2004,
            Intent(this, ShakeQuickAddService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, Constants.CHANNEL_AUTO_CAPTURE)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("摇一摇记一笔运行中")
            .setContentText("摇动手机即可打开记账表单")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openSettings)
            .addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_quick_add, "关闭", stopIntent).build()
            )
            .build()
    }

    override fun onDestroy() {
        runCatching { listener?.let { sensorManager?.unregisterListener(it) } }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_STOP"
        private const val NOTIFICATION_ID = 1020
        private const val SHAKE_THRESHOLD = 24f
        private const val COOLDOWN_MS = 1_200L

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            if (isRunning) return
            runCatching {
                val intent = Intent(context, ShakeQuickAddService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "start ShakeQuickAddService failed") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ShakeQuickAddService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

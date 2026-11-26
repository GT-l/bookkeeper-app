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
import androidx.core.app.ServiceCompat
import com.azu.bookkeeper.MainActivity
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.CaptureDiagnostics
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureReport
import com.azu.bookkeeper.core.DiagnosticStep
import com.azu.bookkeeper.core.NotifyGuard
import com.azu.bookkeeper.core.QuickGestureCache
import com.azu.bookkeeper.core.ShakeActions
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 摇一摇记一笔：独立前台服务，不依赖无障碍服务。
 *
 * 摇一摇命中后的动作由设置页决定（见 ShakeActions）：
 * - OPEN_FORM：直接打开记账表单；
 * - SCREEN_CAPTURE：触发识屏管线；识屏失败 / 未授权 / 识别未完成时，
 *   自动打开记账表单让用户手动输入，绝不只弹个 Toast 就结束。
 *
 * 后台拉起 Activity 在部分 ROM（尤其 ColorOS）上会被拦截：第一次能用、之后只响 Toast
 * 就是这么来的。因此保留两条路：先直接 startActivity；失败时发一条带 full-screen intent
 * 的高优先级通知，由系统把记账表单推到前台（用户报的「只提醒不跳转」由此修复）。
 */
class ShakeQuickAddService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ShakeActionEntryPoint::class.java)
    }

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var lastShakeAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // 未授予通知权限时部分 ROM 会抛 SecurityException 甚至阻塞主线程（ANR）→ 统一走守卫
                NotifyGuard.safeNotify(this, NOTIFICATION_ID, buildNotification())
                return START_NOT_STICKY
            }
        }
        return try {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            // 用持久化值回填动作缓存（设置页改动也会同步更新缓存）
            scope.launch {
                runCatching {
                    QuickGestureCache.shakeAction = entryPoint.automationSettings().shakeAction.first()
                }.onFailure { Timber.w(it, "read shake action failed") }
            }
            registerSensor()
            isRunning = true
            // 授权完成后由授权页发 ACTION_CAPTURE_NOW 回来继续识屏（必须在 startForeground 之后）
            if (intent?.action == ACTION_CAPTURE_NOW) runCatching { triggerScreenCapture() }
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
                // 静止时合加速度≈9.8（重力）；24≈2.4g，配合冷却避免走路误触
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
        // 每次命中都双震 + Toast：不依赖通知权限，用户一定能感知到「已经触发了」
        vibrate()
        if (QuickGestureCache.shakeAction == ShakeActions.SCREEN_CAPTURE) {
            Toast.makeText(applicationContext, "摇一摇已触发 · 正在识屏…", Toast.LENGTH_SHORT).show()
            triggerScreenCapture()
        } else {
            openForm("摇一摇命中，打开记一笔")
        }
    }

    /**
     * 摇一摇 → 轻量识屏：截屏 + OCR + 智能提取 → 带预填打开表单。
     *
     * 多重提醒：
     * - 没有截屏授权：自动拉起透明授权页；被 ROM 拦下则发「点击授权」通知；
     * - 任一环节失败：Toast 明确原因 + 进度通知标题写明原因 + 自检页留痕 + 自动打开表单。
     */
    private fun triggerScreenCapture() {
        val capture = runCatching { entryPoint.lightweightCapture() }.getOrNull()
        if (capture == null) {
            recordDiagnostic(
                CaptureFailReason.UNKNOWN,
                listOf(DiagnosticStep("依赖注入", false, "LightweightCapture 取不到"))
            )
            openForm("识屏组件加载失败，已打开记一笔")
            return
        }
        if (capture.needsAuthorization()) {
            requestCaptureAuthorization(capture)
            return
        }
        capture.captureIntoForm(scope) { outcome ->
            val message = when {
                outcome.amountFen != null && outcome.amountFen > 0L -> "识别完成，请确认金额后保存"
                !outcome.success -> "识屏失败：${outcome.reason?.title}，请手动输入"
                else -> "没有识别到金额，请手动输入"
            }
            openCaptureForm(capture, outcome.amountFen, outcome.merchant, message)
        }
    }

    /** 没有截屏授权：优先拉起透明授权页；被 ROM 拦截则发可点击的「去授权」通知兜底。 */
    private fun requestCaptureAuthorization(capture: LightweightCapture) {
        val authIntent = Intent(this, ScreenCaptureAuthActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { startActivity(authIntent) }.isSuccess
        if (launched) {
            capture.toast("请允许屏幕捕获，授权后会自动继续识屏")
            return
        }
        recordDiagnostic(
            CaptureFailReason.NEED_AUTH_LAUNCH_FAILED,
            listOf(DiagnosticStep("拉起授权页", false, "后台启动 Activity 被系统拦截"))
        )
        runCatching {
            val pi = PendingIntent.getActivity(
                this,
                2007,
                authIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            entryPoint.captureProgress().showNeedAuth(pi)
        }
        openForm("识屏需要截屏授权，已打开记一笔")
    }

    /** 统一记录诊断（自检页展示最近一次失败步骤）。 */
    private fun recordDiagnostic(reason: CaptureFailReason, steps: List<DiagnosticStep>) {
        runCatching {
            CaptureDiagnostics.record(
                applicationContext,
                CaptureReport(System.currentTimeMillis(), reason, steps)
            )
        }
    }

    /** 用轻量识屏器的预填 Intent 打开表单；失败走全屏通知兜底。 */
    private fun openCaptureForm(
        capture: LightweightCapture,
        amountFen: Long?,
        merchant: String,
        message: String
    ) {
        capture.toast(message)
        val launched = runCatching {
            startActivity(capture.formIntent(amountFen, merchant))
        }.isSuccess
        if (!launched) {
            val request = QuickCaptureRequest(
                entry = QuickEntry.SYSTEM_GESTURE,
                amountFen = amountFen,
                merchant = merchant
            )
            showFullScreenFallback(request)
        }
    }

    /** 打开记账表单；后台启动被拦时用全屏通知兜底，保证每次都能进来。 */
    private fun openForm(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        val request = QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE)
        val launched = runCatching {
            startActivity(QuickCaptureLauncher.intent(applicationContext, request))
        }.isSuccess
        if (!launched) showFullScreenFallback(request)
    }

    private fun showFullScreenFallback(request: QuickCaptureRequest) {
        runCatching { ensureGestureChannel() }
        // 全屏通知兜底也走守卫：通知权限被关时部分 ROM 会阻塞甚至抛 SecurityException
        val posted = runCatching {
            val pending = QuickCaptureLauncher.pendingIntent(this, request, 2002)
            NotifyGuard.safeNotify(this, 2002, buildFullScreenNotification(pending))
        }.getOrDefault(false)
        if (!posted) Timber.w("full screen fallback not posted (notification permission off / ROM blocked)")
    }

    private fun buildFullScreenNotification(pending: PendingIntent): android.app.Notification =
        NotificationCompat.Builder(this, GESTURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("摇一摇已触发")
            .setContentText("点这里记一笔")
            .setAutoCancel(true)
            .setContentIntent(pending)
            // 高优先级渠道 + 全屏意图，可绕过后台启动 Activity 限制
            .setFullScreenIntent(pending, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

    private fun vibrate() {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 双脉冲：与普通通知震动区分开，明确是「摇一摇命中」
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 70, 70, 70), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 70, 70, 70), -1)
            }
        }
    }

    /**
     * 摇一摇常驻通知改用独立低重要级渠道。
     * 不再复用 CHANNEL_AUTO_CAPTURE：自动化服务（LOW）、校对通知（DEFAULT）、
     * 摇一摇（HIGH）三方抢建同一渠道时，重要级以先建者为准且系统永久保留，
     * 会出现「有时响铃有时静音」的不稳定表现——这就是用户反馈的操作异常之一。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONGOING_CHANNEL_ID,
                    "摇一摇快捷记账",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    /** 全屏意图单独用高重要级渠道，避免把常驻通知渠道也变成响铃/弹窗。 */
    private fun ensureGestureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(GESTURE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    GESTURE_CHANNEL_ID,
                    "手势快捷记账",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openSettings = PendingIntent.getActivity(
            this,
            2003,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            2004,
            Intent(this, ShakeQuickAddService::class.java).setAction(ACTION_STOP),
            flags
        )
        val action = ShakeActions.label(QuickGestureCache.shakeAction)
        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("摇一摇记一笔运行中")
            .setContentText("当前动作：$action · 摇动手机触发")
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
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_STOP"
        const val ACTION_REFRESH = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_REFRESH"

        /** 截屏授权完成后继续识屏。 */
        const val ACTION_CAPTURE_NOW = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_CAPTURE_NOW"
        private const val NOTIFICATION_ID = 1020
        private const val ONGOING_CHANNEL_ID = "shake_ongoing"
        private const val GESTURE_CHANNEL_ID = "shake_gesture_high"
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

        /** 动作选择变化后刷新常驻通知文案；服务没在跑时什么也不做。 */
        fun refresh(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, ShakeQuickAddService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ShakeQuickAddService::class.java).setAction(ACTION_STOP))
            }
        }

        /** 授权完成后继续识屏；服务没在跑时顺带拉起（onStartCommand 内会先 startForeground）。 */
        fun captureNow(context: Context) {
            runCatching {
                val intent = Intent(context, ShakeQuickAddService::class.java)
                    .setAction(ACTION_CAPTURE_NOW)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}

/** getter 名不能与不同类型冲突：automationSettings() 与 lightweightCapture() 均与已有入口同类型。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShakeActionEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
    fun lightweightCapture(): LightweightCapture
    fun captureProgress(): CaptureProgressNotifier
}

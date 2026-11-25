package com.azu.bookkeeper.automation.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.capture.AccessibilityCaptureParser
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.QuickGestureCache
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 无障碍读屏捕获（主架构 §5.1 主路径，AC-4）：
 * 仅对白名单包名、完成态关键词触发；1.5s 去抖 + eventId 幂等避免连发重复抓取。
 * 抖音包名作为强信号打 businessTag=DOUYIN，但不改 captureType。
 *
 * 同时承载「系统原生手势 → 记一笔」：指纹四向滑动 / 摇一摇 / 无障碍按钮，
 * 用户在设置页自选。不再使用底部悬浮手势条——那条会吞掉其他应用的触摸。
 * 所有手势回调只做轻动作，异常一律降级，绝不让无障碍服务崩掉（它崩了读屏也没了）。
 */
@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {

    @Inject lateinit var parser: AccessibilityCaptureParser
    @Inject lateinit var tagger: DouyinBusinessTagger
    @Inject lateinit var coordinator: CaptureCoordinator
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private val scope = CoroutineScope(SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, QuickGestureEntryPoint::class.java
        )
    }

    private var lastEventAt = 0L
    private var lastPackage = ""

    // ---- 手势相关状态 ----
    private var fingerprintController: FingerprintGestureController? = null
    private var fingerprintCallback: FingerprintGestureController.FingerprintGestureCallback? = null
    private var accessibilityButtonController: AccessibilityButtonController? = null
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var shakeListener: SensorEventListener? = null
    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 手势选择从持久化值回填；设置页改动会通过 QuickGestureCache 立即同步（同进程）
        scope.launch {
            runCatching {
                QuickGestureCache.code = gestureEntryPoint.automationSettings().quickGesture.first()
            }
        }
        setupFingerprintGestures()
        setupAccessibilityButton()
        setupShakeDetection()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName !in Constants.PAYMENT_PACKAGES) return

        val now = System.currentTimeMillis()
        if (packageName == lastPackage && now - lastEventAt < Constants.CAPTURE_DEBOUNCE_MS) return

        val root = event.source ?: rootInActiveWindow ?: return
        val text = parser.collectText(root)
        if (text.isBlank() || !parser.isPaymentComplete(text)) return

        lastEventAt = now
        lastPackage = packageName
        val eventId = generateEventId(packageName, text)
        val businessTag: BusinessTag = tagger.tag(packageName, text)
        val captureEvent = parser.buildEvent(
            eventId = eventId,
            packageName = packageName,
            text = text,
            eventTime = now,
            businessTag = businessTag
        )
        scope.launch {
            withContext(ioDispatcher) { coordinator.onCapture(captureEvent) }
        }
    }

    /** 系统导航栏的无障碍按钮（部分 ROM 提供）：通过官方 Controller 回调，不是 Service 方法。 */
    private fun setupAccessibilityButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val controller = runCatching { accessibilityButtonController }.getOrNull() ?: return
        accessibilityButtonController = controller
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                if (QuickGestureCache.code == QuickGestureCodes.ACCESSIBILITY_BUTTON) {
                    triggerQuickAdd()
                }
            }

            override fun onAvailabilityChanged(
                controller: AccessibilityButtonController,
                available: Boolean
            ) = Unit
        }
        accessibilityButtonCallback = callback
        runCatching { controller.registerAccessibilityButtonCallback(callback, mainHandler) }
    }

    /** 指纹传感器四向滑动：不需要触摸探索模式，不影响正常点击/滑动。 */
    private fun setupFingerprintGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val controller = runCatching { fingerprintGestureController }.getOrNull() ?: return
        fingerprintController = controller
        val callback = object : FingerprintGestureController.FingerprintGestureCallback() {
            override fun onGestureDetected(gesture: Int) {
                val actual = when (gesture) {
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> QuickGestureCodes.FINGER_UP
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> QuickGestureCodes.FINGER_DOWN
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> QuickGestureCodes.FINGER_LEFT
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> QuickGestureCodes.FINGER_RIGHT
                    else -> QuickGestureCodes.OFF
                }
                if (actual != QuickGestureCodes.OFF && actual == QuickGestureCache.code) {
                    triggerQuickAdd()
                }
            }

            override fun onGestureDetectionAvailabilityChanged(available: Boolean) = Unit
        }
        fingerprintCallback = callback
        runCatching { controller.registerFingerprintGestureCallback(callback, mainHandler) }
    }

    /** 摇一摇：加速度模长超过阈值且冷却 900ms，只在用户选中该手势时生效。 */
    private fun setupShakeDetection() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            private var lastShakeAt = 0L
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || QuickGestureCache.code != QuickGestureCodes.SHAKE) return
                val x = event.values.getOrNull(0) ?: return
                val y = event.values.getOrNull(1) ?: return
                val z = event.values.getOrNull(2) ?: return
                val force = x * x + y * y + z * z
                val now = System.currentTimeMillis()
                if (force > 22f * 22f && now - lastShakeAt > 900L) {
                    lastShakeAt = now
                    triggerQuickAdd()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        shakeListener = listener
        runCatching {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /**
     * 手势命中 → 打开记一笔表单。
     * 无障碍服务属于系统绑定服务，通常可直接拉起 Activity；若被 ROM 拦下，
     * 退化成一条带「记一笔」按钮的通知，保证手势永远有反馈。
     */
    private fun triggerQuickAdd() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < GESTURE_COOLDOWN_MS) return
        lastTriggerAt = now
        val request = QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE)
        val launched = runCatching {
            startActivity(QuickCaptureLauncher.intent(applicationContext, request))
        }.isSuccess
        if (!launched) runCatching { showGestureFallbackNotification(request) }
    }

    private fun showGestureFallbackNotification(request: QuickCaptureRequest) {
        val channelId = Constants.CHANNEL_AUTO_CAPTURE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager?.getNotificationChannel(channelId) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(channelId, "自动记账", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("手势已触发")
            .setContentText("点这里记一笔")
            .setAutoCancel(true)
            .setContentIntent(QuickCaptureLauncher.pendingIntent(this, request, 2001))
            .build()
        NotificationManagerCompat.from(this).notify(2001, notification)
    }

    /** 稳定 eventId：包名 + 文本哈希 + 秒，避免同一完成页连发重复建单（与去重键一致）。 */
    private fun generateEventId(packageName: String, text: String): Long {
        val base = (packageName + text).hashCode().toLong() and 0x7fffffffL
        return (base xor (System.currentTimeMillis() / 1000L)) and Long.MAX_VALUE
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching {
            fingerprintCallback?.let { fingerprintController?.unregisterFingerprintGestureCallback(it) }
            accessibilityButtonCallback?.let {
                accessibilityButtonController?.unregisterAccessibilityButtonCallback(it)
            }
            shakeListener?.let {
                (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(it)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val GESTURE_COOLDOWN_MS = 2_500L
    }
}

/** 手势开关的惰性依赖入口：不在服务字段注入，避免依赖图异常拖垮读屏服务。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickGestureEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
}

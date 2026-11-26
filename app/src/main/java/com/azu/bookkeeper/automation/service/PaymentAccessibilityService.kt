package com.azu.bookkeeper.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.capture.AccessibilityCaptureParser
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.NotifyGuard
import com.azu.bookkeeper.core.QuickGestureCache
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍读屏捕获（主架构 §5.1 主路径，AC-4）：
 * 仅对白名单包名、完成态关键词触发；1.5s 去抖 + eventId 幂等避免连发重复抓取。
 * 抖音包名作为强信号打 businessTag=DOUYIN，但不改 captureType。
 *
 * 同时承载「指纹手势 → 记一笔」（指纹传感器四向滑动，需用户开启本服务）。
 * 摇一摇已迁到独立前台服务 ShakeQuickAddService，不再依赖本服务；
 * 「无障碍按钮」选项已移除——ColorOS 会把它渲染成系统悬浮球，造成困扰。
 * 所有手势回调只做轻动作，异常一律降级，绝不让无障碍服务崩掉（它崩了读屏也没了）。
 */
class PaymentAccessibilityService : AccessibilityService() {

    /**
     * 关键：这里绝不使用 @AndroidEntryPoint 字段注入。
     *
     * 字段注入发生在 onCreate 之前，一旦 CaptureCoordinator 那套重依赖
     * （OCR / Room / 校验视图 / 多 Repository）中任何一环构造失败，
     * 服务会在系统绑定阶段直接崩溃；很多 ROM（尤其 ColorOS / Orange 等定制系统）
     * 检测到无障碍服务崩溃后会自动把它关掉 —— 表现就是「开完返回再进来，
     * 读屏服务被自动取消」。改为惰性取 + 每个回调全链路 runCatching，
     * 最坏情况只是这一次没识别，服务本身永远活着。
     */
    private val deps by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, AccessibilityDepsEntryPoint::class.java
        )
    }

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
    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching { ensureScreenshotCapability() }
        // 心跳：主进程读这个标记判断服务是否真的连上（系统开关状态在部分 ROM 上不可靠）
        runCatching {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CONNECTED, System.currentTimeMillis()).apply()
        }
        // 手势选择从持久化值回填；设置页改动会通过 QuickGestureCache 立即同步（同进程）
        scope.launch {
            runCatching {
                QuickGestureCache.code = gestureEntryPoint.automationSettings().quickGesture.first()
            }
        }
        setupFingerprintGestures()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 整个回调必须零异常逃出：无障碍服务一崩，ROM 就可能自动禁用它
        runCatching { handleEvent(event) }.onFailure { t ->
            timber.log.Timber.w(t, "accessibility event failed (service kept alive)")
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = runCatching { event.packageName?.toString().orEmpty() }.getOrDefault("")
        if (packageName !in Constants.PAYMENT_PACKAGES) return

        val now = System.currentTimeMillis()
        if (packageName == lastPackage && now - lastEventAt < Constants.CAPTURE_DEBOUNCE_MS) return

        val root = runCatching { event.source ?: rootInActiveWindow }.getOrNull() ?: return
        val parser = runCatching { deps.parser() }.getOrNull() ?: return
        val text = runCatching { parser.collectText(root) }.getOrDefault("")
        if (text.isBlank() || !runCatching { parser.isPaymentComplete(text) }.getOrDefault(false)) return

        lastEventAt = now
        lastPackage = packageName
        val eventId = generateEventId(packageName, text)
        val tagger = runCatching { deps.tagger() }.getOrNull()
        val businessTag: BusinessTag = tagger?.let { runCatching { it.tag(packageName, text) }.getOrNull() }
            ?: BusinessTag.NONE
        val captureEvent = parser.buildEvent(
            eventId = eventId,
            packageName = packageName,
            text = text,
            eventTime = now,
            businessTag = businessTag
        )
        val coordinator = runCatching { deps.coordinator() }.getOrNull() ?: return
        val dispatcher = runCatching { deps.ioDispatcher() }.getOrNull() ?: return
        scope.launch {
            runCatching { withContext(dispatcher) { coordinator.onCapture(captureEvent) } }
                .onFailure { timber.log.Timber.w(it, "capture coordinator failed (service kept alive)") }
        }
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
        // 手势兜底通知走统一守卫：无障碍服务里抛 SecurityException 的后果最严重——
        // ROM 会直接把读屏服务禁用且不再自动恢复（Orange 7 实测），必须在这里掐断。
        NotifyGuard.safeNotify(this, 2001, notification)
    }

    /** 稳定 eventId：包名 + 文本哈希 + 秒，避免同一完成页连发重复建单（与去重键一致）。 */
    private fun generateEventId(packageName: String, text: String): Long {
        val base = (packageName + text).hashCode().toLong() and 0x7fffffffL
        return (base xor (System.currentTimeMillis() / 1000L)) and Long.MAX_VALUE
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CONNECTED, 0L).apply()
        }
        runCatching {
            fingerprintCallback?.let { fingerprintController?.unregisterFingerprintGestureCallback(it) }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val GESTURE_COOLDOWN_MS = 2_500L
        const val PREFS = "accessibility_service_status"
        const val KEY_LAST_CONNECTED = "last_connected_at"

        /**
         * 当前活着的服务实例。无障碍截屏（API 30+ takeScreenshot）必须由服务实例发起，
         * 摇一摇/自检在别的组件里，通过这里拿实例；服务被杀后自动置空。
         */
        @Volatile
        private var instance: PaymentAccessibilityService? = null

        fun current(): PaymentAccessibilityService? = instance

        /** 是否具备无障碍截屏能力（API 30+ 且 CAPABILITY_CAN_TAKE_SCREENSHOT）。 */
        fun canCaptureScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val service = instance ?: return false
            return runCatching {
                val caps = service.serviceInfo?.capabilities ?: 0
                caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
            }.getOrDefault(false)
        }
    }

    /**
     * XML 已配 canTakeScreenshot；这里再兜一次（部分 ROM 首连时 capability 未刷新）。
     */
    private fun ensureScreenshotCapability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val info = serviceInfo ?: return
        val canShot = android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT
        if (info.capabilities and canShot == 0) {
            info.capabilities = info.capabilities or canShot
            setServiceInfo(info)
        }
    }
}

/** 手势开关的惰性依赖入口：不在服务字段注入，避免依赖图异常拖垮读屏服务。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickGestureEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
}

/** 读屏服务的惰性依赖入口：字段注入会让服务在 ROM 崩溃后自动被禁用。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccessibilityDepsEntryPoint {
    fun parser(): AccessibilityCaptureParser
    fun tagger(): DouyinBusinessTagger
    fun coordinator(): CaptureCoordinator
    @IoDispatcher fun ioDispatcher(): CoroutineDispatcher
}

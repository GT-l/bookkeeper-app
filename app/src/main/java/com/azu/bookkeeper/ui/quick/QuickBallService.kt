package com.azu.bookkeeper.ui.quick

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.CaptureEvent
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 悬浮球快捷入口（T7 / AC-15）。
 *
 * 与 T5 的 OverlayVerifyView 共用 SYSTEM_ALERT_WINDOW 权限，权限未授予时
 * 复用 PermissionGuide 的系统设置跳转引导，不重复申请；权限校验失败只降级、不崩溃。
 *
 * 手势约定（识屏记账可行性结论见 [triggerScreenCapture] 注释）：
 * - 单击：打开记账表单（保留现状，延迟一个双击窗口执行，见 onClick）；
 * - 长按：主动触发识屏记账（截屏 → OCR → CaptureCoordinator 完整管线）；
 * - 双击：收起悬浮球（原「长按关闭」改为双击关闭，避免长按识屏与关闭手势冲突）。
 */
@AndroidEntryPoint
class QuickBallService : Service() {

    @Inject lateinit var permissionGuide: PermissionGuide
    @Inject lateinit var screenCaptureManager: ScreenCaptureManager
    @Inject lateinit var captureCoordinator: CaptureCoordinator
    @Inject lateinit var captureDao: CaptureDao

    private var ballView: View? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 双击判定与单击延迟执行共用主线程 Handler；Toast 也统一回到主线程弹出。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 单击动作（打开表单）延迟执行的 Runnable；双击到来时先取消它。 */
    private var pendingSingleTap: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!permissionGuide.canDrawOverlays()) {
            toast("请先授予悬浮窗权限，再开启悬浮球", Toast.LENGTH_LONG)
            runCatching { startActivity(permissionGuide.overlaySettingsIntent()) }
            stopSelf()
            return START_NOT_STICKY
        }
        showBall()
        return START_STICKY
    }

    /** 添加可拖拽的「记」球；重复 start 时先移除旧视图避免叠加。 */
    private fun showBall() {
        removeBall()
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val view = Button(this).apply {
            text = "记"
            textSize = 18f
            setTextColor(Color.WHITE)
            // 主题草莓粉（与 App 主题一致），替换原硬编码 #C62828
            setBackgroundColor(Color.parseColor("#C74372"))
            alpha = 0.88f
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (160 * density).toInt()
        }

        var startX = params.x
        var startY = params.y
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { windowManager().updateViewLayout(v, params) }
                    // 不消费事件，抬起时仍能触发 onClick
                    false
                }
                else -> false
            }
        }
        view.setOnClickListener {
            val pending = pendingSingleTap
            if (pending != null) {
                // 双击窗口内的第二次点击：取消待执行的单击动作，收起悬浮球
                mainHandler.removeCallbacks(pending)
                pendingSingleTap = null
                stopSelf()
            } else {
                // 延迟 DOUBLE_TAP_TIMEOUT_MS 再打开表单，给双击留出判定窗口
                val singleTap = Runnable {
                    pendingSingleTap = null
                    openForm()
                }
                pendingSingleTap = singleTap
                mainHandler.postDelayed(singleTap, DOUBLE_TAP_TIMEOUT_MS)
            }
        }
        view.setOnLongClickListener {
            triggerScreenCapture()
            true
        }

        try {
            windowManager().addView(view, params)
            ballView = view
        } catch (_: Exception) {
            // 权限被系统回收等场景：安静降级，用户可在设置页重新开启
            ballView = null
            stopSelf()
        }
    }

    private fun openForm() {
        val request = QuickCaptureRequest(entry = QuickEntry.FLOATING_BALL)
        runCatching { startActivity(QuickCaptureLauncher.intent(applicationContext, request)) }
            .onFailure { toast("无法打开记账表单", Toast.LENGTH_SHORT) }
    }

    /**
     * 长按手势主动触发识屏记账。
     *
     * 手势载体可行性排查结论（为什么选悬浮球长按）：
     * 1. AccessibilityService.onGesture / dispatchGesture：全屏手势拦截会吞掉用户的正常
     *    操作（滑动、返回、下拉通知栏都被截走），且与已有的 PaymentAccessibilityService
     *    读屏职责耦合，误触率与功耗都不可接受 —— 排除；
     * 2. FingerprintGestureController：仅极少数带指纹手势硬件的机型支持，覆盖面过窄 —— 排除；
     * 3. 悬浮球长按：QuickBallService 已持有 SYSTEM_ALERT_WINDOW，长按事件天然落在自己的
     *    overlay 视图上，不影响系统与第三方应用的手势，实现成本最低且不误触 —— 采用。
     *
     * 识屏流程不重复 OCR：CaptureCoordinator.onCapture 内部已包含
     * 幂等 → 落库 → OCR → 分类 → 置信度 → 去重 → 自动入账/进校对 的完整管线，
     * 这里只负责截屏并投递 [CaptureEvent]。
     */
    private fun triggerScreenCapture() {
        if (!screenCaptureManager.hasActiveSession()) {
            // MediaProjection 授权只在 MainActivity 的设置/授权流程里能发起，
            // Service 无法可靠拉起系统授权弹窗（且后台启动受限），Toast 引导后退出，不崩溃。
            toast("请先在 设置→自动化 里完成识屏授权", Toast.LENGTH_LONG)
            return
        }
        scope.launch {
            runCatching {
                val path = screenCaptureManager.capture()
                    ?: return@launch toastOnMain("识屏失败，请重新完成授权", Toast.LENGTH_LONG)
                val event = CaptureEvent(
                    // eventId 只需保证幂等键唯一；手动触发场景以毫秒时间戳派生即可
                    eventId = System.currentTimeMillis(),
                    captureType = CaptureType.PROJECTION_IMAGE,
                    imagePath = path,
                    rawText = "",
                    billTime = System.currentTimeMillis()
                )
                captureCoordinator.onCapture(event)
                // 管线终态由 DB status 反推，区分三种反馈口径
                when (captureDao.findByEventId(event.eventId)?.status) {
                    CaptureEventStatus.LOW_CONFIDENCE.code -> toastOnMain("已进入校对", Toast.LENGTH_SHORT)
                    // PENDING：管线因缺默认账本等原因未走完，事件留待后续处理
                    CaptureEventStatus.PENDING.code ->
                        toastOnMain("识别完成，请先在应用内设置默认账本", Toast.LENGTH_LONG)
                    else -> toastOnMain("已识别并记账", Toast.LENGTH_SHORT)
                }
            }.onFailure { e ->
                Timber.w(e, "quick ball screen capture failed")
                toastOnMain("识屏失败，请稍后再试", Toast.LENGTH_SHORT)
            }
        }
    }

    /** 当前线程即主线程时直接弹；否则切主线程，避免非 Looper 线程 Toast 崩溃。 */
    private fun toast(message: String, duration: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, message, duration).show()
        } else {
            toastOnMain(message, duration)
        }
    }

    private fun toastOnMain(message: String, duration: Int) {
        mainHandler.post { Toast.makeText(this, message, duration).show() }
    }

    private fun removeBall() {
        ballView?.let { view ->
            runCatching { windowManager().removeView(view) }
        }
        ballView = null
    }

    private fun windowManager(): WindowManager =
        getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun onDestroy() {
        removeBall()
        pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
        pendingSingleTap = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.azu.bookkeeper.action.QUICK_BALL_START"
        const val ACTION_STOP = "com.azu.bookkeeper.action.QUICK_BALL_STOP"

        /** 双击判定窗口（毫秒），与系统 ViewConfiguration 双击超时口径一致。 */
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L

        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, QuickBallService::class.java).setAction(ACTION_START))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, QuickBallService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

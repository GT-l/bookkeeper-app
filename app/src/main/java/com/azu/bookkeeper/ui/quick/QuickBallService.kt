package com.azu.bookkeeper.ui.quick

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
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
    @Inject lateinit var automationSettings: com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore

    private var ballView: View? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 双击判定与单击延迟执行共用主线程 Handler；Toast 也统一回到主线程弹出。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 单击动作（打开表单）延迟执行的 Runnable；双击到来时先取消它。 */
    private var pendingSingleTap: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 任何异常都不得逃出本方法：Service 抛异常会直接杀死进程，
        // 若再叠加系统重启就是用户看到的「频繁闪退」。统一降级 + 明确提示。
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            if (!permissionGuide.canDrawOverlays()) {
                // 不在这里主动跳系统设置：Service 里跳转会打断当前任务（用户容易以为「App 退出了」），
                // 引导跳转统一由设置页的开关处理。
                toast("尚未授予「显示在其他应用上层」权限，请在 设置→自动化 里开启", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY
            }
            val added = showBall()
            if (!added) {
                toast("悬浮球创建失败，请确认已允许「显示在其他应用上层」后重试", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY
            }
            isRunning = true
            // 不使用 START_STICKY：进程被杀后由系统反复拉起、反复失败，正是「频繁闪退」的放大器；
            // 恢复交给 MainActivity.onResume / BootReceiver / 用户手动开关。
            START_NOT_STICKY
        } catch (t: Throwable) {
            Timber.e(t, "QuickBallService start failed")
            toast("悬浮球启动失败：${t.javaClass.simpleName}", Toast.LENGTH_LONG)
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    /**
     * 添加可拖拽的「记」球；重复 start 时先移除旧视图避免叠加。
     * 返回 true 表示已成功挂载到 WindowManager。
     */
    private fun showBall(): Boolean {
        removeBall()
        val view: View = try {
            buildBallView()
        } catch (t: Throwable) {
            // 主题/资源异常等极端情况下也不让进程崩掉
            Timber.e(t, "build ball view failed")
            return false
        }
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
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
                // 同步把开关落库为关闭，否则下次回到 App（onResume 兜底）会把它又拉起来
                scope.launch {
                    runCatching { automationSettings.setFloatingBallEnabled(false) }
                }
                toast("悬浮球已收起，可在 设置→自动化 重新开启", Toast.LENGTH_SHORT)
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

        return try {
            windowManager().addView(view, params)
            ballView = view
            true
        } catch (t: Throwable) {
            // 权限被系统回收 / ROM 拦截：明确告知原因，不再静默
            Timber.e(t, "add overlay view failed")
            ballView = null
            false
        }
    }

    /**
     * 构建可爱风「记」球：粉色圆点 + 白色「记」字 + 轻微阴影。
     * 用代码构建 GradientDrawable 而不是依赖 XML/AppCompat 主题，
     * 避免极简主题下控件实例化失败导致 Service 反复崩溃。
     */
    private fun buildBallView(): View {
        val density = resources.displayMetrics.density
        val radius = 26 * density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#C74372"))
            setStroke((2 * density).toInt(), Color.parseColor("#FFF9F5"))
        }
        return TextView(this).apply {
            text = "记"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg
            elevation = 6 * density
            alpha = 0.92f
            // 触摸热区略大于视觉圆点，方便手指按住
            setPadding(0, 0, 0, 0)
            width = (radius * 2).toInt()
            height = (radius * 2).toInt()
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
        isRunning = false
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

        /** 悬浮球当前是否已挂载。由 onStartCommand 成功挂载后置 true，onDestroy 置 false。 */
        @Volatile
        var isRunning: Boolean = false

        /**
         * 启动悬浮球。调用方若在 Activity/前台场景直接调用即可；
         * 未授予悬浮窗权限或后台启动受限时静默失败（服务内部会 Toast 说明），不抛异常。
         */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, QuickBallService::class.java).setAction(ACTION_START))
            }.onFailure { Timber.w(it, "start QuickBallService failed") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, QuickBallService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

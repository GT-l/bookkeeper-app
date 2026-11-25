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
import com.azu.bookkeeper.core.SafeMode
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 悬浮球快捷入口（T7 / AC-15）。
 *
 * 与 T5 的 OverlayVerifyView 共用 SYSTEM_ALERT_WINDOW 权限，权限未授予时
 * 复用 PermissionGuide 的系统设置跳转引导，不重复申请；权限校验失败只降级、不崩溃。
 *
 * 依赖获取方式：**不使用 @AndroidEntryPoint 字段注入**，改为 [OverlayEntryPoint] 惰性取。
 * 字段注入发生在 onCreate 之前，注入失败会让进程在服务创建阶段直接崩溃（onStartCommand
 * 的 try/catch 拦不住），这正是「开启悬浮球即闪退、之后每次启动都闪退」的成因；
 * 惰性获取 + 全程 runCatching 后，最坏情况只是功能不可用。
 *
 * 手势约定：
 * - 单击：打开记账表单（延迟一个双击窗口执行）；
 * - 长按：主动触发识屏记账（截屏 → OCR → CaptureCoordinator 完整管线）；
 * - 双击：收起悬浮球。
 */
class QuickBallService : Service() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, OverlayEntryPoint::class.java)
    }

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
            if (!canDrawOverlays()) {
                // 不在这里主动跳系统设置：Service 里跳转会打断当前任务（用户容易以为「App 退出了」），
                // 引导跳转统一由设置页的开关处理。
                toast("尚未授予「显示在其他应用上层」权限，请在 设置→自动化 里开启", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY
            }
            // 已经挂载过就直接返回：本服务在独立进程 :overlay，主进程的 isRunning 恒为 false，
            // 于是每次回到前台都会再发一次 startService；这里挡住重复 addView，避免球被重置位置/闪烁。
            if (ballView != null) {
                isRunning = true
                return START_NOT_STICKY
            }
            val added = showBall()
            if (!added) {
                toast("悬浮球创建失败，请确认已允许「显示在其他应用上层」后重试", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY

            }
            isRunning = true
            // 正向信号：本机悬浮层至少成功挂载过一次（安全模式判断用）
            runCatching { SafeMode.markOverlayVerified(this) }
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
                // 注意：本服务运行在独立进程 :overlay，DataStore 不支持跨进程写，
                // 因此这里不再回写开关（避免把用户数据写坏）。收起后由用户自行在设置页关闭开关，
                // 下次回到 App 时 onResume 会按开关状态决定是否恢复。
                toast("悬浮球已收起，可在 设置→自动化 关闭该开关彻底停用", Toast.LENGTH_SHORT)
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
        // 惰性取依赖：Hilt 图构造失败也只影响这一次识屏，不会拖垮进程
        val captureTrigger = runCatching { entryPoint.quickCaptureTrigger() }.getOrNull()
        if (captureTrigger == null) {
            toast("识屏组件不可用，请打开记一笔", Toast.LENGTH_LONG)
            openForm()
            return
        }
        val launched = captureTrigger.trigger(scope) { text, duration ->
            toastOnMain(text, duration)
        }
        if (!launched) {
            // MediaProjection 授权只在 MainActivity 的设置/授权流程里能发起，
            // Service 无法可靠拉起系统授权弹窗（且后台启动受限），降级为直接打开记一笔表单。
            runCatching { startActivity(captureTrigger.formIntent()) }
                .onFailure { toast("未完成识屏授权，请在 设置→自动化 里开启", Toast.LENGTH_LONG) }
        }
    }

    /** 悬浮窗权限判断同样惰性取依赖，失败按「无权限」处理，绝不抛出。 */
    private fun canDrawOverlays(): Boolean = runCatching {
        entryPoint.permissionGuide().canDrawOverlays()
    }.getOrDefault(android.provider.Settings.canDrawOverlays(this))

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

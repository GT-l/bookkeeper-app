package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.azu.bookkeeper.core.SafeMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * 三指上滑快速记账（用户需求：增加手势触发三指上滑触发记账）。
 *
 * 可行性说明（为什么是「底部手势条」而不是真·全局手势）：
 * Android 不向第三方 App 派发任意全局触摸事件，AccessibilityService 也只能感知
 * 窗口内容变化与按键，拿不到多点触控原始流。唯一不需要系统权限的载体是
 * 悬浮窗（SYSTEM_ALERT_WINDOW，App 已持有）：在屏幕底部放一条全宽、几乎全透明的
 * 手势条，三根手指按在条上向上滑动即触发识屏记账。
 *
 * 代价与取舍（如实告知用户）：
 * - 手势条本身会消费落在条内的触摸；条高仅 34dp 且贴屏幕底边，
 *   正常 App 的可点击区域几乎不会落在这个范围；
 * - 与系统「全面屏返回手势」（底边上滑）可能冲突：三指同时上滑才触发，
 *   单指/双指在条上的操作会被吞掉（返回手势走单指，实际影响很小）；
 * - 部分机型「三指上滑截屏」为系统手势，从屏幕中部开始的三指滑动不受本条影响。
 *
 * 触发语义：已授权识屏 → 识屏记账；未授权 → 打开记一笔表单（保证手势必有响应）。
 */
class ThreeFingerGestureService : android.app.Service() {

    /** 惰性取依赖：不在 onCreate 阶段注入，避免依赖图异常导致进程崩溃（同悬浮球）。 */
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, OverlayEntryPoint::class.java)
    }

    private var stripView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun canDrawOverlays(): Boolean = runCatching {
        entryPoint.permissionGuide().canDrawOverlays()
    }.getOrDefault(android.provider.Settings.canDrawOverlays(this))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            if (!canDrawOverlays()) {
                toast("尚未授予「显示在其他应用上层」权限，三指手势无法启用", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY
            }
            // 与悬浮球同理：独立进程内做幂等判断，重复 start 不重复挂载手势条
            if (stripView != null) {
                isRunning = true
                return START_NOT_STICKY
            }
            if (!showStrip()) {
                toast("手势条创建失败，请确认已允许「显示在其他应用上层」后重试", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY
            }
            isRunning = true
            runCatching { SafeMode.markOverlayVerified(this) }
            START_NOT_STICKY
        } catch (t: Throwable) {
            Timber.e(t, "ThreeFingerGestureService start failed")
            toast("三指手势启动失败：${t.javaClass.simpleName}", Toast.LENGTH_LONG)
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** 底部全宽透明手势条；返回 true 表示挂载成功。 */
    private fun showStrip(): Boolean {
        removeStrip()
        val density = resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val view = View(this).apply { alpha = 0f }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (34 * density).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }

        // 三指手势状态机：>=3 指按下且各自上滑超过阈值 → 触发一次
        val pointerStartY = HashMap<Int, Float>()
        val triggerThresholdPx = (110 * density).toInt()
        var triggered = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = event.actionIndex
                    pointerStartY[event.getPointerId(idx)] = event.getY(idx)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!triggered && pointerStartY.size >= 3) {
                        var movedUp = 0
                        for (i in 0 until event.pointerCount) {
                            val id = event.getPointerId(i)
                            val startY = pointerStartY[id] ?: continue
                            if (startY - event.getY(i) >= triggerThresholdPx) movedUp++
                        }
                        if (movedUp >= 3) {
                            triggered = true
                            onThreeFingerSwipeUp()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pointerStartY.clear()
                    triggered = false
                }
                else -> Unit
            }
            true
        }

        return try {
            windowManager().addView(view, params)
            stripView = view
            true
        } catch (t: Throwable) {
            Timber.e(t, "add gesture strip failed")
            stripView = null
            false
        }
    }

    private fun onThreeFingerSwipeUp() {
        val captureTrigger = runCatching { entryPoint.quickCaptureTrigger() }.getOrNull()
        if (captureTrigger == null) {
            toast("识屏组件不可用，请打开记一笔", Toast.LENGTH_LONG)
            return
        }
        val launched = captureTrigger.trigger(scope) { text, duration ->
            mainHandler.post { Toast.makeText(this, text, duration).show() }
        }
        if (!launched) {
            // 未授权识屏：降级为打开记一笔表单，手势依然有响应
            runCatching { startActivity(captureTrigger.formIntent()) }
                .onFailure { toast("未完成识屏授权，请在 设置→自动化 里开启", Toast.LENGTH_LONG) }
        }
    }

    private fun removeStrip() {
        stripView?.let { runCatching { windowManager().removeView(it) } }
        stripView = null
    }

    private fun windowManager(): WindowManager =
        getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun toast(message: String, duration: Int) {
        mainHandler.post { Toast.makeText(this, message, duration).show() }
    }

    override fun onDestroy() {
        isRunning = false
        removeStrip()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.azu.bookkeeper.action.THREE_FINGER_START"
        const val ACTION_STOP = "com.azu.bookkeeper.action.THREE_FINGER_STOP"

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, ThreeFingerGestureService::class.java).setAction(ACTION_START))
            }.onFailure { Timber.w(it, "start ThreeFingerGestureService failed") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ThreeFingerGestureService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

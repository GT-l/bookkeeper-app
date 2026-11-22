package com.azu.bookkeeper.ui.quick

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 悬浮球快捷入口（T7 / AC-15）。
 *
 * 与 T5 的 OverlayVerifyView 共用 SYSTEM_ALERT_WINDOW 权限，权限未授予时
 * 复用 PermissionGuide 的系统设置跳转引导，不重复申请；点击拉起记账表单，
 * 长按收起。权限校验失败只降级、不崩溃。
 */
@AndroidEntryPoint
class QuickBallService : Service() {

    @Inject lateinit var permissionGuide: PermissionGuide

    private var ballView: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!permissionGuide.canDrawOverlays()) {
            Toast.makeText(this, "请先授予悬浮窗权限，再开启悬浮球", Toast.LENGTH_LONG).show()
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
            setBackgroundColor(Color.parseColor("#C62828"))
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
        view.setOnClickListener { openForm() }
        view.setOnLongClickListener {
            stopSelf()
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
            .onFailure { Toast.makeText(this, "无法打开记账表单", Toast.LENGTH_SHORT).show() }
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.azu.bookkeeper.action.QUICK_BALL_START"
        const val ACTION_STOP = "com.azu.bookkeeper.action.QUICK_BALL_STOP"

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

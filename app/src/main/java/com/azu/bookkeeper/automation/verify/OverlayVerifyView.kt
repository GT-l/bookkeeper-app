package com.azu.bookkeeper.automation.verify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.CaptureCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮窗校对（PRD 22.4d 中档：后台 + SYSTEM_ALERT_WINDOW 已授权）。
 * 仅在 VerifyMode=1（悬浮窗）时被 ViewModel 调用；权限未授予时返回 false 让调用方降级通知。
 */
@Singleton
class OverlayVerifyView @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var currentView: View? = null

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    /** 展示悬浮校对卡，用户点「记一笔」或「忽略」时回调；返回是否成功展示。 */
    fun show(candidate: CaptureCandidate, onConfirm: () -> Unit, onDiscard: () -> Unit): Boolean {
        if (!canShow()) return false
        dismiss()

        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(context).apply {
            text = "识别到一笔支出，请确认"
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
        }
        val amount = TextView(context).apply {
            text = "${candidate.amountFen?.let { Money.format(it) }.orEmpty()} ${candidate.merchant}"
            textSize = 20f
            setTextColor(Color.parseColor("#C62828"))
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val confirm = Button(context).apply {
            text = "记一笔"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dismiss()
                onConfirm()
            }
        }
        val discard = Button(context).apply {
            text = "忽略"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dismiss()
                onDiscard()
            }
        }
        actions.addView(confirm)
        actions.addView(discard)
        container.addView(title)
        container.addView(amount)
        container.addView(actions)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = padding
            y = (72 * density).toInt()
        }
        return try {
            windowManager().addView(container, params)
            currentView = container
            true
        } catch (_: Exception) {
            false
        }
    }

    fun dismiss() {
        currentView?.let { view ->
            runCatching { windowManager().removeView(view) }
            currentView = null
        }
    }

    private fun windowManager(): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}

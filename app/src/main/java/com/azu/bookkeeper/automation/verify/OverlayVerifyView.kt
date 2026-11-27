package com.azu.bookkeeper.automation.verify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 * 屏幕底部悬浮确认卡片（SYSTEM_ALERT_WINDOW）。
 *
 * v1.3.0：疑似重复 / 中置信度的确认不再 `startActivity` 把用户拽进软件界面，
 * 改为贴在屏幕底部的一张卡片（咔皮记账「通知卡片」同类形态）：
 * - `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL`：不抢焦点、不拦截卡片外的操作；
 * - 宽度 MATCH_PARENT、gravity BOTTOM，靠近拇指、符合底部弹窗直觉；
 * - 权限未授予时返回 false，由调用方降级为「点击才打开」的普通通知。
 *
 * 纯 View 体系（无 ComposeView），避免 Service 里补 LifecycleOwner 的额外复杂度。
 */
@Singleton
class OverlayVerifyView @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var currentView: View? = null

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    /** 校对链原有入口：底部卡片显示候选账单，确认即入账。 */
    fun show(candidate: CaptureCandidate, onConfirm: () -> Unit, onDiscard: () -> Unit): Boolean =
        showConfirm(
            title = "识别到一笔支出，请确认",
            detail = "${candidate.amountFen?.let { Money.format(it) }.orEmpty()} ${candidate.merchant}".trim(),
            confirmLabel = "记一笔",
            editLabel = null,
            onConfirm = onConfirm,
            onDiscard = onDiscard
        )

    /**
     * 通用底部确认卡片。
     * [editLabel] 为 null 时不显示「修改」按钮；三个回调都在主线程触发，调用方自行切协程落库。
     */
    fun showConfirm(
        title: String,
        detail: String,
        confirmLabel: String = "记账",
        editLabel: String? = "修改",
        onConfirm: () -> Unit,
        onEdit: () -> Unit = {},
        onDiscard: () -> Unit = {}
    ): Boolean {
        if (!canShow()) return false
        dismiss()

        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                cornerRadius = 20f * density
            }
            setPadding(pad * 2, pad, pad * 2, pad * 2)
            elevation = 8f * density
        }
        container.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
        })
        container.addView(TextView(context).apply {
            text = detail
            textSize = 19f
            setTextColor(Color.parseColor("#C62828"))
            setPadding(0, pad / 2, 0, pad / 2)
        })

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, block: () -> Unit) = Button(context).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dismiss()
                block()
            }
        }
        actions.addView(action(confirmLabel, onConfirm))
        editLabel?.let { actions.addView(action(it, onEdit)) }
        actions.addView(action("忽略", onDiscard))
        container.addView(actions)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = (12 * density).toInt()
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

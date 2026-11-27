package com.azu.bookkeeper.automation.verify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.azu.bookkeeper.automation.service.PaymentAccessibilityService
import androidx.compose.ui.graphics.toArgb
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.ui.theme.DarkOnSurface
import com.azu.bookkeeper.ui.theme.DarkOutline
import com.azu.bookkeeper.ui.theme.DarkPrimaryContainer
import com.azu.bookkeeper.ui.theme.DarkSurface
import com.azu.bookkeeper.ui.theme.LightOnSurface
import com.azu.bookkeeper.ui.theme.LightOutline
import com.azu.bookkeeper.ui.theme.LightSurface
import com.azu.bookkeeper.ui.theme.PinkContainer
import com.azu.bookkeeper.ui.theme.Primary
import com.azu.bookkeeper.ui.theme.PrimaryDark
import com.azu.bookkeeper.ui.theme.PrimaryLight
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕底部轻卡片（v1.3.1）：**所有识屏反馈的唯一出口**。
 *
 * 三种形态：
 * 1. [showSuccess]  高置信度已自动入账 → 「✓ 记账成功 · ¥xx 商户」，3 秒自动消失，无按钮；
 * 2. [showInfo]     确定重复已跳过等纯提示 → 3 秒自动消失，无按钮；
 * 3. [showConfirm]  低置信度 / 疑似重复 → 「仍然记账 / 修改 / 忽略」，12 秒自动忽略。
 *
 * 关键修复（v1.3.1）：
 * - 优先用 `TYPE_ACCESSIBILITY_OVERLAY`：无障碍服务绑定的窗口**不需要 SYSTEM_ALERT_WINDOW 权限**，
 *   过去没授予悬浮窗权限就返回 false → 调用方降级发通知栏，这是「重复提醒变成通知」的根因；
 * - 所有 addView / removeView 切主线程（过去从 IO 线程直接操作 WindowManager，失败即降级）；
 * - 自动消失，不再常驻遮挡，也不再拉起 Activity / 前台 Dialog。
 */
@Singleton
class OverlayVerifyView @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null

    /** 有活跃的无障碍服务 → 不需要悬浮窗权限也能弹；否则要求已授予 SYSTEM_ALERT_WINDOW。 */
    fun canShow(): Boolean =
        PaymentAccessibilityService.current() != null || canDrawOverlay()

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(context)

    private fun isDark(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 校对链原有入口：低置信度候选的底部确认卡片。 */
    fun show(candidate: CaptureCandidate, onConfirm: () -> Unit, onDiscard: () -> Unit): Boolean =
        showConfirm(
            title = "识别到一笔支出，请确认",
            detail = "${candidate.amountFen?.let { Money.format(it) }.orEmpty()} ${candidate.merchant}".trim(),
            confirmLabel = "记一笔",
            editLabel = null,
            onConfirm = onConfirm,
            onDiscard = onDiscard
        )

    /** 自动入账成功：轻提示，不询问、不需要点击，3 秒消失。 */
    fun showSuccess(amountFen: Long, remark: String): Boolean = showInfo(
        title = "✓ 已自动记账 ${Money.format(amountFen)}",
        detail = remark.ifBlank { "账单已存入账本" },
        autoDismissMs = SUCCESS_VISIBLE_MS
    )

    /** 纯提示（已跳过重复、已忽略等）：3 秒消失。 */
    fun showInfo(title: String, detail: String, autoDismissMs: Long = INFO_VISIBLE_MS): Boolean =
        showConfirm(
            title = title,
            detail = detail,
            confirmLabel = "",   // 空标签 → 不生成任何操作按钮
            editLabel = null,
            onConfirm = {},
            onDiscard = {},
            autoDismissMs = autoDismissMs
        )

    /** 通用底部卡片；[confirmLabel] 为空时纯展示。 */
    fun showConfirm(
        title: String,
        detail: String,
        confirmLabel: String = "记账",
        editLabel: String? = "修改",
        onConfirm: () -> Unit,
        onEdit: () -> Unit = {},
        onDiscard: () -> Unit = {},
        autoDismissMs: Long = CONFIRM_VISIBLE_MS
    ): Boolean {
        if (!canShow()) return false
        val latch = CountDownLatch(1)
        val result = booleanArrayOf(false)
        runOnMain {
            try {
                dismissInternal()
                val host = PaymentAccessibilityService.current() ?: context
                val density = host.resources.displayMetrics.density
                val dp = { value: Int -> (value * density).toInt() }
                val dark = isDark(host)
                val cardBg = (if (dark) DarkSurface else LightSurface).toArgb()
                val textColor = (if (dark) DarkOnSurface else LightOnSurface).toArgb()
                val accent = (if (dark) PrimaryLight else Primary).toArgb()
                val chipBg = (if (dark) DarkPrimaryContainer else PinkContainer).toArgb()
                val border = (if (dark) DarkOutline else LightOutline).toArgb()

                // 外层只负责左右留白，真正卡片是里面的圆角容器 —— 与 App 内的 SoftCard 一致
                val container = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(12), dp(8))
                    clipChildren = false
                    clipToPadding = false
                }
                val card = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(cardBg)
                        cornerRadius = dp(24).toFloat()
                        setStroke(dp(1), border)
                    }
                    elevation = dp(12).toFloat()
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                }
                // 顶部：圆形粉色徽标 + 标题，和「我的」页卡片头风格一致
                val header = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(host).apply {
                    text = if (confirmLabel.isBlank()) "✓" else "✦"
                    textSize = 16f
                    setTextColor(accent)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(chipBg)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                })
                header.addView(TextView(host).apply {
                    text = title
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(textColor)
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = dp(10) }
                })
                card.addView(header)
                if (detail.isNotBlank()) {
                    card.addView(TextView(host).apply {
                        text = detail
                        textSize = 16f
                        setTextColor(if (dark) 0xFFFFD9E5.toInt() else PrimaryDark.toArgb())
                        setPadding(0, dp(10), 0, 0)
                    })
                }
                if (confirmLabel.isNotBlank()) {
                    val actions = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(14), 0, 0)
                    }
                    fun pill(label: String, primary: Boolean, block: () -> Unit) =
                        TextView(host).apply {
                            text = label
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setPadding(dp(10), dp(9), dp(10), dp(9))
                            setTextColor(if (primary) Color.WHITE else accent)
                            background = GradientDrawable().apply {
                                cornerRadius = dp(999).toFloat()
                                if (primary) setColor(accent) else {
                                    setColor(Color.TRANSPARENT)
                                    setStroke(dp(1), accent)
                                }
                            }
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                .apply { rightMargin = dp(8) }
                            setOnClickListener {
                                dismiss()
                                runCatching(block)
                            }
                        }
                    actions.addView(pill(confirmLabel, true, onConfirm))
                    editLabel?.takeIf { it.isNotBlank() }?.let { actions.addView(pill(it, false, onEdit)) }
                    actions.addView(pill("忽略", false, onDiscard))
                    card.addView(actions)
                }
                container.addView(card)

                val service = PaymentAccessibilityService.current()
                val type = when {
                    service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ->
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }
                val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                wm.addView(container, params)
                currentView = container
                result[0] = true
                val runnable = Runnable { dismiss() }
                dismissRunnable = runnable
                mainHandler.postDelayed(runnable, autoDismissMs)
            } catch (_: Exception) {
                result[0] = false
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(1, TimeUnit.SECONDS) }
        return result[0]
    }

    /** 供外部在收到用户决定后立刻收起（避免自动消失前重复操作）。 */
    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissInternal()
        } else {
            mainHandler.post { dismissInternal() }
        }
    }

    private fun dismissInternal() {
        dismissRunnable?.let { runCatching { mainHandler.removeCallbacks(it) } }
        dismissRunnable = null
        currentView?.let { view ->
            runCatching {
                val host = PaymentAccessibilityService.current() ?: context
                (host.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
            currentView = null
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val SUCCESS_VISIBLE_MS = 3_200L
        const val INFO_VISIBLE_MS = 3_200L
        const val CONFIRM_VISIBLE_MS = 12_000L
    }
}

package com.azu.bookkeeper.automation.verify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.service.PaymentAccessibilityService
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.ui.theme.DarkOnSurface
import com.azu.bookkeeper.ui.theme.DarkOutline
import com.azu.bookkeeper.ui.theme.DarkSurface
import com.azu.bookkeeper.ui.theme.LightOnSurface
import com.azu.bookkeeper.ui.theme.LightOutline
import com.azu.bookkeeper.ui.theme.Primary
import com.azu.bookkeeper.ui.theme.PrimaryDark
import com.azu.bookkeeper.ui.theme.PrimaryLight
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕底部轻卡片（v1.3.4 奶油风重做）：**所有识屏反馈的唯一出口**。
 *
 * 三种形态：
 * 1. [showSuccess]  高置信度已自动入账 → 「记下啦 · ¥xx」+ 暖心短句，3 秒自动消失；
 * 2. [showInfo]     确定重复已跳过等纯提示 → 3 秒自动消失，无按钮；
 * 3. [showConfirm]  低置信度 / 疑似重复 → 「要记下这一笔吗？」+ 记一笔 / 忽略，12 秒自动忽略。
 *
 * v1.3.4 视觉：仿咔皮记账的治愈奶油风——奶油白底、大圆角、同款小猪存钱罐 IP 形象、
 * 草莓粉金额、胶囊按钮。关键约束不变：
 * - 优先 `TYPE_ACCESSIBILITY_OVERLAY`：无障碍服务绑定的窗口**不需要悬浮窗权限**；
 * - 所有 addView / removeView 切主线程；
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
            title = "要记下这一笔吗？",
            detail = "${candidate.amountFen?.let { Money.format(it) }.orEmpty()} ${candidate.merchant}".trim(),
            confirmLabel = "记一笔",
            editLabel = null,
            onConfirm = onConfirm,
            onDiscard = onDiscard,
            note = "点一下就记好啦"
        )

    /** 自动入账成功：轻提示，不询问、不需要点击，3 秒消失。 */
    fun showSuccess(amountFen: Long, remark: String): Boolean = showInfo(
        title = "记下啦 ${Money.format(amountFen)}",
        detail = remark.ifBlank { "账单已存入账本" },
        note = "今天也有认真记账哦～",
        autoDismissMs = SUCCESS_VISIBLE_MS
    )

    /** 纯提示（已跳过重复、已忽略等）：3 秒消失。 */
    fun showInfo(
        title: String,
        detail: String,
        note: String = "稍等就好啦",
        autoDismissMs: Long = INFO_VISIBLE_MS
    ): Boolean = showConfirm(
        title = title,
        detail = detail,
        note = note,
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
        note: String = "",
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
                // 奶油风：浅色走「奶白 → 淡粉」渐变，深色保留深紫灰，确保对比度
                val textColor = (if (dark) DarkOnSurface else LightOnSurface).toArgb()
                val accent = (if (dark) PrimaryLight else Primary).toArgb()
                val amountColor = (if (dark) PrimaryLight else PrimaryDark).toArgb()
                val border = (if (dark) DarkOutline else LightOutline).toArgb()
                val noteColor = if (dark) Color.rgb(0xB8, 0xAE, 0xC4) else Color.rgb(0x9A, 0x86, 0x90)

                // 外层只负责左右留白，真正卡片是里面的大圆角奶油容器
                val container = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(12), dp(8))
                    clipChildren = false
                    clipToPadding = false
                }
                val card = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    // 奶油渐变：上浅下粉，比纯色更软更「可可爱爱」
                    background = GradientDrawable().apply {
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        colors = if (dark) {
                            intArrayOf(DarkSurface.toArgb(), Color.rgb(0x3A, 0x30, 0x42))
                        } else {
                            intArrayOf(Color.rgb(0xFF, 0xFC, 0xF8), Color.rgb(0xFF, 0xEF, 0xF3))
                        }
                        cornerRadius = dp(30).toFloat()
                        setStroke(dp(1), border)
                    }
                    elevation = dp(16).toFloat()
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }

                // 头部：圆形奶油粉托 + 同款小猪存钱罐 IP + 标题 / 暖心短句
                val header = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val avatar = FrameLayout(host).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (dark) Color.rgb(0x4A, 0x3A, 0x48) else Color.rgb(0xFF, 0xE4, 0xEC))
                        setStroke(dp(1), if (dark) border else Color.rgb(0xF7, 0xC9, 0xD6))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
                }
                avatar.addView(ImageView(host).apply {
                    setImageResource(R.drawable.ic_piggy_overlay)
                    layoutParams = FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
                    contentDescription = "小猪存钱罐"
                })
                header.addView(avatar)
                val textColumn = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = dp(12) }
                }
                textColumn.addView(TextView(host).apply {
                    text = title
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textColor)
                    maxLines = 2
                })
                if (note.isNotBlank()) {
                    textColumn.addView(TextView(host).apply {
                        text = note
                        textSize = 12f
                        setTextColor(noteColor)
                        setPadding(0, dp(4), 0, 0)
                        maxLines = 1
                    })
                }
                header.addView(textColumn)
                // 右上角小装饰，纯文字图形，不额外占位
                header.addView(TextView(host).apply {
                    text = "✦"
                    textSize = 14f
                    setTextColor(accent)
                    alpha = 0.75f
                    setPadding(dp(4), 0, dp(2), 0)
                })
                card.addView(header)

                if (detail.isNotBlank()) {
                    card.addView(TextView(host).apply {
                        text = detail
                        textSize = 21f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(amountColor)
                        setPadding(dp(4), dp(14), dp(4), dp(2))
                        letterSpacing = 0.02f
                    })
                }
                if (confirmLabel.isNotBlank()) {
                    val actions = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(16), 0, 0)
                    }
                    fun pill(label: String, primary: Boolean, block: () -> Unit) =
                        TextView(host).apply {
                            text = label
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            setTextColor(if (primary) Color.WHITE else accent)
                            background = GradientDrawable().apply {
                                cornerRadius = dp(999).toFloat()
                                if (primary) {
                                    // 主按钮也做一点渐变，比纯色更软
                                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                                    colors = intArrayOf(accent, (if (dark) PrimaryLight else PrimaryDark).toArgb())
                                } else {
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
                // 轻柔入场：从底部浮上来 + 淡入，比硬弹出来更可爱
                container.alpha = 0f
                container.translationY = dp(26).toFloat()
                container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(240L)
                    .start()
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

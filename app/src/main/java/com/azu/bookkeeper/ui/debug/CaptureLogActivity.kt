package com.azu.bookkeeper.ui.debug

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.azu.bookkeeper.core.BootTrace
import com.azu.bookkeeper.core.CaptureLog
import com.azu.bookkeeper.core.CrashLog
import com.azu.bookkeeper.core.SelfCheckGuard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 零依赖崩溃 / 日志查看页。
 *
 * 设计原则（重要）：这个页面**故意**不用 Compose、不用 Hilt、不碰截屏/OCR/数据库，
 * 只用最原始的 Activity + TextView。原因是自检页一旦因为依赖图、Compose 或 native
 * 问题崩掉，用户就彻底看不到任何信息；这里必须保证「只要能打开 App，就能打开它」。
 *
 * 显示内容：设备信息 → 最近一次崩溃栈 → 崩溃探针（最后执行到哪）→ 识屏日志。
 * 一键复制后可直接粘贴发给开发者定位。
 */
class CaptureLogActivity : Activity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootTrace.step("PAGE", "打开日志页")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "识屏诊断 · 日志查看"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.START
        }
        root.addView(title)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        toolbar.addView(button("复制全部") { copyAll() })
        toolbar.addView(button("刷新") { render() })
        toolbar.addView(button("清空") { clearAll() })
        toolbar.addView(button("返回") { finish() })
        root.addView(toolbar)

        val scroll = ScrollView(this)
        body = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        render()
    }

    private fun render() {
        body.text = runCatching { buildAll() }.getOrElse {
            "构建内容失败：${it.javaClass.simpleName}: ${it.message}"
        }
    }

    /** 拼装完整诊断文本；任何一段失败都只影响该段，不影响整页。 */
    private fun buildAll(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 设备 ===")
        sb.appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()

        sb.appendLine("=== 最近一次崩溃（Java 栈）===")
        sb.appendLine(CrashLog.last(this) ?: "（无记录：可能是 native 崩溃或被系统直接杀进程，看下面探针最后一行）")
        sb.appendLine()

        sb.appendLine("=== 崩溃探针（最后一行＝崩溃前最后执行到的步骤）===")
        sb.appendLine(BootTrace.dump(this, 200))
        sb.appendLine()

        sb.appendLine("=== 上次自检中断步骤 ===")
        sb.appendLine(SelfCheckGuard.lastInterruptedStep(this) ?: "（无：上次自检正常结束）")
        sb.appendLine()

        sb.appendLine("=== 识屏日志（主体）===")
        sb.appendLine(CaptureLog.export(this, 600))
        return sb.toString()
    }

    private fun copyAll() {
        runCatching {
            val text = buildAll()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("识屏诊断", text))
            Toast.makeText(this, "已复制全部（${text.length} 字），直接粘贴发送", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "复制失败：${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAll() {
        runCatching {
            CrashLog.clear(this)
            BootTrace.clear(this)
            SelfCheckGuard.clear(this)
            CaptureLog.clear(this)
        }
        Toast.makeText(this, "已清空全部记录（日志开关状态保留）", Toast.LENGTH_SHORT).show()
        BootTrace.step("PAGE", "清空记录")
        render()
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { runCatching { onClick() } }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4) }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

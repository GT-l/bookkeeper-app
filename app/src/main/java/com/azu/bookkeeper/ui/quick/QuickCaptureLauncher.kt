package com.azu.bookkeeper.ui.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.azu.bookkeeper.MainActivity
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.domain.model.BillQuickPrefill
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 四入口 Intent 归一化（增量架构 §4.2 / §4.3）。
 *
 * 所有入口（Shortcut / Widget / FGS 通知 / 悬浮球 / 抖音兜底）都构造同一个
 * [QuickCaptureRequest] 并携带在 Intent extra 中；MainActivity 在冷启动和热启动
 * （onNewIntent）时都调用 [resolve] 拿到契约，再统一转到 Screen.BillEdit(quickPrefill)。
 *
 * 使用 JSON 字符串而非 Parcelable：项目已以 kotlinx.serialization 承载路由参数，
 * 无需额外引入 kotlin-parcelize 插件；FLAG_IMMUTABLE 的 PendingIntent 传字符串同样安全。
 */
object QuickCaptureLauncher {

    const val EXTRA_REQUEST = "com.azu.bookkeeper.extra.QUICK_CAPTURE_REQUEST"

    private val json = Json { ignoreUnknownKeys = true }

    /** 构造拉起表单的 Intent；NEW_TASK 支持系统组件冷启动，SINGLE_TOP 保证热启动走 onNewIntent。 */
    fun intent(context: Context, request: QuickCaptureRequest): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_REQUEST, json.encodeToString(request))
            // Glance 对同一 Intent 的多个 action 会按 filterEquals 收敛；入口枚举保证唯一。
            setData(android.net.Uri.parse("bookkeeper://quick/${request.entry.name}"))
        }

    /** FGS 通知「记一笔」动作使用；FLAG_IMMUTABLE 兼容 Android 12+ 的 PendingIntent 要求。 */
    fun pendingIntent(
        context: Context,
        request: QuickCaptureRequest,
        requestCode: Int
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        intent(context, request),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 从任意入口 Intent 还原统一契约，兼容三种历史/系统载荷：
     * 1) 本 T7 的 JSON extra；
     * 2) 静态 Shortcut 的布尔 extra（shortcuts.xml 已声明）；
     * 3) T5 校对通知的 BillQuickPrefill JSON extra → 归一为 DOUYIN_FALLBACK。
     */
    fun resolve(intent: Intent?): QuickCaptureRequest? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_REQUEST)?.let { raw ->
            runCatching { json.decodeFromString<QuickCaptureRequest>(raw) }.getOrNull()
                ?.let { return it }
        }
        if (intent.getBooleanExtra(Constants.EXTRA_QUICK_ADD, false)) {
            return QuickCaptureRequest(entry = QuickEntry.APP_SHORTCUT)
        }
        intent.getStringExtra(Constants.EXTRA_VERIFY_PREFILL)?.let { raw ->
            runCatching { json.decodeFromString<BillQuickPrefill>(raw) }.getOrNull()?.let { prefill ->
                return QuickCaptureRequest.fromPrefill(prefill).copy(entry = QuickEntry.DOUYIN_FALLBACK)
            }
        }
        return null
    }
}

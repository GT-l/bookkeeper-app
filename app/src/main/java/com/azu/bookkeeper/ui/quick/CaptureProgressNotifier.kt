package com.azu.bookkeeper.ui.quick

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.NotifyGuard
import com.azu.bookkeeper.core.RomCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 识屏进度通知（摇一摇识屏的可视化反馈）。
 *
 * 灵动岛适配说明：真「灵动岛」是 iPhone 的交互；安卓对应形态是
 * 小米「焦点通知」、华为/荣耀「实况窗」、OPPO/一加/realme「流体云」。
 * 这些形态目前都没有公开的第三方 SDK——系统把它们做成**常驻通知的聚合展示**：
 * 只要 App 投递一条 ongoing 进度通知，支持的 ROM 会自动在屏幕顶部以岛状胶囊呈现，
 * 其余 ROM 正常显示通知栏进度条。所以这里的实现是全 ROM 兼容的最优解：
 * 有岛的系统自动上岛，没岛的看通知栏。
 *
 * 流程：CAPTURE(截屏) → RECOGNIZE(识别文字) → EXTRACT(提取扣款信息) →
 * RESULT(成功：金额+商户，点击确认) / FAILED(失败：点击手动记一笔)。
 */
@Singleton
class CaptureProgressNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class Stage(val text: String) {
        CAPTURE("正在截屏…"),
        RECOGNIZE("正在识别屏幕文字…"),
        EXTRACT("正在提取扣款信息…")
    }

    init {
        ensureChannel()
    }

    /** 阶段进度：ongoing + 模糊进度条，支持焦点通知/流体云/实况窗的 ROM 自动聚合成岛。 */
    fun showStage(stage: Stage) {
        post(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle("识屏记账")
                .setContentText(stage.text)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    /** 成功：展示识别到的金额与商户，点击直达表单确认。 */
    fun showResult(amountFen: Long?, merchant: String, contentIntent: PendingIntent) {
        val amountText = amountFen?.let { Money.format(it) } ?: "未识别到金额"
        val title = if (merchant.isNotBlank()) "$merchant $amountText" else amountText
        post(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle("识屏完成 · 点击确认记账")
                .setContentText(title)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    /** 已自动记账：只报结果，不再要求用户确认。 */
    fun showRecorded(amountFen: Long, merchant: String, contentIntent: PendingIntent?) {
        val amountText = Money.format(amountFen)
        val text = if (merchant.isNotBlank()) "$merchant · 已自动记入账本" else "已自动记入账本"
        post(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle("记账成功 $amountText")
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true)
                .apply { contentIntent?.let { setContentIntent(it) } }
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    /** 失败：标题直接写明失败原因，点击手动记一笔（失败时同时会自动打开表单）。 */
    fun showFailed(contentIntent: PendingIntent, reason: com.azu.bookkeeper.core.CaptureFailReason? = null) {
        val title = reason?.let { "识屏失败：${it.title}" } ?: "识屏失败 · 点击手动记一笔"
        val text = reason?.advice ?: "截屏授权或识别未完成，可手动输入金额"
        post(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    /** 没有截屏授权：引导用户点通知去授权（后台无法直接弹系统的授权对话框）。 */
    fun showNeedAuth(authIntent: PendingIntent) {
        post(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle("识屏需要截屏授权")
                .setContentText("点这里授权，授权后会自动继续识屏")
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(authIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun post(notification: android.app.Notification) {
        // 统一守卫：无通知权限时静默跳过，不影响识屏主流程
        NotifyGuard.safeNotify(context, NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val name = if (RomCompat.isFocusIslandCapable()) {
            "识屏进度（顶部胶囊/通知栏）"
        } else {
            "识屏进度"
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private companion object {
        const val CHANNEL_ID = "capture_progress"
        const val NOTIFICATION_ID = 1021
    }
}

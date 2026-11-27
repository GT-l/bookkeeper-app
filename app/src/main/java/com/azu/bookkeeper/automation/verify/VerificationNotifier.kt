package com.azu.bookkeeper.automation.verify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.azu.bookkeeper.MainActivity
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.BillQuickPrefill
import com.azu.bookkeeper.domain.model.BillSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 低置信度校对 / 自动入账结果通知（锁屏可见的高优先级兜底档）。 */
@Singleton
class VerificationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {

    init {
        createChannels()
    }

    /** 校对通知：点击携带预填 JSON 直达 BillEdit，用户改写后由表单按 QUICK_MANUAL 落库。 */
    fun showVerify(candidate: com.azu.bookkeeper.domain.model.CaptureCandidate) {
        val prefill = BillQuickPrefill(
            amountFen = candidate.amountFen,
            merchant = candidate.merchant,
            billTime = candidate.billTime,
            categoryId = candidate.categoryId,
            accountId = candidate.accountId,
            source = BillSource.QUICK_MANUAL,
            fromCaptureEventId = candidate.eventId
        )
        val encoded = json.encodeToString(BillQuickPrefill.serializer(), prefill)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.EXTRA_VERIFY_PREFILL, encoded)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            candidate.eventId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val amountText = candidate.amountFen?.let { Money.format(it) }.orEmpty()
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_VERIFY)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("确认这笔账单？")
            .setContentText("$amountText ${candidate.merchant} · 点击确认或修改")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notifySafe(Constants.VERIFY_NOTIFICATION_ID, notification)
    }

    /**
     * 极端兜底：仅在连无障碍服务都没有、悬浮窗也不可用时发一条**会自己消失**的横幅。
     * v1.3.1：不再发常驻「确认这笔账单？」通知——用户要求所有反馈轻打扰，
     * 真正的确认走屏幕底部卡片（TYPE_ACCESSIBILITY_OVERLAY 不需要额外权限）。
     */
    fun showTransientVerify(amountFen: Long?, merchant: String, duplicate: Boolean) {
        val amountText = amountFen?.let { Money.format(it) }.orEmpty()
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_VERIFY)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle(if (duplicate) "这笔好像记过了" else "识别到一笔支出")
            .setContentText("$amountText ${merchant}".trim())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(8_000L)
            .setAutoCancel(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() and 0x7fffffffL).toInt(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            ))
            .build()
        notifySafe(Constants.VERIFY_NOTIFICATION_ID, notification)
    }

    /** 自动入账成功提示（低打扰，DEFAULT 优先级）。 */
    fun showAutoBill(amountFen: Long, merchant: String) {
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_AUTO_CAPTURE)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("已自动记一笔")
            .setContentText("${Money.format(amountFen)} ${merchant}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notifySafe(Constants.AUTO_BILL_NOTIFICATION_ID, notification)
    }

    private fun notifySafe(id: Int, notification: android.app.Notification) {
        // 统一守卫：先查权限再投递（部分 OEM ROM 无权限时阻塞主线程），投递异常吞掉
        com.azu.bookkeeper.core.NotifyGuard.safeNotify(context, id, notification)
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channels = listOf(
            Constants.CHANNEL_VERIFY to context.getString(R.string.notification_channel_verify),
            Constants.CHANNEL_AUTO_CAPTURE to context.getString(R.string.notification_channel_auto_capture)
        )
        channels.forEach { (id, name) ->
            if (manager.getNotificationChannel(id) == null) {
                val importance = if (id == Constants.CHANNEL_VERIFY) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_DEFAULT
                }
                manager.createNotificationChannel(NotificationChannel(id, name, importance))
            }
        }
    }
}

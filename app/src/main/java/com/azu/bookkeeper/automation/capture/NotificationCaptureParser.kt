package com.azu.bookkeeper.automation.capture

import android.service.notification.StatusBarNotification
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.domain.model.CaptureType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知路解析：包名 + 完成态关键词过滤，多字段拼接后交 FieldExtractor。
 *
 * v1.3.0：微信支付凭证常把金额放在 android.bigText / subText，旧实现只读 title + text，
 * 于是「通知到了但没有金额」；这里把 bigText / subText / infoText / ticker 一并纳入，
 * 并复用页面识别器的扩展词表（含「微信支付」「已完成」）。
 */
@Singleton
class NotificationCaptureParser @Inject constructor() {

    fun parse(sbn: StatusBarNotification, businessTag: BusinessTag): CaptureEvent? {
        val rawPackage = sbn.packageName.orEmpty()
        if (!PaymentScreenRecognizer.isPaymentPackage(rawPackage)) return null
        val packageName = PaymentScreenRecognizer.normalizePackage(rawPackage)
        val extras = sbn.notification.extras ?: return null

        val parts = mutableListOf<String>()
        listOf(
            "android.title", "android.text", "android.bigText",
            "android.subText", "android.infoText", "android.summaryText"
        ).forEach { key ->
            val value = runCatching { extras.getCharSequence(key)?.toString().orEmpty() }.getOrDefault("")
            if (value.isNotBlank() && value !in parts) parts.add(value)
        }
        val ticker = runCatching { sbn.notification.tickerText?.toString().orEmpty() }.getOrDefault("")
        if (ticker.isNotBlank() && ticker !in parts) parts.add(ticker)
        val combined = parts.joinToString("\n")

        if (combined.isBlank() || !PaymentScreenRecognizer.isSuccessText(combined)) return null
        return CaptureEvent(
            eventId = sbn.id.toLong() and 0x7fffffff or (sbn.postTime shl 32),
            captureType = CaptureType.NOTIFICATION,
            rawText = combined,
            billTime = sbn.postTime,
            packageName = packageName,
            businessTag = businessTag,
            screenType = PaymentScreen.SUCCESS.name
        )
    }
}

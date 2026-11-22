package com.azu.bookkeeper.automation.capture

import android.service.notification.StatusBarNotification
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.domain.model.CaptureType
import javax.inject.Inject
import javax.inject.Singleton

/** 通知路解析：包名 + 关键词过滤，标题/正文拼接后交 FieldExtractor。 */
@Singleton
class NotificationCaptureParser @Inject constructor() {

    fun parse(sbn: StatusBarNotification, businessTag: BusinessTag): CaptureEvent? {
        val packageName = sbn.packageName
        if (packageName !in Constants.PAYMENT_PACKAGES) return null
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val combined = "$title\n$text"
        if (title.isBlank() && text.isBlank()) return null
        if (Constants.PAYMENT_SUCCESS_KEYWORDS.none { combined.contains(it) }) return null
        return CaptureEvent(
            eventId = sbn.id.toLong() and 0x7fffffff or (sbn.postTime shl 32),
            captureType = CaptureType.NOTIFICATION,
            rawText = combined,
            billTime = sbn.postTime,
            packageName = packageName,
            businessTag = businessTag
        )
    }
}

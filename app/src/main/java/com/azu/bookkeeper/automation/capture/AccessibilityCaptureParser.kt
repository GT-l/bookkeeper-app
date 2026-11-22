package com.azu.bookkeeper.automation.capture

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.domain.model.CaptureType
import javax.inject.Inject
import javax.inject.Singleton

/** 读屏解析：递归收集文字节点，仅在完成态关键词命中时产出候选事件。 */
@Singleton
class AccessibilityCaptureParser @Inject constructor() {

    /** 完成态判定；"确认支付"/"输入密码"等中间态不触发。 */
    fun isPaymentComplete(text: String): Boolean =
        Constants.PAYMENT_SUCCESS_KEYWORDS.any { text.contains(it) }

    fun collectText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val builder = StringBuilder()
        collect(root, builder, depth = 0)
        return builder.toString()
    }

    fun buildEvent(
        eventId: Long,
        packageName: String,
        text: String,
        eventTime: Long,
        businessTag: BusinessTag
    ): CaptureEvent = CaptureEvent(
        eventId = eventId,
        captureType = CaptureType.ACCESSIBILITY_TEXT,
        rawText = text,
        billTime = eventTime,
        packageName = packageName,
        businessTag = businessTag
    )

    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (label != null && out.length < MAX_TEXT_LENGTH) out.append(label).append('\n')
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collect(child, out, depth + 1)
                child.recycle()
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_TEXT_LENGTH = 4000
    }
}

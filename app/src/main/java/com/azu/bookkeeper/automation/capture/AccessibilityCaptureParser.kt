package com.azu.bookkeeper.automation.capture

import android.view.accessibility.AccessibilityNodeInfo
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.domain.model.CaptureType
import javax.inject.Inject
import javax.inject.Singleton

/** 读屏解析：递归收集文字节点，仅在完成态关键词命中时产出候选事件。 */
@Singleton
class AccessibilityCaptureParser @Inject constructor() {

    /** 完成态判定；"确认支付"/"输入密码"等中间态不触发。词表统一交给页面识别器维护。 */
    fun isPaymentComplete(text: String): Boolean =
        PaymentScreenRecognizer.isSuccessText(text)

    /**
     * 收集整页可见文字。
     *
     * v1.3.0：微信支付结果页常用自绘 View / WebView，text 与 contentDescription 分散在不同节点，
     * 旧实现二选一 + 深度 12 会漏掉关键行；这里两个字段都收（去重），深度放宽到 24。
     */
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
        businessTag: BusinessTag,
        screen: PaymentScreen = PaymentScreen.UNKNOWN
    ): CaptureEvent = CaptureEvent(
        eventId = eventId,
        captureType = CaptureType.ACCESSIBILITY_TEXT,
        rawText = text,
        billTime = eventTime,
        packageName = packageName,
        businessTag = businessTag,
        screenType = screen.name
    )

    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH || out.length >= MAX_TEXT_LENGTH) return
        val primary = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
        val secondary = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")
        if (primary.isNotEmpty()) out.append(primary).append('\n')
        if (secondary.isNotEmpty() && secondary != primary) out.append(secondary).append('\n')
        val children = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until children) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collect(child, out, depth + 1)
            runCatching { child.recycle() }
        }
    }

    private companion object {
        const val MAX_DEPTH = 24
        const val MAX_TEXT_LENGTH = 6000
    }
}

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
     * v1.3.1：实现统一委托 [ScreenTextCollector] —— 与摇一摇 readScreenSnapshot()
     * 用同一套采集/去重/限长逻辑，且不再回收共享子节点（这是「只识别到『支付』」的修复点）。
     */
    fun collectText(root: AccessibilityNodeInfo?): String =
        ScreenTextCollector.collect(root)

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
}

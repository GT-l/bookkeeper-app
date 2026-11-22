package com.azu.bookkeeper.automation.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.azu.bookkeeper.automation.capture.AccessibilityCaptureParser
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 无障碍读屏捕获（主架构 §5.1 主路径，AC-4）：
 * 仅对白名单包名、完成态关键词触发；1.5s 去抖 + eventId 幂等避免连发重复抓取。
 * 抖音包名作为强信号打 businessTag=DOUYIN，但不改 captureType。
 */
@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {

    @Inject lateinit var parser: AccessibilityCaptureParser
    @Inject lateinit var tagger: DouyinBusinessTagger
    @Inject lateinit var coordinator: CaptureCoordinator
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private val scope = CoroutineScope(SupervisorJob())
    private var lastEventAt = 0L
    private var lastPackage = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName !in Constants.PAYMENT_PACKAGES) return

        val now = System.currentTimeMillis()
        if (packageName == lastPackage && now - lastEventAt < Constants.CAPTURE_DEBOUNCE_MS) return

        val root = event.source ?: rootInActiveWindow ?: return
        val text = parser.collectText(root)
        if (text.isBlank() || !parser.isPaymentComplete(text)) return

        lastEventAt = now
        lastPackage = packageName
        val eventId = generateEventId(packageName, text)
        val businessTag: BusinessTag = tagger.tag(packageName, text)
        val captureEvent = parser.buildEvent(
            eventId = eventId,
            packageName = packageName,
            text = text,
            eventTime = now,
            businessTag = businessTag
        )
        scope.launch {
            withContext(ioDispatcher) { coordinator.onCapture(captureEvent) }
        }
    }

    /** 稳定 eventId：包名 + 文本哈希 + 秒，避免同一完成页连发重复建单（与去重键一致）。 */
    private fun generateEventId(packageName: String, text: String): Long {
        val base = (packageName + text).hashCode().toLong() and 0x7fffffffL
        return (base xor (System.currentTimeMillis() / 1000L)) and Long.MAX_VALUE
    }

    override fun onInterrupt() = Unit
}

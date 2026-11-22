package com.azu.bookkeeper.automation.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.NotificationCaptureParser
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 通知路捕获（主架构 §5.1 备选 / PRD 22.3f）：包名 + 完成态关键词过滤后交解析器。
 * 时间取 postTime，与读屏路秒级去重键一致；抖音包名打标但不改 captureType（AC-12）。
 */
@AndroidEntryPoint
class PaymentNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var parser: NotificationCaptureParser
    @Inject lateinit var tagger: DouyinBusinessTagger
    @Inject lateinit var coordinator: CaptureCoordinator
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    private val scope = CoroutineScope(SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // 忽略常驻 / 进行中通知，只处理一次性支付结果通知
        if (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return
        val businessTag: BusinessTag = tagger.tag(sbn.packageName, "")
        val event = parser.parse(sbn, businessTag) ?: return
        scope.launch {
            withContext(ioDispatcher) { coordinator.onCapture(event) }
        }
    }
}

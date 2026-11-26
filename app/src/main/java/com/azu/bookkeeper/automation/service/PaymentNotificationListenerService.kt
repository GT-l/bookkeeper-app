package com.azu.bookkeeper.automation.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.NotificationCaptureParser
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.CaptureLog
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 通知路捕获（主架构 §5.1 备选 / PRD 22.3f）：包名 + 完成态关键词过滤后交解析器。
 * 时间取 postTime，与读屏路秒级去重键一致；抖音包名打标但不改 captureType（AC-12）。
 *
 * 稳定性要点（与无障碍服务同教训）：
 * - **不用 @AndroidEntryPoint 字段注入**：注入发生在 onCreate 之前，任一依赖构造失败
 *   （数据库/Keystore 未就绪）就会让系统侧服务直接崩溃，ROM 可能连带关掉通知使用权；
 *   改为惰性 EntryPoint，失败只影响本次解析；
 * - 所有回调与协程全链路 runCatching：通知解析/写库异常绝不能冒到系统；
 * - 解析与落库都在 IO 线程，避免主线程卡顿被系统判 ANR。
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    private val deps by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, NotificationDepsEntryPoint::class.java
        )
    }

    private val scope = CoroutineScope(SupervisorJob())

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.w("notification listener connected")
        runCatching { CaptureLog.i(applicationContext, "NOTIF", "通知使用权已连接") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Timber.w("notification listener disconnected")
        runCatching { CaptureLog.w(applicationContext, "NOTIF", "通知使用权被断开") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        runCatching { handleNotification(sbn) }.onFailure { t ->
            Timber.w(t, "onNotificationPosted failed (service kept alive)")
            runCatching { CaptureLog.w(applicationContext, "NOTIF", "处理通知异常", t) }
        }
    }

    private fun handleNotification(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // 忽略常驻 / 进行中通知，只处理一次性支付结果通知
        if (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return

        val entry = deps
        val tagger: DouyinBusinessTagger = runCatching { entry.tagger() }.getOrNull() ?: return
        val parser: NotificationCaptureParser = runCatching { entry.parser() }.getOrNull() ?: return
        val coordinator: CaptureCoordinator = runCatching { entry.coordinator() }.getOrNull() ?: return
        val io: CoroutineDispatcher = runCatching { entry.ioDispatcher() }.getOrNull()
            ?: kotlinx.coroutines.Dispatchers.IO

        val businessTag: BusinessTag = runCatching { tagger.tag(sbn.packageName, "") }
            .getOrDefault(BusinessTag.NONE)
        val event = runCatching { parser.parse(sbn, businessTag) }.getOrNull() ?: return

        scope.launch {
            runCatching { withContext(io) { coordinator.onCapture(event) } }
                .onFailure { t ->
                    Timber.w(t, "notification capture failed (service kept alive)")
                    runCatching { CaptureLog.w(applicationContext, "NOTIF", "通知识别写库失败", t) }
                }
        }
    }
}

/** 通知服务的惰性依赖入口：字段注入失败会让系统侧服务崩溃，这里改为按需取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationDepsEntryPoint {
    fun parser(): NotificationCaptureParser
    fun tagger(): DouyinBusinessTagger
    fun coordinator(): CaptureCoordinator
    @IoDispatcher fun ioDispatcher(): CoroutineDispatcher
}

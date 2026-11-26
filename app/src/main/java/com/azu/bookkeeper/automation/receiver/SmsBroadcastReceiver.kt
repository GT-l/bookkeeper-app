package com.azu.bookkeeper.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.CaptureEvent
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 银行扣款短信接收器（增量架构 §2.2 / T6）。
 *
 * 生命周期：Manifest 静态注册、无高 priority；权限未授予 / 短信开关关闭 → 静默 return；
 * 正文只在本方法栈帧内解析为结构化摘要后交给 CaptureCoordinator，绝不写日志正文。
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    private val receiverScope = CoroutineScope(SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // 运行时权限校验：未授权直接静默返回，不崩溃
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?: return

        // 惰性取依赖：字段注入一旦失败会直接崩在广播里（短信到达时闪退）
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, SmsDepsEntryPoint::class.java)
        }.getOrNull() ?: return
        val parser = runCatching { entry.parser() }.getOrNull() ?: return
        val coordinator = runCatching { entry.coordinator() }.getOrNull() ?: return
        val automationSettings = runCatching { entry.automationSettings() }.getOrNull() ?: return
        val settings = runCatching { entry.settings() }.getOrNull() ?: return
        val accountRepository = runCatching { entry.accountRepository() }.getOrNull() ?: return
        val json = runCatching { entry.json() }.getOrNull() ?: return

        // onReceive 在主线程且只有 ~10s：解析为纯正则（<5ms），用 goAsync 把结构化摘要异步入队，
        // 正文不出本方法栈帧；不把原文带入协程作用域。
        val pending = goAsync()
        receiverScope.launch {
            try {
                if (!automationSettings.enabled.first() || !automationSettings.smsEnabled.first()) {
                    return@launch
                }
                val bookId = settings.defaultBookId.first()
                val accounts: List<Account> = if (bookId > 0L) {
                    accountRepository.observeAll(bookId).first()
                } else {
                    emptyList()
                }
                messages.forEach { message ->
                    val sender = message.displayOriginatingAddress.orEmpty()
                    val body = message.displayMessageBody.orEmpty()
                    val arrivalMs = message.timestampMillis.takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    val parsed = parser.parse(sender, body, arrivalMs, accounts) ?: return@forEach
                    val summary = parser.toSummary(parsed, parsed.bankShort)
                    val businessTag = if (parsed.douyinHit) BusinessTag.DOUYIN else BusinessTag.NONE
                    val event = CaptureEvent(
                        eventId = generateEventId(sender, arrivalMs, parsed.amountFen),
                        captureType = CaptureType.SMS,
                        rawText = json.encodeToString(SmsSummary.serializer(), summary),
                        billTime = parsed.billTime,
                        packageName = "sms:${summary.bank}",
                        businessTag = businessTag,
                        timeSource = parsed.timeSource
                    )
                    coordinator.onCapture(event)
                    // 隐私：日志仅打印 eventId / bank / amountFen / timeSource，绝不打印正文
                    Timber.d(
                        "sms captured eventId=%d bank=%s amountFen=%d timeSrc=%s",
                        event.eventId, parsed.bankShort, parsed.amountFen, parsed.timeSource.name
                    )
                }
            } catch (throwable: Throwable) {
                Timber.w(throwable, "sms capture failed")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 稳定 eventId：sender + 到达秒 + 金额，保证同一条短信重复投递幂等，
     * 与 Constants.CHANNEL_* 无关。使用 Long 折叠避免溢出。
     */
    private fun generateEventId(sender: String, arrivalMs: Long, amountFen: Long): Long {
        val base = sender.hashCode().toLong() and 0x7fffffffL
        val seconds = arrivalMs / 1000L
        return (base xor (seconds shl 20) xor amountFen) and Long.MAX_VALUE
    }

    companion object {
        /** 设置页用 PackageManager 启停本组件（静态 receiver 唯一可靠的一键停用方式）。 */
        fun setEnabled(context: Context, enabled: Boolean) {
            val component = android.content.ComponentName(context, SmsBroadcastReceiver::class.java)
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                component, state, PackageManager.DONT_KILL_APP
            )
        }
    }
}

/** 短信识别的惰性依赖入口（避免广播接收器在注入阶段崩溃）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsDepsEntryPoint {
    fun parser(): SmsCaptureParser
    fun coordinator(): CaptureCoordinator
    fun automationSettings(): AutomationSettingsDataStore
    fun settings(): SettingsRepository
    fun accountRepository(): AccountRepository
    fun json(): Json
}

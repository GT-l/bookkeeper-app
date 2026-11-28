package com.azu.bookkeeper.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.automation.ocr.OcrIpcClient
import com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer
import com.azu.bookkeeper.automation.permission.AccessibilityAnomaly
import com.azu.bookkeeper.automation.permission.AccessibilityHealth
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.core.CaptureChannel
import com.azu.bookkeeper.core.CaptureLog
import com.azu.bookkeeper.core.CaptureDiagnostics
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureReport
import com.azu.bookkeeper.core.CrashLog
import com.azu.bookkeeper.core.DiagnosticStep
import com.azu.bookkeeper.core.SelfCheckGuard
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 自检项：ok=false 时 detail 是失败原因，advice 是修复建议。 */
data class CheckItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val advice: String = ""
)

/**
 * 识屏自检 ViewModel：把「通道 → 截屏 → OCR → 提取」逐项跑一遍，
 * 每一项**跑完立即推送**到界面（不是等全部结束才显示），过程中有当前步骤提示，
 * 这样即使某一步把进程干掉，最后停留的步骤也能被 SelfCheckGuard 记下来。
 */
@HiltViewModel
class CaptureDiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: com.azu.bookkeeper.automation.capture.CaptureChannels,
    private val screenCapture: ScreenCaptureManager,
    private val ocrIpc: OcrIpcClient,
    private val analyzer: PaymentTextAnalyzer,
    private val permissionGuide: PermissionGuide,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {

    data class UiState(
        val items: List<CheckItem> = emptyList(),
        val running: Boolean = false,
        /** 当前正在执行的步骤，界面用「监督指示」显示。 */
        val currentStep: String = "",
        val last: CaptureReport? = null,
        val textPreview: String = "",
        /** 无障碍被系统关闭 / 解绑时的一次性异常，调试页弹窗展示。 */
        val accessibilityAnomaly: AccessibilityAnomaly? = null,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        // 构造阶段绝不能抛：ViewModel 创建失败会直接让「点自检」变成闪退，
        // 而用户完全看不到原因。这里兜住并把异常显示到页面上。
        com.azu.bookkeeper.core.BootTrace.step("VM", "CaptureDiagnosticsViewModel.init")
        runCatching { refresh() }.onFailure { t ->
            com.azu.bookkeeper.core.BootTrace.step(
                "VM", "init 失败", "${t.javaClass.simpleName}: ${t.message}"
            )
            _state.update {
                it.copy(
                    items = listOf(
                        CheckItem(
                            "自检页初始化异常",
                            false,
                            "${t.javaClass.simpleName}: ${t.message}",
                            "把该信息连同崩溃日志一起反馈"
                        )
                    )
                )
            }
        }
    }

    /** 刷新环境检查项（通知权限 / 截屏通道）与最近一次留痕。 */
    fun refresh() {
        val last = runCatching { CaptureDiagnostics.last(context) }.getOrNull()
        val interrupted = runCatching { SelfCheckGuard.lastInterruptedStep(context) }.getOrNull()
        val anomaly = runCatching { AccessibilityHealth.detect(context, permissionGuide) }.getOrNull()
        _state.update {
            it.copy(
                last = last,
                textPreview = last?.textPreview.orEmpty(),
                accessibilityAnomaly = anomaly,
                items = buildList {
                    runCatching { crashCheck() }.getOrNull()?.let { add(it) }
                    if (interrupted != null) add(interruptedCheck(interrupted))
                    // 每项独立兜底：某一项读取系统状态失败，只让这一项变红，绝不拖垮整页
                    add(safeItem("通知权限") { notificationCheck() })
                    add(safeItem("无障碍服务开关") { accessibilitySwitchCheck() })
                    add(safeItem("无障碍截屏通道") { accessibilityChannelCheck() })
                    add(safeItem("媒体投影通道") { projectionChannelCheck() })
                }
            )
        }
    }

    fun dismissAccessibilityAnomaly() = _state.update { it.copy(accessibilityAnomaly = null) }

    /** 调试页「复制 log」：无障碍状态 + 保活步骤 + 最近识屏日志。 */
    fun accessibilityReportText(): String = AccessibilityHealth.reportText(context, permissionGuide)

    fun stableRunGuideText(): String = AccessibilityHealth.stableRunGuideText()

    /** 单项检查兜底：异常转成一条红色检查项，而不是让页面崩溃。 */
    private fun safeItem(name: String, block: () -> CheckItem): CheckItem =
        runCatching { block() }.getOrElse { t ->
            CheckItem(
                name = name,
                ok = false,
                detail = "检查项执行异常：${t.javaClass.simpleName}: ${t.message}",
                advice = "把这条与崩溃日志一起反馈"
            )
        }

    /**
     * 最近一次 Java 崩溃（全局捕获器写盘）。用户报告「一点就闪退」时，
     * 这里会直接给出异常类型与栈首行——这是最硬的证据。
     */
    private fun crashCheck(): CheckItem? {
        val raw = CrashLog.last(context) ?: return null
        val time = raw.lineSequence().firstOrNull { it.startsWith("time=") }
            ?.removePrefix("time=")?.toLongOrNull()
        val process = raw.lineSequence().firstOrNull { it.startsWith("process=") }?.removePrefix("process=").orEmpty()
        val exceptionLine = raw.lineSequence()
            .firstOrNull { it.contains("Exception") || it.contains("Error") || it.contains("Throwable") }
            ?.trim()
            ?.take(180)
            .orEmpty()
        val timeText = time?.let {
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "未知时间"
        return CheckItem(
            name = "最近一次崩溃记录",
            ok = false,
            detail = "$timeText${if (process.isNotBlank()) " · $process" else ""}：" +
                (exceptionLine.ifBlank { "已记录，详情见「复制诊断信息」" }),
            advice = "点「复制诊断信息」把完整栈发我；点「清除记录」可清空"
        )
    }

    /** 上次自检过程中进程被杀：这是 native 崩溃/OOM 的直接证据，必须显式展示。 */
    private fun interruptedCheck(step: String): CheckItem = CheckItem(
        name = "上次自检被系统终止",
        ok = false,
        detail = "在步骤「$step」时进程被终止（native 崩溃或内存不足）。OCR 已隔离到独立进程，" +
            "主程序未受影响；自检结果已改为分步落盘，可继续跑看是否还卡在同一步。",
        advice = "再点一次「开始自检」；若仍停在该步骤，把「复制诊断信息」的内容反馈"
    )

    private fun notificationCheck(): CheckItem {
        val granted = permissionGuide.hasNotificationPermission()
        return CheckItem(
            name = "通知权限",
            ok = granted,
            detail = if (granted) "已授予（识屏进度通知可见）" else "未授予（识屏进度和结果通知不会显示，摇一摇改用 Toast 提醒）",
            advice = if (granted) "" else "到系统设置 → 通知，允许「少花点儿」通知"
        )
    }

    /**
     * 无障碍服务开关体检：把「系统开关」和「服务是否真的连上」分开看。
     * OrangeOS / ColorOS 最常见的是开关还亮着、服务已被 ROM 解绑；
     * 也可能是系统直接把开关关了。两种都要让用户能一键复制 log。
     */
    private fun accessibilitySwitchCheck(): CheckItem {
        val enabled = permissionGuide.isAccessibilityEnabled()
        val everConnected = permissionGuide.accessibilityServiceEverConnected()
        val connected = channels.accessibilityServiceConnected()
        val lastConnected = permissionGuide.accessibilityLastConnectedAt()
        val lastDisconnected = permissionGuide.accessibilityLastDisconnectedAt()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val connectedText = if (lastConnected > 0L) fmt.format(java.util.Date(lastConnected)) else "从未连上"
        val disconnectedText = if (lastDisconnected > 0L) fmt.format(java.util.Date(lastDisconnected)) else "无记录"
        val ok = enabled && connected
        val detail = when {
            !enabled && everConnected -> "系统开关已关闭（最后连接 $connectedText，最后断开 $disconnectedText）"
            !enabled -> "从未开启过无障碍服务"
            !connected -> "系统开关还开着，但服务未连接（最后连接 $connectedText，最后断开 $disconnectedText）"
            else -> "开关开启且服务已连接（最后连接 $connectedText）"
        }
        val advice = if (ok) "" else {
            "设置 → 无障碍 → 少花点儿，关闭再重新打开；" +
                "OrangeOS / ColorOS 再把 App 锁定后台，并用 Shevery、Shizuku 等工具的「无障碍服务锁定」锁住本服务。"
        }
        return CheckItem("无障碍服务开关", ok, detail, advice)
    }

    /** 通道 1：无障碍截屏（API 30+，免录屏授权，首选）。 */
    private fun accessibilityChannelCheck(): CheckItem {
        val connected = channels.accessibilityServiceConnected()
        val ready = channels.accessibilityReady()
        val disabled = channels.isChannelDisabled(CaptureChannel.ACCESSIBILITY)
        val optIn = channels.accessibilityEnabledByUser()
        val capable = channels.accessibilityCapable()
        val lastConnected = permissionGuide.accessibilityLastConnectedAt()
        val lastDisconnected = permissionGuide.accessibilityLastDisconnectedAt()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val connectedText = if (lastConnected > 0L) fmt.format(java.util.Date(lastConnected)) else "从未连上"
        val killedAfterConnect = lastDisconnected > lastConnected && lastConnected > 0L
        // v1.3.12：服务声明恢复 canTakeScreenshot。如果这里显示「缺少截屏能力」，
        // 说明服务还是按旧配置绑定的 → 关掉再打开一次无障碍服务即可重新注册。
        val capabilityMissing = connected && !capable

        val detail = when {
            disabled -> "已自动停用（上次使用该通道时进程崩溃） · 最后连接 $connectedText"
            !optIn && capable -> "服务已连且具备截屏能力，但开关被手动关闭，当前走媒体投影 · $connectedText"
            ready -> "可用（免录屏授权，读不到节点时自动截图 OCR） · 最后连接 $connectedText"
            !connected && killedAfterConnect -> "服务未连接 · 上次 $connectedText 连上后被系统解绑"
            !connected -> "服务未连接 · 最后连接 $connectedText"
            capabilityMissing -> "服务已连但缺少截屏能力（旧的绑定配置） · $connectedText"
            else -> "服务已连但截屏能力未注册 · $connectedText"
        }
        val advice = when {
            disabled -> "点下方「恢复截屏通道」可重新启用（若再次崩溃会再次停用）"
            !optIn && capable -> "可在自动化设置重新打开「无障碍截屏」"
            ready -> ""
            !connected -> "设置 → 无障碍 → 少花点儿，打开服务；打开后回到本页应显示「可用」"
            capabilityMissing -> "关闭无障碍服务再重新打开一次，让 canTakeScreenshot 能力重新注册"
            else -> "点下方「恢复截屏通道」，仍不行就重开一次无障碍服务"
        }
        return CheckItem("无障碍截屏通道", ready, detail, advice)
    }

    /** 通道 2：媒体投影（需系统录屏授权，覆盖 Android 8~10 与无障碍不可用场景）。 */
    private fun projectionChannelCheck(): CheckItem {
        val active = channels.projectionReady()
        val disabled = channels.isChannelDisabled(CaptureChannel.PROJECTION)
        return CheckItem(
            name = "媒体投影通道",
            ok = active,
            detail = when {
                disabled -> "已自动停用（上次使用该通道时进程崩溃）"
                active -> "会话有效"
                else -> "无会话（重启 App 后失效，Android 14+ 规则）"
            },
            advice = when {
                disabled -> "点下方「恢复截屏通道」可重新启用"
                active -> ""
                else -> "点下方「授权截屏」完成一次系统录屏授权"
            }
        )
    }

    /**
     * 节点读屏体检：这是识屏的主路径（钱迹等主流记账 App 的做法）。
     * 读的是本页自身的文字——只要能读到，就证明「摇一摇识屏」的取文本环节是通的。
     */
    private suspend fun readScreenCheck(): CheckItem {
        val connected = channels.accessibilityServiceConnected()
        if (!connected) {
            return CheckItem(
                name = "读屏节点（识屏主路径）",
                ok = false,
                detail = "无障碍服务未连接，节点读屏不可用",
                advice = "设置 → 无障碍 → 少花点儿，打开服务后重新检查"
            )
        }
        val snapshot = runCatching {
            withContext(io) { com.azu.bookkeeper.automation.service.PaymentAccessibilityService.readScreenSnapshot() }
        }.getOrNull()
        return if (snapshot == null) {
            CheckItem(
                name = "读屏节点（识屏主路径）",
                ok = false,
                detail = "服务已连接，但当前页面没有读到任何文本节点",
                advice = "停在支付宝/微信支付结果页再试一次；本页若为纯图片也会读不到"
            )
        } else {
            CheckItem(
                name = "读屏节点（识屏主路径）",
                ok = true,
                detail = "${snapshot.nodeCount} 个文本节点 / ${snapshot.text.length} 字 / " +
                    "页面=${snapshot.packageName.ifBlank { "未知" }}",
                advice = ""
            )
        }
    }

    /** 恢复被熔断的截屏通道（用户手动确认后再试）。 */
    fun resetBreakers() {
        channels.resetBreakers()
        _state.update { it.copy(message = "已恢复截屏通道，可重新尝试真实截屏测试") }
        refresh()
    }

    fun createCaptureIntent(): Intent? = screenCapture.createCaptureIntent()

    fun onAuthResult(resultCode: Int, data: Intent?) {
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            _state.update { it.copy(message = "已取消授权") }
            refresh()
            return
        }
        val ok = screenCapture.startSession(resultCode, data) {}
        _state.update { it.copy(message = if (ok) "截屏会话已建立，可点「开始自检」" else "授权失败：系统未返回可用会话") }
        refresh()
    }

    // ---- 实时监督：每一步跑完立刻推送到界面 ----

    private fun publish(item: CheckItem) {
        _state.update { it.copy(items = it.items + item) }
    }

    private fun enterStep(name: String) {
        SelfCheckGuard.markStep(context, name)
        CaptureLog.i(context, "SELFCHECK", "步骤=$name")
        _state.update { it.copy(currentStep = name) }
    }

    /** 主动自检：真实执行一次截屏 + OCR + 提取，逐项实时显示。 */
    fun runSelfCheck() = viewModelScope.launch {
        com.azu.bookkeeper.core.BootTrace.step("SELFCHECK", "点击真实截屏测试")
        if (_state.value.running) return@launch
        // 上次如果崩在截屏步骤，直接再跑一次大概率再次闪退 → 自动降级为安全模式
        val lastInterrupted = SelfCheckGuard.lastInterruptedStep(context)
        if (lastInterrupted != null && lastInterrupted.contains("截屏")) {
            environmentCheck(
                note = "上次自检在「$lastInterrupted」步骤被系统终止，本次已自动切换为环境检查（不截屏）。" +
                    "可先确认通道状态；如需定位截屏崩溃，请把「复制诊断信息」的内容反馈。"
            )
            return@launch
        }
        _state.update {
            it.copy(running = true, items = emptyList(), currentStep = "准备中", message = null)
        }
        // 整段兜底：OutOfMemoryError / 原生异常都不能冒到 viewModelScope（会直接退出 App）
        try {
            enterStep("环境检查")
            publish(notificationCheck())
            publish(accessibilityChannelCheck())
            publish(projectionChannelCheck())

            if (channels.needsAuthorization()) {
                finishRun(CaptureFailReason.NO_CHANNEL, listOf())
                return@launch
            }

            // ---- 截屏 ----
            enterStep("截屏（双通道）")
            val frame = withContext(io) { channels.capture() }
            val bitmap = frame.bitmap
            publish(
                CheckItem(
                    name = "截屏（${frame.channel}）",
                    ok = bitmap != null,
                    detail = if (bitmap != null) {
                        "取帧成功 ${frame.detail}"
                    } else {
                        (frame.reason?.title ?: "失败") + "：" +
                            frame.attempts.joinToString("；").ifBlank { frame.detail }
                    },
                    advice = frame.reason?.advice.orEmpty()
                )
            )
            if (bitmap == null) {
                finishRun(frame.reason ?: CaptureFailReason.UNKNOWN, listOf())
                return@launch
            }

            // ---- OCR（独立进程）----
            enterStep("OCR（独立进程）")
            val ipc = withContext(io) { ocrIpc.recognize(bitmap) }
            runCatching { bitmap.recycle() }
            val payload = ipc.payload
            val textOk = payload != null && payload.text.isNotBlank()
            publish(
                CheckItem(
                    name = "OCR 文字识别（独立进程）",
                    ok = textOk,
                    detail = when {
                        payload == null -> "OCR 进程失败：${ipc.error ?: "未知"}（主程序未受影响）"
                        payload.text.isBlank() -> "识别为空（中文模型可能未下载，或截图是空白页）"
                        else -> "${payload.text.length} 字 / ${payload.blocks} 块 / 置信度 ${"%.2f".format(payload.confidence)}"
                    },
                    advice = when {
                        payload == null -> CaptureFailReason.OCR_PROCESS_FAILED.advice
                        !textOk -> CaptureFailReason.OCR_EMPTY.advice
                        else -> ""
                    }
                )
            )
            if (payload == null || !textOk) {
                val reason = if (payload == null) CaptureFailReason.OCR_PROCESS_FAILED else CaptureFailReason.OCR_EMPTY
                finishRun(reason, listOf(), payload?.text.orEmpty())
                return@launch
            }

            // ---- 扣款提取 ----
            enterStep("扣款提取")
            val analysis = analyzer.analyze(payload.text)
            val amountOk = analysis.amountFen != null
            publish(
                CheckItem(
                    name = "扣款信息提取",
                    ok = amountOk,
                    detail = if (amountOk) {
                        "金额 ¥${analysis.amountFen!! / 100.0} · 商户 ${analysis.merchant.ifBlank { "未识别" }} · 置信度 ${"%.2f".format(analysis.confidence)}"
                    } else {
                        "识别到文字但没有金额候选（当前页可能不是支付结果页）"
                    },
                    advice = if (amountOk) "" else CaptureFailReason.EXTRACT_EMPTY.advice
                )
            )
            finishRun(
                if (amountOk) null else CaptureFailReason.EXTRACT_EMPTY,
                listOf(),
                payload.text
            )
        } catch (t: Throwable) {
            publish(
                CheckItem(
                    "自检异常",
                    false,
                    "${t.javaClass.simpleName}: ${t.message}",
                    "多为内存不足或 ROM 拦截截屏，重启 App 后重试，仍失败请把诊断信息反馈"
                )
            )
            finishRun(CaptureFailReason.UNKNOWN, listOf())
            SelfCheckGuard.markFinished(context)
        }
    }

    /**
     * 安全模式自检：只做环境检查（权限/通道），完全不截屏。
     * 用于「点开始自检就闪退」时先确认基础状态，排除截屏环节。
     */
    fun runEnvironmentOnlyCheck() = viewModelScope.launch {
        com.azu.bookkeeper.core.BootTrace.step("SELFCHECK", "点击环境检查（安全）")
        if (_state.value.running) return@launch
        // 环境检查本身也要兜底：它是用户「自检是否还崩」的基准线，绝不能崩
        try {
            environmentCheck()
        } catch (t: Throwable) {
            com.azu.bookkeeper.core.BootTrace.step(
                "SELFCHECK", "环境检查异常", "${t.javaClass.simpleName}: ${t.message}"
            )
            publish(
                CheckItem(
                    "环境检查异常",
                    false,
                    "${t.javaClass.simpleName}: ${t.message}",
                    "把该信息连同崩溃日志一起反馈"
                )
            )
            finishRun(CaptureFailReason.UNKNOWN, emptyList())
        }
    }

    private suspend fun environmentCheck(note: String? = null) {
        _state.update {
            it.copy(running = true, items = emptyList(), currentStep = "环境检查（安全模式）", message = null)
        }
        enterStep("环境检查（安全模式）")
        note?.let { publish(CheckItem("安全模式说明", false, it, "把「复制诊断信息」的内容反馈给开发者可定位该步骤崩溃")) }
        publish(notificationCheck())
        publish(accessibilityChannelCheck())
        publish(projectionChannelCheck())
        // 识屏主力通道体检：节点读屏（不截屏、不授权），读当前页面文本
        publish(readScreenCheck())
        val ready = channels.accessibilityServiceConnected() || channels.anyChannelReady()
        publish(
            CheckItem(
                name = "通道结论",
                ok = ready,
                detail = if (ready) "至少一条截屏通道可用" else "两条通道都不可用",
                advice = if (ready) "" else "先开启无障碍服务，或点「授权截屏」"
            )
        )
        finishRun(
            if (ready) null else CaptureFailReason.NO_CHANNEL,
            emptyList(),
            keepInterruptMarker = note != null
        )
    }

    /** 收尾：落盘诊断、结束监督状态。 */
    private suspend fun finishRun(
        reason: CaptureFailReason?,
        extra: List<DiagnosticStep>,
        preview: String = "",
        keepInterruptMarker: Boolean = false
    ) {
        val snapshot = _state.value.items
        runCatching {
            CaptureDiagnostics.record(
                context,
                CaptureReport(
                    timeMillis = System.currentTimeMillis(),
                    reason = reason,
                    steps = snapshot.map { DiagnosticStep(it.name, it.ok, it.detail) } + extra,
                    textPreview = preview
                )
            )
        }
        // 自动降级时保留中断标记：避免用户每次点都重复触发同一步崩溃
        if (!keepInterruptMarker) SelfCheckGuard.markFinished(context)
        _state.update {
            it.copy(
                running = false,
                currentStep = "",
                last = CaptureDiagnostics.last(context)
            )
        }
    }

    /** 识屏日志预览（最近 N 行）。 */
    fun logPreview(lines: Int = 40): String = CaptureLog.export(context, lines)

    fun logSummary(): String {
        val kb = CaptureLog.sizeBytes(context) / 1024
        return if (CaptureLog.isEnabled()) "记录中 · ${kb}KB" else "未开启（在自动化设置里开启截屏授权后开始记录）"
    }

    fun clearLog() {
        CaptureLog.clear(context)
        _state.update { it.copy(message = "识屏日志已清空") }
    }

    fun clearReport() {
        CaptureDiagnostics.clear(context)
        CrashLog.clear(context)
        SelfCheckGuard.clear(context)
        _state.update { it.copy(last = null, textPreview = "") }
        refresh()
    }

    /** 汇总当前诊断信息为纯文本，供用户一键复制反馈（含机型/ROM/通道状态）。 */
    fun buildReportText(): String {
        val s = _state.value
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("【少花点儿 · 识屏诊断】")
        sb.appendLine("机型：${com.azu.bookkeeper.core.RomCompat.displayName()}")
        sb.appendLine("Android：${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("无障碍服务连接：${channels.accessibilityServiceConnected()}")
        sb.appendLine("无障碍截屏可用：${channels.accessibilityReady()}")
        sb.appendLine("媒体投影会话：${channels.projectionReady()}")
        val lastConnected = permissionGuide.accessibilityLastConnectedAt()
        sb.appendLine("最后连接时间：${if (lastConnected > 0) fmt.format(java.util.Date(lastConnected)) else "从未连上"}")
        sb.appendLine("上次截屏能力：${permissionGuide.accessibilityCanTakeScreenshot()}")
        s.items.forEach { sb.appendLine("${if (it.ok) "✓" else "✗"} ${it.name}：${it.detail}") }
        CrashLog.last(context)?.let { crash ->
            sb.appendLine("--- 最近一次崩溃 ---")
            sb.appendLine(crash.take(3000))
        }
        sb.appendLine("--- 识屏日志（最近 200 行）---")
        sb.appendLine(CaptureLog.export(context, 200))
        s.last?.let { report ->
            sb.appendLine("--- 最近一次识屏 ---")
            sb.appendLine("时间：${fmt.format(java.util.Date(report.timeMillis))}")
            sb.appendLine("结果：${report.reason?.title ?: "成功"}")
            report.steps.forEach { sb.appendLine("${if (it.ok) "✓" else "✗"} ${it.name}：${it.detail}") }
        }
        return sb.toString()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

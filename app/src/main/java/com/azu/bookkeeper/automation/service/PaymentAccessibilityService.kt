package com.azu.bookkeeper.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.capture.AccessibilityCaptureParser
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.BillDirection
import com.azu.bookkeeper.automation.capture.PaymentScreen
import com.azu.bookkeeper.automation.capture.PaymentScreenRecognizer
import com.azu.bookkeeper.automation.capture.PendingPaymentCache
import com.azu.bookkeeper.automation.capture.ScreenTextCollector
import kotlin.math.roundToLong
import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.NotifyGuard
import com.azu.bookkeeper.core.QuickGestureCache
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍读屏捕获（主架构 §5.1 主路径，AC-4）：
 * 仅对白名单包名、完成态关键词触发；1.5s 去抖 + eventId 幂等避免连发重复抓取。
 * 抖音包名作为强信号打 businessTag=DOUYIN，但不改 captureType。
 *
 * 同时承载「指纹手势 → 记一笔」（指纹传感器四向滑动，需用户开启本服务）。
 * 摇一摇已迁到独立前台服务 ShakeQuickAddService，不再依赖本服务；
 * 「无障碍按钮」选项已移除——ColorOS 会把它渲染成系统悬浮球，造成困扰。
 * 所有手势回调只做轻动作，异常一律降级，绝不让无障碍服务崩掉（它崩了读屏也没了）。
 */
class PaymentAccessibilityService : AccessibilityService() {

    /**
     * 关键：这里绝不使用 @AndroidEntryPoint 字段注入。
     *
     * 字段注入发生在 onCreate 之前，一旦 CaptureCoordinator 那套重依赖
     * （OCR / Room / 校验视图 / 多 Repository）中任何一环构造失败，
     * 服务会在系统绑定阶段直接崩溃；很多 ROM（尤其 ColorOS / Orange 等定制系统）
     * 检测到无障碍服务崩溃后会自动把它关掉 —— 表现就是「开完返回再进来，
     * 读屏服务被自动取消」。改为惰性取 + 每个回调全链路 runCatching，
     * 最坏情况只是这一次没识别，服务本身永远活着。
     */
    private val deps by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, AccessibilityDepsEntryPoint::class.java
        )
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, QuickGestureEntryPoint::class.java
        )
    }

    // v1.3.0：旧的两个去抖状态会「先占位」吞掉结果页事件，已移除。
    // 改为「成功页内容指纹 5s 冷却」+「付款界面识别日志 5s 节流」。
    private var lastEntryAt = 0L
    private var lastSuccessFingerprint = 0L
    private var lastSuccessAt = 0L

    // v1.3.4：同一轮支付只提交一次。
    // 成功页文字是逐步加载的（先「支付成功」，再金额、商户），
    // 而 scheduleResultRetry 的多条重试链并行，每次读到的文本完整度不同 →
    // 内容指纹每次都不一样，旧 5s 指纹冷却完全拦不住 → 同一笔支付被提交 2~4 次 →
    // 后几次命中去重，弹「这笔已记过」，看起来就是「识屏快速触发误判重复」。
    // 现在：成功页提交后按包名进入静默期；再次进入付款界面（ENTRY）视为新一轮支付，解除静默。
    // v1.3.5：静默期带上「金额指纹」——同一包名但金额不同，说明是另一笔，必须放行，
    // 否则用户 12 秒内连看两笔消费记录时，第二笔会被静默吞掉（表现为「概率识别失败」）。
    private data class SettledMark(val at: Long, val money: String)

    private val settledPackages = mutableMapOf<String, SettledMark>()

    /** 重试链代号：新的读屏事件让旧链失效，避免多条链并行各提交一次。 */
    private var retryGeneration = 0

    // ---- 手势相关状态 ----
    private var fingerprintController: FingerprintGestureController? = null
    private var fingerprintCallback: FingerprintGestureController.FingerprintGestureCallback? = null
    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        // 整个回调零异常逃出：系统绑定阶段一旦抛异常，服务会被 ROM 直接禁用，
        // 用户看到的就是「刚打开无障碍，几秒后又被自动关掉」。
        runCatching { connectedInternal() }.onFailure { t ->
            Log.e(TAG, "onServiceConnected 执行异常（服务保持存活）", t)
            runCatching { com.azu.bookkeeper.core.CaptureLog.e(applicationContext, "A11Y", "onServiceConnected 异常", t) }
        }
    }

    private fun connectedInternal() {
        super.onServiceConnected()
        instance = this
        com.azu.bookkeeper.core.BootTrace.step("A11Y", "onServiceConnected")
        val canShot = runCatching { canCaptureScreen() }.getOrDefault(false)
        // 用 Log.w 而不是 Timber.i：release 的 -assumenosideeffects 会把 i() 整段裁掉，
        // 连不上服务时必须有能在 logcat 里看到的日志。
        Log.w(TAG, "无障碍服务已连接: canTakeScreenshot=$canShot sdk=${Build.VERSION.SDK_INT}")
        com.azu.bookkeeper.core.CaptureLog.i(applicationContext, "A11Y", "服务已连接 canTakeScreenshot=$canShot")
        runCatching { logScreenshotCapability() }
        // v1.3.8：识屏已恢复，立即撤掉「识屏没在工作」通知，不等守护下一轮
        runCatching { CaptureHealthNotifier.clear(applicationContext) }
        // 心跳 + 进程现场：pid / 进程已运行时长 / 第几次连接。
        // v1.2.2：连接事件也强制落盘，否则用户复制回来的 log 只有一堆断开 ERROR，
        // 还原不出「连上 → 1 秒后被撤销」的完整时间线。
        runCatching {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val count = prefs.getInt(KEY_CONNECT_COUNT, 0) + 1
            val pid = Process.myPid()
            val age = runCatching {
                SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
            }.getOrDefault(0L)
            val previousPid = prefs.getInt(KEY_CONNECT_PID, 0)
            val pidChanged = previousPid != 0 && previousPid != pid
            val everChanged = prefs.getBoolean(KEY_PID_CHANGED, false) || pidChanged
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_LAST_CONNECTED, now)
                .putBoolean(KEY_CAN_SHOT, canShot)
                .putInt(KEY_CONNECT_COUNT, count)
                .putInt(KEY_CONNECT_PID, pid)
                .putBoolean(KEY_PID_CHANGED, everChanged)
                .putLong(KEY_PROCESS_AGE, age)
                .apply()
            com.azu.bookkeeper.core.CaptureLog.forceInfo(
                applicationContext, "A11Y",
                "服务已连接 #$count pid=$pid pidChanged=$pidChanged 进程已运行=${age}ms canShot=$canShot " +
                    "device=${com.azu.bookkeeper.core.RomCompat.displayName()}"
            )
        }
        // 记录「曾经开启」，供调试页识别「连上后又被系统关掉」
        runCatching {
            com.azu.bookkeeper.automation.permission.AccessibilityHealth
                .onServiceConnected(applicationContext)
        }
        // 手势选择从持久化值回填；设置页改动会通过 QuickGestureCache 立即同步（同进程）
        scope.launch {
            runCatching {
                QuickGestureCache.code = gestureEntryPoint.automationSettings().quickGesture.first()
            }
        }
        runCatching { setupFingerprintGestures() }.onFailure {
            Log.w(TAG, "指纹手势初始化失败（不影响读屏）", it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 整个回调必须零异常逃出：无障碍服务一崩，ROM 就可能自动禁用它
        runCatching { dispatchEvent(event) }.onFailure { t ->
            timber.log.Timber.w(t, "accessibility event failed (service kept alive)")
        }
    }

    /**
     * v1.3.0 识屏主链路重写。
     *
     * 修掉的三个真凶：
     * 1. 旧 1.5s 去抖「解析前先占位」，第一个中间态事件吃掉窗口，真正的结果页事件被直接丢弃
     *    ——微信支付流程事件密集，这是「微信所有付款界面都识别失败」的主因；
     * 2. `event.source` 常只是发生变化的局部子树，不是整页；节点还被带到 IO 线程遍历，可能已失效；
     * 3. 完全不看 className / 页面类型，认不出「用户打开了付款界面」。
     *
     * 现在：主线程读多根节点 → 纯文本快照 → 页面识别 → 付款界面只记录、成功页才提交；
     * 失败不再占用去抖窗口；微信空根节点做 3 次有界重读。
     */
    private fun dispatchEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rawPackage = runCatching { event.packageName?.toString().orEmpty() }.getOrDefault("")
        if (!PaymentScreenRecognizer.isPaymentPackage(rawPackage)) return
        val packageName = PaymentScreenRecognizer.normalizePackage(rawPackage)
        val className = runCatching { event.className?.toString().orEmpty() }.getOrDefault("")

        val parser = runCatching { deps.parser() }.getOrNull() ?: return
        val text = readScreenText(parser, event)
        val recognition = PaymentScreenRecognizer.classify(packageName, className, text)
        val sparse = ScreenTextCollector.isSparse(text)

        when (recognition.screen) {
            PaymentScreen.ENTRY -> {
                // 重新进入收银台 = 新一轮支付，解除上一轮成功页的静默锁
                settledPackages.remove(packageName)
                // v1.3.6：收银台阶段金额/商户最清晰，先缓存；成功页字段缺失时补齐
                rememberPendingPayment(packageName, text)
                logScreenThrottled(
                    "付款界面 pkg=$packageName cls=$className reason=${recognition.reason} " +
                        "len=${text.length} sparse=$sparse preview=${ScreenTextCollector.preview(text)}"
                )
                return
            }
            PaymentScreen.DETAIL -> {
                // v1.3.1：账单详情不再丢弃。它常是「同一笔付款记录」的唯一现场，
                // 提交后 CaptureCoordinator 才会跑去重 → 底部轻卡片提醒「已记过」。
                logScreenThrottled(
                    "账单详情（提交去重） pkg=$packageName cls=$className reason=${recognition.reason} " +
                        "len=${text.length} preview=${ScreenTextCollector.preview(text)}"
                )
            }
            PaymentScreen.UNKNOWN -> {
                logScreenThrottled(
                    "未识别 pkg=$packageName cls=$className len=${text.length} sparse=$sparse " +
                        "preview=${ScreenTextCollector.preview(text)}"
                )
                // v1.3.5：打开「消费记录 / 账单」时页面既没有成功词也没有「账单详情」标题，
                // 但有金额 + 交易单号/商户等凭据字段 → 兜底提交，让协调器决定自动入账或轻卡片确认。
                // 这是「自动识别概率失败、必须用手势」的主要修复点之一。
                if (PaymentScreenRecognizer.looksLikeReceipt(text)) {
                    logScreenThrottled("凭证页兜底提交 pkg=$packageName len=${text.length}")
                    submitCapture(
                        parser, packageName, className, recognition,
                        ScreenTextCollector.refine(packageName, text)
                    )
                    return
                }
                // 文本稀疏（v1.3.0「只剩支付两字」的现场）或类名像结果页 → 有界重读
                if (sparse || (className.isNotBlank() && PaymentScreenRecognizer.looksLikeResultClass(className))) {
                    retryGeneration++
                    scheduleResultRetry(parser, packageName, className, 1, retryGeneration)
                }
                return
            }
            PaymentScreen.SUCCESS -> Unit
        }
        submitCapture(
            parser, packageName, className, recognition,
            ScreenTextCollector.refine(packageName, text)
        )
    }

    /** 所有「不提交」分支也留一条节流日志，用户复制 log 时能看出到底读到了什么。 */
    private fun logScreenThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastEntryAt < ENTRY_LOG_INTERVAL_MS) return
        lastEntryAt = now
        runCatching {
            com.azu.bookkeeper.core.CaptureLog.forceInfo(applicationContext, "SCREEN", message)
        }
    }

    /**
     * 主线程读节点：整页优先，只有稀疏时才补 source / 其它窗口。
     * 所有片段交给 [ScreenTextCollector.merge] 按行去重后再限长——
     * v1.3.0 把多个共享同一子树的 root 直接字符串相加，重复行把 6000 字上限吃满，
     * 真正的金额/商户行反而被截断。
     */
    private fun readScreenText(
        parser: AccessibilityCaptureParser,
        event: AccessibilityEvent
    ): String = runCatching {
        val parts = mutableListOf<String>()
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) {
            val text = parser.collectText(activeRoot)
            if (text.isNotBlank()) parts.add(text)
        }
        if (parts.isEmpty() || ScreenTextCollector.isSparse(parts.first())) {
            val source = runCatching { event.source }.getOrNull()
            if (source != null) parts.add(parser.collectText(source))
            val windows = runCatching { getWindows() }.getOrNull().orEmpty()
            for (w in windows.take(2)) {
                val node = runCatching { w.root }.getOrNull() ?: continue
                parts.add(parser.collectText(node))
                if (!ScreenTextCollector.isSparse(ScreenTextCollector.merge(parts))) break
            }
        }
        ScreenTextCollector.merge(parts)
    }.getOrDefault("")

    /** 成功页提交：内容指纹 5s 冷却；解析失败不再吞窗口。 */
    private fun submitCapture(
        parser: AccessibilityCaptureParser,
        packageName: String,
        className: String,
        recognition: PaymentScreenRecognizer.Recognition,
        text: String
    ) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val fingerprint = (packageName + text).hashCode().toLong()
        if (fingerprint == lastSuccessFingerprint && now - lastSuccessAt < SUCCESS_DEDUP_MS) return
        // v1.3.4 核心：同一轮支付成功后，本包名进入静默期。期间成功页/详情页的
        // 所有重试链、事件潮一律不再提交，从源头消灭「同一笔被提交多次 → 命中去重弹重复」。
        // v1.3.5：静默期带金额指纹——金额不同说明是另一笔（用户连续查看消费记录），必须放行。
        // 用户再次进入付款界面（ENTRY）也会解除静默，下一笔照常识别。
        val mark = settledPackages[packageName]
        val money = firstMoneyKey(text)
        if (mark != null && now - mark.at < SETTLED_QUIET_MS &&
            (money.isBlank() || mark.money.isBlank() || money == mark.money)
        ) {
            logScreenThrottled("支付静默期跳过 pkg=$packageName 距上次提交=${now - mark.at}ms money=$money")
            return
        }
        lastSuccessFingerprint = fingerprint
        lastSuccessAt = now
        settledPackages[packageName] = SettledMark(now, money)
        val dispatcher = runCatching { deps.ioDispatcher() }.getOrNull() ?: return
        com.azu.bookkeeper.core.CaptureLog.forceInfo(
            applicationContext, "SCREEN",
            "提交识别 pkg=$packageName cls=$className screen=${recognition.screen} " +
                "len=${text.length} reason=${recognition.reason} preview=${ScreenTextCollector.preview(text)}"
        )
        scope.launch {
            runCatching {
                withContext(dispatcher) {
                    parseAndCapture(packageName, recognition, text, now, parser, dispatcher)
                }
            }.onFailure { t ->
                timber.log.Timber.w(t, "accessibility parse failed (service kept alive)")
                runCatching {
                    com.azu.bookkeeper.core.CaptureLog.w(applicationContext, "A11Y", "读屏解析失败", t)
                }
            }
        }
    }

    /**
     * 粗提页面里的第一个金额数字，仅用于判断「是不是同一笔」。
     * 纯字符扫描，不用正则（Android 17 的 ICU 对字符类 \s 会抛异常）。
     */
    private fun firstMoneyKey(text: String): String {
        if (text.isBlank()) return ""
        for (i in text.indices) {
            if (text[i] == '¥' || text[i] == '￥') {
                var end = i + 1
                while (end < text.length &&
                    (text[end].isDigit() || text[end] == '.' || text[end] == ',')
                ) end++
                val s = text.substring(i + 1, end).replace(",", "")
                if (s.any { it.isDigit() }) return s
            }
        }
        // 没有货币符号时，退化为「含小数点的数字串」（如 23.00）
        var i = 0
        while (i < text.length) {
            if (text[i].isDigit()) {
                var j = i
                while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                val s = text.substring(i, j)
                if (s.contains('.') && s.length >= 3) return s
                i = j
            } else {
                i++
            }
        }
        return ""
    }

    /**
     * 收银台阶段把「即将支付的金额 / 商户」缓存 5 分钟。
     * 成功页常因为动画节点读不到金额，靠这份缓存补齐，解决「付了却识别失败」。
     */
    private fun rememberPendingPayment(packageName: String, text: String) {
        val money = firstMoneyKey(text)
        val fen = money.toDoubleOrNull()?.let { (it * 100).roundToLong() } ?: 0L
        val merchant = extractMerchantHint(text)
        if (fen > 0L || merchant.isNotBlank()) {
            PendingPaymentCache.remember(packageName, fen, merchant)
            logScreenThrottled("收银台缓存 pkg=$packageName fen=$fen merchant=$merchant")
        }
    }

    /** 从收银台文案里找「收款方 / 付款给 / 商户」后面的名字，找不到返回空。 */
    private fun extractMerchantHint(text: String): String {
        val labels = listOf("收款方名称", "收款方", "收款人", "商户名称", "付款给", "转账给", "商家", "商户")
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            for (label in labels) {
                val idx = line.indexOf(label)
                if (idx < 0) continue
                val value = line.substring(idx + label.length).trim().trim('：', ':', ' ')
                if (value.length in 2..24 && value.none { it.isDigit() }) return value
            }
        }
        return ""
    }

    /** 微信空根节点有界重读：150 / 350 / 650ms，最多 3 次，用主线程 Handler 不阻塞回调。 */
    private fun scheduleResultRetry(
        parser: AccessibilityCaptureParser,
        packageName: String,
        className: String,
        attempt: Int,
        generation: Int = retryGeneration
    ) {
        if (attempt > MAX_RETRY) return
        val delayMs = when (attempt) { 1 -> 150L; 2 -> 350L; else -> 650L }
        mainHandler.postDelayed({
            if (instance !== this) return@postDelayed
            // 期间来了新事件 → 旧链作废，避免多条链并行各提交一次
            if (generation != retryGeneration) return@postDelayed
            runCatching {
                val text = readActiveWindowText(parser)
                val recognition = PaymentScreenRecognizer.classify(packageName, className, text)
                com.azu.bookkeeper.core.CaptureLog.forceInfo(
                    applicationContext, "SCREEN",
                    "结果页重读 #$attempt len=${text.length} screen=${recognition.screen} reason=${recognition.reason}"
                )
                if ((recognition.screen == PaymentScreen.SUCCESS ||
                        recognition.screen == PaymentScreen.DETAIL ||
                        (recognition.screen == PaymentScreen.UNKNOWN &&
                            PaymentScreenRecognizer.looksLikeReceipt(text))) &&
                    text.isNotBlank()
                ) {
                    submitCapture(
                        parser, packageName, className, recognition,
                        ScreenTextCollector.refine(packageName, text)
                    )
                } else if (attempt < MAX_RETRY) {
                    scheduleResultRetry(parser, packageName, className, attempt + 1, generation)
                }
            }
        }, delayMs)
    }

    private fun readActiveWindowText(parser: AccessibilityCaptureParser): String = runCatching {
        val parts = mutableListOf<String>()
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) parts.add(parser.collectText(activeRoot))
        val windowList = runCatching { getWindows() }.getOrNull().orEmpty()
        for (w in windowList.take(2)) {
            val node = runCatching { w.root }.getOrNull() ?: continue
            parts.add(parser.collectText(node))
        }
        ScreenTextCollector.merge(parts)
    }.getOrDefault("")

    /** IO 线程：文本已是纯快照，只做打标 → 构造事件 → 交协调器。 */
    private suspend fun parseAndCapture(
        packageName: String,
        recognition: PaymentScreenRecognizer.Recognition,
        text: String,
        eventTime: Long,
        parser: AccessibilityCaptureParser,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) {
        val eventId = generateEventId(packageName, text)
        val tagger = runCatching { deps.tagger() }.getOrNull()
        val businessTag: BusinessTag = tagger?.let { runCatching { it.tag(packageName, text) }.getOrNull() }
            ?: BusinessTag.NONE
        // v1.3.6：带上收银台缓存的金额/商户，成功页读不全时由协调器补齐
        val pending = PendingPaymentCache.take(packageName)
        val captureEvent = parser.buildEvent(
            eventId = eventId,
            packageName = packageName,
            text = text,
            eventTime = eventTime,
            businessTag = businessTag,
            screen = recognition.screen,
            direction = recognition.direction,
            scene = recognition.scene,
            pendingAmountFen = pending?.amountFen ?: 0L,
            pendingMerchant = pending?.merchant.orEmpty()
        )
        // 已带进事件，清掉缓存避免污染下一笔
        if (pending != null) PendingPaymentCache.clear(packageName)
        val coordinator = runCatching { deps.coordinator() }.getOrNull() ?: return
        withContext(dispatcher) { coordinator.onCapture(captureEvent) }
    }

    /** 指纹传感器四向滑动：不需要触摸探索模式，不影响正常点击/滑动。 */
    private fun setupFingerprintGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val controller = runCatching { fingerprintGestureController }.getOrNull() ?: return
        fingerprintController = controller
        val callback = object : FingerprintGestureController.FingerprintGestureCallback() {
            override fun onGestureDetected(gesture: Int) {
                val actual = when (gesture) {
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> QuickGestureCodes.FINGER_UP
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> QuickGestureCodes.FINGER_DOWN
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> QuickGestureCodes.FINGER_LEFT
                    FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> QuickGestureCodes.FINGER_RIGHT
                    else -> QuickGestureCodes.OFF
                }
                if (actual != QuickGestureCodes.OFF && actual == QuickGestureCache.code) {
                    triggerQuickAdd()
                }
            }

            override fun onGestureDetectionAvailabilityChanged(available: Boolean) = Unit
        }
        fingerprintCallback = callback
        runCatching { controller.registerFingerprintGestureCallback(callback, mainHandler) }
    }

    /**
     * 手势命中 → 打开记一笔表单。
     * 无障碍服务属于系统绑定服务，通常可直接拉起 Activity；若被 ROM 拦下，
     * 退化成一条带「记一笔」按钮的通知，保证手势永远有反馈。
     */
    private fun triggerQuickAdd() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < GESTURE_COOLDOWN_MS) return
        lastTriggerAt = now
        val request = QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE)
        val launched = runCatching {
            startActivity(QuickCaptureLauncher.intent(applicationContext, request))
        }.isSuccess
        if (!launched) runCatching { showGestureFallbackNotification(request) }
    }

    private fun showGestureFallbackNotification(request: QuickCaptureRequest) {
        val channelId = Constants.CHANNEL_AUTO_CAPTURE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager?.getNotificationChannel(channelId) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(channelId, "自动记账", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("手势已触发")
            .setContentText("点这里记一笔")
            .setAutoCancel(true)
            .setContentIntent(QuickCaptureLauncher.pendingIntent(this, request, 2001))
            .build()
        // 手势兜底通知走统一守卫：无障碍服务里抛 SecurityException 的后果最严重——
        // ROM 会直接把读屏服务禁用且不再自动恢复（Orange 7 实测），必须在这里掐断。
        NotifyGuard.safeNotify(this, 2001, notification)
    }

    /** 稳定 eventId：包名 + 文本哈希 + 秒，避免同一完成页连发重复建单（与去重键一致）。 */
    private fun generateEventId(packageName: String, text: String): Long {
        val base = (packageName + text).hashCode().toLong() and 0x7fffffffL
        return (base xor (System.currentTimeMillis() / 1000L)) and Long.MAX_VALUE
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        com.azu.bookkeeper.core.BootTrace.step("A11Y", "onUnbind（系统撤销授权）")
        Log.w(TAG, "无障碍服务被解绑：系统撤销授权或关闭开关")
        com.azu.bookkeeper.core.CaptureLog.w(applicationContext, "A11Y", "服务被解绑（系统撤销授权）")
        val now = System.currentTimeMillis()
        val lifespan = runCatching {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CONNECTED, 0L)
        }.getOrDefault(0L).let { if (it > 0L) now - it else 0L }
        // 强制落盘：即使识屏日志开关没开，也让用户在调试页能复制到这条现场。
        // 措辞必须准确：解绑后紧接 onDestroy 是「系统撤销授权」的正常生命周期，
        // 不是 App 崩溃 / 进程被杀的证据；v1.2.1 写成「被系统销毁」把排查方向带偏了。
        com.azu.bookkeeper.core.CaptureLog.force(
            applicationContext, "A11Y",
            "无障碍服务被解绑（系统撤销授权 / 关闭开关，非崩溃证据） " +
                "存活=${lifespan}ms pid=${Process.myPid()} " +
                "device=${com.azu.bookkeeper.core.RomCompat.displayName()}"
        )
        runCatching {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_DISCONNECTED, now)
                .putLong(KEY_LAST_LIFESPAN, lifespan)
                .apply()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        com.azu.bookkeeper.core.BootTrace.step("A11Y", "onDestroy")
        Log.w(TAG, "无障碍服务已销毁（解绑后的正常销毁）")
        com.azu.bookkeeper.core.CaptureLog.force(
            applicationContext, "A11Y",
            "无障碍服务 onDestroy（解绑后的正常销毁，非崩溃 / 非进程被杀） pid=${Process.myPid()} " +
                "device=${com.azu.bookkeeper.core.RomCompat.displayName()}"
        )
        if (instance === this) instance = null
        // 保留最后一次连接时间：设置页/自检页用它区分「从没连上」与「连上后被杀」
        runCatching {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_DISCONNECTED, System.currentTimeMillis()).apply()
        }
        runCatching {
            fingerprintCallback?.let { fingerprintController?.unregisterFingerprintGestureCallback(it) }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BookkeeperA11y"
        private const val GESTURE_COOLDOWN_MS = 2_500L
        /** v1.3.0 识屏：成功页内容指纹冷却、付款界面日志节流、空节点重读次数、文本上限。 */
        private const val SUCCESS_DEDUP_MS = 5_000L
        /** v1.3.4：同一轮支付成功后同包名的静默时长；再次进入收银台（ENTRY）会提前解除。 */
        private const val SETTLED_QUIET_MS = 12_000L
        private const val ENTRY_LOG_INTERVAL_MS = 5_000L
        private const val MAX_RETRY = 3
        private const val MAX_SCREEN_TEXT = 6000
        const val PREFS = "accessibility_service_status"
        const val KEY_LAST_CONNECTED = "last_connected_at"
        const val KEY_LAST_DISCONNECTED = "last_disconnected_at"
        const val KEY_CAN_SHOT = "can_take_screenshot"
        /** v1.2.2 诊断现场：本次连接存活时长、累计连接次数、连接时 pid、连接时进程已运行时长。 */
        const val KEY_LAST_LIFESPAN = "last_lifespan_ms"
        const val KEY_CONNECT_COUNT = "connect_count"
        const val KEY_CONNECT_PID = "connect_pid"
        const val KEY_PROCESS_AGE = "process_age_at_connect"
        /** 多次连接的 pid 是否变过：不变 = 进程没被杀，后台保活类排查可排除。 */
        const val KEY_PID_CHANGED = "connect_pid_changed"

        /**
         * 当前活着的服务实例。无障碍截屏（API 30+ takeScreenshot）必须由服务实例发起，
         * 摇一摇/自检在别的组件里，通过这里拿实例；服务被杀后自动置空。
         */
        @Volatile
        private var instance: PaymentAccessibilityService? = null

        fun current(): PaymentAccessibilityService? = instance

        /** 屏幕文本快照：包名 + 拼好的文本 + 文本节点数（诊断用）。 */
        data class ScreenTextSnapshot(
            val packageName: String,
            val text: String,
            val nodeCount: Int
        )

        /**
         * 读取当前活动窗口的可见文本。
         *
         * 这是主流记账 App（钱迹等）在 Android 上的通用做法：**不截屏、不做 OCR**，
         * 直接把页面节点树里的 text / contentDescription 读出来再解析。
         *
         * 相比截图 OCR 的优势：
         * - 不需要录屏授权、不依赖 MediaProjection 会话；
         * - 不受目标页面 FLAG_SECURE 影响（读节点和截屏是两套机制）；
         * - 纯粹读 Binder 数据，不会 native 崩溃；
         * - 支付宝/微信支付结果页的「¥xx.xx」「收款方」本来就在节点里。
         *
         * 全程 runCatching：读不到只返回 null，绝不影响服务存活。
         */
        fun readScreenSnapshot(): ScreenTextSnapshot? = runCatching {
            val service = instance ?: return@runCatching null
            val root = service.rootInActiveWindow ?: return@runCatching null
            val pkg = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
            val parts = mutableListOf(ScreenTextCollector.collect(root))
            if (ScreenTextCollector.isSparse(parts.first())) {
                val windows = runCatching { service.getWindows() }.getOrNull().orEmpty()
                for (w in windows.take(2)) {
                    val node = runCatching { w.root }.getOrNull() ?: continue
                    parts.add(ScreenTextCollector.collect(node))
                }
            }
            // 与自动识屏共用同一采集/去重/标签补齐入口，摇一摇与自动结果不再漂移
            val text = ScreenTextCollector.refine(pkg, ScreenTextCollector.merge(parts))
            if (text.isBlank()) return@runCatching null
            val count = text.lines().count { it.isNotBlank() }
            ScreenTextSnapshot(pkg, text, count)
        }.getOrNull()

        /** 是否具备无障碍截屏能力（API 30+ 且 CAPABILITY_CAN_TAKE_SCREENSHOT）。 */
        fun canCaptureScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val service = instance ?: return false
            return runCatching {
                val caps = service.serviceInfo?.capabilities ?: 0
                caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
            }.getOrDefault(false)
        }
    }

    /**
     * 无障碍截屏能力由 XML（canTakeScreenshot="true"）在服务绑定时注册，
     * 运行时不能改 capabilities（Kotlin 侧是只读），这里只做一次日志确认。
     * 若设置页显示「服务已连但无截屏能力」，关掉服务再打开即可重新注册。
     */
    private fun logScreenshotCapability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        timber.log.Timber.i("a11y connected, canTakeScreenshot=%b", canCaptureScreen())
    }
}

/** 手势开关的惰性依赖入口：不在服务字段注入，避免依赖图异常拖垮读屏服务。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickGestureEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
}

/** 读屏服务的惰性依赖入口：字段注入会让服务在 ROM 崩溃后自动被禁用。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccessibilityDepsEntryPoint {
    fun parser(): AccessibilityCaptureParser
    fun tagger(): DouyinBusinessTagger
    fun coordinator(): CaptureCoordinator
    @IoDispatcher fun ioDispatcher(): CoroutineDispatcher
}

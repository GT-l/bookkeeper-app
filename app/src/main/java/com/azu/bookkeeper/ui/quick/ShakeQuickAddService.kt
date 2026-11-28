package com.azu.bookkeeper.ui.quick

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.azu.bookkeeper.MainActivity
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.verify.OverlayVerifyView
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.CaptureDiagnostics
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureReport
import com.azu.bookkeeper.core.DiagnosticStep
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.core.NotifyGuard
import com.azu.bookkeeper.core.QuickGestureCache
import com.azu.bookkeeper.core.ShakeActions
import com.azu.bookkeeper.core.ShakeSensitivity
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 摇一摇记一笔：独立前台服务，不依赖无障碍服务。
 *
 * 摇一摇命中后的动作由设置页决定（见 ShakeActions）：
 * - OPEN_FORM：直接打开记账表单；
 * - SCREEN_CAPTURE：触发识屏管线；识屏失败 / 未授权 / 识别未完成时，
 *   自动打开记账表单让用户手动输入，绝不只弹个 Toast 就结束。
 *
 * 后台拉起 Activity 在部分 ROM（尤其 ColorOS）上会被拦截：第一次能用、之后只响 Toast
 * 就是这么来的。因此保留两条路：先直接 startActivity；失败时发一条带 full-screen intent
 * 的高优先级通知，由系统把记账表单推到前台（用户报的「只提醒不跳转」由此修复）。
 */
class ShakeQuickAddService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ShakeActionEntryPoint::class.java)
    }

    private var sensorManager: SensorManager? = null
    private var detector: ShakeDetector? = null

    /** 一次摇动只允许走一条链路：处理未结束时再摇一律忽略（防重复调用）。 */
    @Volatile
    private var handling = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // 未授予通知权限时部分 ROM 会抛 SecurityException 甚至阻塞主线程（ANR）→ 统一走守卫
                NotifyGuard.safeNotify(this, NOTIFICATION_ID, buildNotification())
                // 设置页改了「摇一摇动作/灵敏度」后热更新：不用重启服务，阈值立刻生效
                scope.launch {
                    runCatching {
                        val settings = entryPoint.automationSettings()
                        QuickGestureCache.shakeAction = settings.shakeAction.first()
                        QuickGestureCache.shakeSensitivity = settings.shakeSensitivity.first()
                        detector?.updateThreshold(
                            ShakeSensitivity.threshold(QuickGestureCache.shakeSensitivity)
                        )
                    }.onFailure { Timber.w(it, "refresh shake settings failed") }
                }
                return START_NOT_STICKY
            }
        }
        return try {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            // 用持久化值回填动作缓存（设置页改动也会同步更新缓存）
            scope.launch {
                runCatching {
                    QuickGestureCache.shakeAction = entryPoint.automationSettings().shakeAction.first()
                    QuickGestureCache.shakeSensitivity =
                        entryPoint.automationSettings().shakeSensitivity.first()
                    // 注册传感器时可能还没读到持久化值，这里补一次阈值热更新
                    detector?.updateThreshold(
                        ShakeSensitivity.threshold(QuickGestureCache.shakeSensitivity)
                    )
                }.onFailure { Timber.w(it, "read shake action failed") }
            }
            registerSensor()
            isRunning = true
            // 授权完成后由授权页发 ACTION_CAPTURE_NOW 回来继续识屏（必须在 startForeground 之后）
            if (intent?.action == ACTION_CAPTURE_NOW) runCatching { triggerScreenCapture() }
            START_NOT_STICKY
        } catch (t: Throwable) {
            Timber.e(t, "ShakeQuickAddService start failed")
            Toast.makeText(applicationContext, "摇一摇启动失败，请检查传感器权限", Toast.LENGTH_LONG).show()
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun registerSensor() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = manager
        // 识别算法在 ShakeDetector 里：去重力 + 双向确认 + 多帧确认 + 冷却，
        // 不再是「合加速度 > 24 就触发」那种一碰就炸的写法。
        val callback = ShakeDetector(
            threshold = ShakeSensitivity.threshold(QuickGestureCache.shakeSensitivity),
            onShake = { onShakeDetected() }
        )
        detector = callback
        manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /**
     * 摇动命中后的唯一入口。
     *
     * 三重闸门，保证「一次摇动只做一件事」：
     * 1. [handling] —— 上一次触发还在跑（识屏/跳转中）就直接忽略；
     * 2. 屏幕必须亮着 —— 装在兜里、放在桌上被震动都不算；
     * 3. 动作只有设置里选的那一个（打开表单 或 直接识屏），不会两条链路同时跑。
     */
    private fun onShakeDetected() {
        if (handling) return
        if (!isScreenInteractive()) return
        handling = true
        // 每次命中都双震 + Toast：不依赖通知权限，用户一定能感知到「已经触发了」
        vibrate()
        if (QuickGestureCache.shakeAction == ShakeActions.SCREEN_CAPTURE) {
            Toast.makeText(applicationContext, "摇一摇已触发 · 正在识屏…", Toast.LENGTH_SHORT).show()
            // 兜底解锁：识屏最坏 25s 内一定会走完回调；即便卡住也不会永久锁死
            releaseHandlingLater(AUTO_CAPTURE_LOCK_TIMEOUT_MS)
            triggerScreenCapture()
        } else {
            openForm("摇一摇命中，打开记一笔")
            // 表单已经拉起，1.5s 后允许下一次摇动（要记第二笔时不用干等）
            releaseHandlingLater(1_500L)
        }
    }

    /** 处理结束/超时后解锁，允许下一次摇动。 */
    private fun releaseHandlingLater(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            handling = false
        }
    }

    /** 屏幕是否点亮且已解锁。熄屏（装兜里）时完全不响应摇动。 */
    private fun isScreenInteractive(): Boolean = runCatching {
        val power = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        power?.isInteractive == true
    }.getOrDefault(true)

    /**
     * 摇一摇 → 轻量识屏：截屏 + OCR + 智能提取 → 带预填打开表单。
     *
     * 多重提醒：
     * - 没有截屏授权：自动拉起透明授权页；被 ROM 拦下则发「点击授权」通知；
     * - 任一环节失败：Toast 明确原因 + 进度通知标题写明原因 + 自检页留痕 + 自动打开表单。
     */
    private fun triggerScreenCapture() {
        com.azu.bookkeeper.core.CaptureLog.i(applicationContext, "SHAKE", "摇一摇触发识屏")
        val capture = runCatching { entryPoint.lightweightCapture() }.getOrNull()
        if (capture == null) {
            recordDiagnostic(
                CaptureFailReason.UNKNOWN,
                listOf(DiagnosticStep("依赖注入", false, "LightweightCapture 取不到"))
            )
            // v1.3.5：依赖异常也不自动把页面顶到前台，避免用户不操作后黑屏
            Toast.makeText(applicationContext, "识屏组件加载失败，点通知手动记一笔", Toast.LENGTH_SHORT).show()
            showTapFallbackNotification(QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE))
            return
        }
        if (capture.needsAuthorization()) {
            requestCaptureAuthorization(capture)
            return
        }
        capture.captureIntoForm(scope) { outcome ->
            // 识屏链路已走完（成功/失败都算），短暂延迟后允许下一次摇动
            releaseHandlingLater(800L)
            val amount = outcome.amountFen
            if (outcome.success && amount != null && amount > 0L) {
                handleRecognized(outcome, capture, amount)
            } else {
                val message = if (!outcome.success) {
                    "识屏失败：${outcome.reason?.title}，请手动输入"
                } else {
                    "没有识别到金额，请手动输入"
                }
                openCaptureForm(capture, amount, outcome.remark, message)
            }
        }
    }

    /**
     * 识别到金额后的分流：
     * 0. **疑似重复检查**（近 24 小时同商户同金额已有一笔）→ 弹确认让用户决定是否重复记录。
     *    优先级最高——重复记账比少记一笔更让用户恼火，而这一笔到底要不要记只有用户知道。
     * 1. 置信度 ≥ [AUTO_RECORD_CONFIDENCE]（金额+商户同时命中）→ 直接记账，只提示「记账成功」；
     * 2. 0.5 ~ 0.8（只拿到金额 / 金额+支付成功）→ 弹一颗小卡片确认，不把用户丢进完整表单；
     * 3. 更低或落库失败 → 打开完整表单。
     */
    private fun handleRecognized(outcome: CaptureOutcome, capture: LightweightCapture, amount: Long) {
        scope.launch {
            val duplicate = runCatching {
                entryPoint.autoRecord().findRecentDuplicate(amount, outcome.merchant)
            }.getOrNull()
            if (duplicate != null) {
                com.azu.bookkeeper.core.CaptureLog.i(
                    applicationContext, "CAPTURE",
                    "疑似重复 amount=$amount merchant=${outcome.merchant} 命中 billId=${duplicate.billId}"
                )
                showDuplicateConfirm(capture, outcome, amount, duplicate)
                return@launch
            }
            // v1.3.1：识别把握够（金额 + 有效商户）就不再问，直接记账并弹「记账成功」轻卡片；
            // 商户没抽到 / 只抽到「支付」这类噪声词时才弹轻确认。
            val usableMerchant = outcome.merchant.trim().let {
                it.length >= 2 && it !in setOf("支付", "付款", "账单", "已完成", "微信支付", "支付宝")
            }
            if (outcome.confidence >= AUTO_RECORD_CONFIDENCE || usableMerchant) {
                autoRecord(outcome, capture, amount)
            } else {
                showQuickConfirm(capture, amount, outcome)
            }
        }
    }

    /**
     * 疑似重复：v1.3.0 起改为**屏幕底部悬浮卡片**，不再 startActivity 把用户拽进软件界面。
     * 「仍然记账」直接在后台落库，「修改」才打开表单（这是用户主动选择）。
     * 没有悬浮窗权限时只发一条点击才打开的普通通知。
     */
    private fun showDuplicateConfirm(
        capture: LightweightCapture,
        outcome: CaptureOutcome,
        amount: Long,
        duplicate: com.azu.bookkeeper.domain.usecase.bill.DuplicateHint
    ) {
        val whenText = com.azu.bookkeeper.core.DateTimeUtils.format(
            duplicate.billTime, "MM-dd HH:mm"
        )
        val detail = "已有一笔：$whenText · ${Money.format(duplicate.amountFen)}" +
            if (duplicate.remark.isNotBlank()) " · ${duplicate.remark}" else ""
        val shown = runCatching {
            entryPoint.overlayVerify().showConfirm(
                title = "这笔好像记过了",
                detail = "$detail\n本次：${Money.format(amount)} ${outcome.merchant}".trim(),
                confirmLabel = "仍然记账",
                editLabel = "修改",
                onConfirm = { autoRecord(outcome, capture, amount) },
                onEdit = { openCaptureForm(capture, amount, outcome.remark, "修改这笔账单") },
                onDiscard = {
                    com.azu.bookkeeper.core.CaptureLog.i(
                        applicationContext, "CAPTURE", "用户忽略疑似重复 amount=$amount"
                    )
                }
            )
        }.getOrDefault(false)
        if (!shown) showConfirmFallbackNotification(amount, outcome, detail)
    }

    /** 高置信度直接落库；失败则退到小弹窗，绝不静默丢掉这笔。 */
    private fun autoRecord(outcome: CaptureOutcome, capture: LightweightCapture, amount: Long) {
        scope.launch {
            val recorder = runCatching { entryPoint.autoRecord() }.getOrNull()
            val billId = if (recorder == null) {
                null
            } else {
                runCatching {
                    recorder(
                        amountFen = amount,
                        merchant = outcome.merchant,
                        goods = outcome.goods,
                        confidence = outcome.confidence,
                        rawText = outcome.rawText,
                        captureType = outcome.captureType
                    )
                }.getOrNull()?.getOrNull()
            }
            if (billId != null) {
                // v1.3.1：成功反馈统一走屏幕底部轻卡片（3 秒自消），
                // 不再 Toast + 常驻通知栏两段打扰。
                runCatching { entryPoint.overlayVerify().showSuccess(amount, outcome.remark) }
                com.azu.bookkeeper.core.CaptureLog.i(
                    applicationContext, "CAPTURE",
                    "自动记账成功 billId=$billId amount=$amount conf=${outcome.confidence}"
                )
            } else {
                com.azu.bookkeeper.core.CaptureLog.w(
                    applicationContext, "CAPTURE", "自动记账失败，退到确认弹窗"
                )
                showQuickConfirm(capture, amount, outcome)
            }
        }
    }

    /** 轻确认：v1.3.0 起同样走底部悬浮卡片，确认即记账，不进软件界面。 */
    private fun showQuickConfirm(capture: LightweightCapture, amount: Long, outcome: CaptureOutcome) {
        val shown = runCatching {
            entryPoint.overlayVerify().showConfirm(
                title = "识别到一笔支出",
                detail = "${Money.format(amount)} ${outcome.merchant}".trim(),
                confirmLabel = "记账",
                editLabel = "修改",
                onConfirm = { autoRecord(outcome, capture, amount) },
                onEdit = { openCaptureForm(capture, amount, outcome.remark, "请确认金额") },
                onDiscard = {
                    com.azu.bookkeeper.core.CaptureLog.i(
                        applicationContext, "CAPTURE", "用户忽略识屏结果 amount=$amount"
                    )
                }
            )
        }.getOrDefault(false)
        if (!shown) showConfirmFallbackNotification(amount, outcome, "")
    }

    /** 无悬浮窗权限的兜底：只发普通通知，**点击才**打开确认页，绝不自动把 App 拉到前台。 */
    private fun showConfirmFallbackNotification(
        amount: Long,
        outcome: CaptureOutcome,
        duplicate: String
    ) {
        runCatching {
            val intent = QuickConfirmActivity.intent(
                this, amount, outcome.merchant, outcome.confidence,
                goods = outcome.goods, duplicate = duplicate
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(this, CONFIRM_NOTIFICATION_ID, intent, flags)
            val channelId = Constants.CHANNEL_AUTO_CAPTURE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                if (manager?.getNotificationChannel(channelId) == null) {
                    manager?.createNotificationChannel(
                        NotificationChannel(channelId, "自动记账", NotificationManager.IMPORTANCE_HIGH)
                    )
                }
            }
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle(
                    if (duplicate.isBlank()) "识别到一笔支出，点此确认" else "这笔可能记过了，点此确认"
                )
                .setContentText("${Money.format(amount)} ${outcome.merchant}".trim())
                .setContentIntent(pi)
                .setAutoCancel(true)
                // v1.3.1：极端兜底也必须轻打扰——8 秒后自己消失，不在通知栏常驻
                .setTimeoutAfter(8_000L)
                .build()
            NotifyGuard.safeNotify(this, CONFIRM_NOTIFICATION_ID, notification)
        }
    }

    /** 没有截屏授权：优先拉起透明授权页；被 ROM 拦截则发可点击的「去授权」通知兜底。 */
    private fun requestCaptureAuthorization(capture: LightweightCapture) {
        com.azu.bookkeeper.core.CaptureLog.w(applicationContext, "SHAKE", "无可用截屏通道，拉起授权页")
        val authIntent = Intent(this, ScreenCaptureAuthActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { startActivity(authIntent) }.isSuccess
        if (launched) {
            capture.toast("请允许屏幕捕获，授权后会自动继续识屏")
            return
        }
        recordDiagnostic(
            CaptureFailReason.NEED_AUTH_LAUNCH_FAILED,
            listOf(DiagnosticStep("拉起授权页", false, "后台启动 Activity 被系统拦截"))
        )
        runCatching {
            val pi = PendingIntent.getActivity(
                this,
                2007,
                authIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            entryPoint.captureProgress().showNeedAuth(pi)
        }
        // v1.3.5：无授权时不自动跳表单，只留一条点击才打开的通知
        showTapFallbackNotification(QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE))
    }

    /** 统一记录诊断（自检页展示最近一次失败步骤）。 */
    private fun recordDiagnostic(reason: CaptureFailReason, steps: List<DiagnosticStep>) {
        runCatching {
            CaptureDiagnostics.record(
                applicationContext,
                CaptureReport(System.currentTimeMillis(), reason, steps)
            )
        }
    }

    /**
     * 识屏失败时的出口（v1.3.5 重做）。
     *
     * 旧行为：直接 startActivity 把记账表单顶到前台。表单若成为冷启动起始页，
     * 用户点「取消」时 popBackStack 无栈可退（取消不了），按返回又直接退出（黑屏）。
     * 现在不再自动跳转：只给 Toast + 底部轻卡片（用户点「手动记一笔」才进表单），
     * 弹不出卡片时退化为一条点击才打开的普通通知。
     */
    private fun openCaptureForm(
        capture: LightweightCapture,
        amountFen: Long?,
        remark: String,
        message: String
    ) {
        capture.toast(message)
        val request = QuickCaptureRequest(
            entry = QuickEntry.SYSTEM_GESTURE,
            amountFen = amountFen,
            merchant = remark
        )
        val shown = runCatching {
            entryPoint.overlayVerify().showConfirm(
                title = "没识别到这笔，要手动记吗？",
                detail = if (amountFen != null && amountFen > 0L) {
                    "${Money.format(amountFen)} ${remark}".trim()
                } else {
                    "点下面的按钮打开记账页"
                },
                confirmLabel = "手动记一笔",
                editLabel = null,
                onConfirm = { openCaptureFormNow(capture, amountFen, remark) },
                onDiscard = {},
                note = "不用着急，随时都能记"
            )
        }.getOrDefault(false)
        if (!shown) showTapFallbackNotification(request)
    }

    /** 用户主动点了「手动记一笔」才真正打开表单。 */
    private fun openCaptureFormNow(
        capture: LightweightCapture,
        amountFen: Long?,
        remark: String
    ) {
        val launched = runCatching {
            startActivity(capture.formIntent(amountFen, remark))
        }.isSuccess
        if (!launched) {
            showTapFallbackNotification(
                QuickCaptureRequest(
                    entry = QuickEntry.SYSTEM_GESTURE,
                    amountFen = amountFen,
                    merchant = remark
                )
            )
        }
    }

    /** 点击才打开的普通通知（v1.3.5：去掉 fullScreenIntent，避免自动把页面顶到前台）。 */
    private fun showTapFallbackNotification(request: QuickCaptureRequest) {
        runCatching { ensureGestureChannel() }
        runCatching {
            val pending = QuickCaptureLauncher.pendingIntent(this, request, 2002)
            val notification = NotificationCompat.Builder(this, GESTURE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quick_add)
                .setContentTitle("识屏没成功，点这里手动记")
                .setContentText("不会自动打开页面")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setTimeoutAfter(8_000L)
                .build()
            NotifyGuard.safeNotify(this, 2002, notification)
        }
    }

    /** 打开记账表单；后台启动被拦时用全屏通知兜底，保证每次都能进来。 */
    private fun openForm(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        val request = QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE)
        val launched = runCatching {
            startActivity(QuickCaptureLauncher.intent(applicationContext, request))
        }.isSuccess
        if (!launched) showFullScreenFallback(request)
    }

    private fun showFullScreenFallback(request: QuickCaptureRequest) {
        runCatching { ensureGestureChannel() }
        // 全屏通知兜底也走守卫：通知权限被关时部分 ROM 会阻塞甚至抛 SecurityException
        val posted = runCatching {
            val pending = QuickCaptureLauncher.pendingIntent(this, request, 2002)
            NotifyGuard.safeNotify(this, 2002, buildFullScreenNotification(pending))
        }.getOrDefault(false)
        if (!posted) Timber.w("full screen fallback not posted (notification permission off / ROM blocked)")
    }

    private fun buildFullScreenNotification(pending: PendingIntent): android.app.Notification =
        NotificationCompat.Builder(this, GESTURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle("摇一摇已触发")
            .setContentText("点这里记一笔")
            .setAutoCancel(true)
            .setContentIntent(pending)
            // v1.3.5：去掉 fullScreenIntent。它会绕过后台启动限制自动把页面顶到前台，
            // 用户不操作时容易停在表单上，返回就是黑屏。改为高优先级普通通知，点击才进。
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(8_000L)
            .build()

    private fun vibrate() {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 双脉冲：与普通通知震动区分开，明确是「摇一摇命中」
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 70, 70, 70), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 70, 70, 70), -1)
            }
        }
    }

    /**
     * 摇一摇常驻通知改用独立低重要级渠道。
     * 不再复用 CHANNEL_AUTO_CAPTURE：自动化服务（LOW）、校对通知（DEFAULT）、
     * 摇一摇（HIGH）三方抢建同一渠道时，重要级以先建者为准且系统永久保留，
     * 会出现「有时响铃有时静音」的不稳定表现——这就是用户反馈的操作异常之一。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONGOING_CHANNEL_ID,
                    "摇一摇快捷记账",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    /** 全屏意图单独用高重要级渠道，避免把常驻通知渠道也变成响铃/弹窗。 */
    private fun ensureGestureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(GESTURE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    GESTURE_CHANNEL_ID,
                    "手势快捷记账",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openSettings = PendingIntent.getActivity(
            this,
            2003,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            2004,
            Intent(this, ShakeQuickAddService::class.java).setAction(ACTION_STOP),
            flags
        )
        val action = ShakeActions.label(QuickGestureCache.shakeAction)
        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_add)
            // v1.3.2：与自动记账共用同一个通知 ID，通知栏只保留一条常驻项
            .setContentTitle("自动记账 · 摇一摇就绪")
            .setContentText("摇动手机识屏记账（$action）· 点此打开设置")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openSettings)
            .addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_quick_add, "关闭", stopIntent).build()
            )
            .build()
    }

    override fun onDestroy() {
        runCatching { detector?.let { sensorManager?.unregisterListener(it) } }
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_STOP"
        const val ACTION_REFRESH = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_REFRESH"

        /** 截屏授权完成后继续识屏。 */
        const val ACTION_CAPTURE_NOW = "com.azu.bookkeeper.action.SHAKE_QUICK_ADD_CAPTURE_NOW"
        /** 金额+商户同时命中（confidence≥0.8）就认为可靠，不再打扰用户。 */
        private const val AUTO_RECORD_CONFIDENCE = 0.8f
        /** v1.3.2：与自动记账前台服务共用通知 ID，通知栏只显示一条常驻项。 */
        private const val NOTIFICATION_ID = Constants.FOREGROUND_SERVICE_ID
        /** 底部悬浮卡片不可用时的「点击才打开」兜底通知。 */
        private const val CONFIRM_NOTIFICATION_ID = 1021
        private const val ONGOING_CHANNEL_ID = "shake_ongoing"
        private const val GESTURE_CHANNEL_ID = "shake_gesture_high"
        /** 识屏链路的解锁兜底时间：识屏正常 25s 内必回调。 */
        private const val AUTO_CAPTURE_LOCK_TIMEOUT_MS = 25_000L

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            if (isRunning) return
            runCatching {
                val intent = Intent(context, ShakeQuickAddService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "start ShakeQuickAddService failed") }
        }

        /** 动作选择变化后刷新常驻通知文案；服务没在跑时什么也不做。 */
        fun refresh(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, ShakeQuickAddService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ShakeQuickAddService::class.java).setAction(ACTION_STOP))
            }
        }

        /** 授权完成后继续识屏；服务没在跑时顺带拉起（onStartCommand 内会先 startForeground）。 */
        fun captureNow(context: Context) {
            runCatching {
                val intent = Intent(context, ShakeQuickAddService::class.java)
                    .setAction(ACTION_CAPTURE_NOW)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}

/** getter 名不能与不同类型冲突：automationSettings() 与 lightweightCapture() 均与已有入口同类型。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShakeActionEntryPoint {
    fun automationSettings(): AutomationSettingsDataStore
    fun lightweightCapture(): LightweightCapture
    fun captureProgress(): CaptureProgressNotifier
    fun autoRecord(): com.azu.bookkeeper.domain.usecase.bill.AutoRecordUseCase
    fun overlayVerify(): OverlayVerifyView
}

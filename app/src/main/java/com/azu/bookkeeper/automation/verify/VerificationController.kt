package com.azu.bookkeeper.automation.verify

import com.azu.bookkeeper.automation.capture.PaymentScene
import com.azu.bookkeeper.automation.dedup.DuplicateDetector
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.normalizeRemark
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import com.azu.bookkeeper.data.mapper.toCandidate
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 用户对低置信度候选的决定。 */
data class VerifyDecision(
    val eventId: Long,
    val accepted: Boolean,
    val amountFen: Long? = null,
    val merchant: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null
)

/**
 * 三档校对控制器（主架构 §5.3 / PRD 22.4d）：
 * requests 供前台 Compose Dialog 收集；后台按 verify_mode 走悬浮窗 / 高优先级通知。
 * 用户确认或改写后，经 BillRepository 单一事务入账并把事件置 USER_CORRECTED 终态。
 */
@Singleton
class VerificationController @Inject constructor(
    private val captureDao: CaptureDao,
    private val billRepository: BillRepository,
    private val settings: SettingsRepository,
    private val automationSettings: AutomationSettingsDataStore,
    private val notifier: VerificationNotifier,
    private val overlay: OverlayVerifyView,
    private val dedup: DuplicateDetector,
    private val encryptedFiles: EncryptedFileDataSource
) {

    private val _requests = MutableSharedFlow<CaptureCandidate>(extraBufferCapacity = 16)
    val requests: SharedFlow<CaptureCandidate> = _requests.asSharedFlow()

    /** T7 抖音兜底：前台「一键补记」事件流，由 MainActivity / 弹窗宿主收集后拉起表单。 */
    private val _quickCaptureEvents = MutableSharedFlow<QuickCaptureRequest>(extraBufferCapacity = 8)
    val quickCaptureEvents: SharedFlow<QuickCaptureRequest> = _quickCaptureEvents.asSharedFlow()

    /** 已发出补记请求但尚未写库的事件：期间跳过重复提醒，避免用户填表时再被弹窗打断。 */
    private val pendingQuickEventIds = ConcurrentHashMap.newKeySet<Long>()

    /** v1.3.4：同一笔（金额+备注）重复提醒的节流时间戳，防止事件潮连续弹多张卡片。 */
    private val lastDuplicateNoticeAt = ConcurrentHashMap<String, Long>()

    /**
     * 低置信度弹窗「一键补记」：带已知预填字段与来源事件 ID 发出统一契约。
     * 事件终态仍由 QuickCaptureUseCase 在保存时的单一事务内置 USER_CORRECTED。
     */
    fun requestQuickCapture(candidate: CaptureCandidate) {
        if (candidate.eventId <= 0L) return
        pendingQuickEventIds.add(candidate.eventId)
        _quickCaptureEvents.tryEmit(
            QuickCaptureRequest(
                entry = QuickEntry.DOUYIN_FALLBACK,
                amountFen = candidate.amountFen?.takeIf { it > 0L },
                merchant = candidate.merchant,
                billTime = candidate.billTime,
                bookId = candidate.bookId.takeIf { it > 0L },
                accountId = candidate.accountId?.takeIf { it > 0L },
                categoryId = candidate.categoryId?.takeIf { it > 0L },
                fromCaptureEventId = candidate.eventId
            )
        )
    }

    /** 用户放弃补记表单：解除提醒抑制，事件仍为非终态，后续可继续校对。 */
    fun cancelQuickCapture(eventId: Long) {
        if (eventId > 0L) pendingQuickEventIds.remove(eventId)
    }

    /**
     * 低置信度 / 疑似重复事件入队（v1.3.1 统一轻打扰）：
     * - 默认（mode 0/1/2）：一律优先屏幕底部轻卡片（TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限）；
     *   卡片失败才发一条 8 秒自动消失的横幅，**不再发常驻通知、不再弹前台 Dialog**。
     * - mode 3（用户显式选「仅前台弹窗」）才保留 Compose Dialog。
     *
     * 用户明确要求：重复弹窗不要强制拉起软件界面、不要落到通知栏。
     */
    suspend fun requestVerify(candidate: CaptureCandidate, duplicate: Boolean = false) {
        if (candidate.eventId in pendingQuickEventIds) return
        val mode = automationSettings.verifyMode.first()
        if (mode == 3) {
            _requests.tryEmit(candidate)
            return
        }
        val overlayShown = overlay.show(
            candidate = candidate.copy(
                merchant = if (duplicate) "可能是重复记账 · ${candidate.merchant}" else candidate.merchant
            ),
            onConfirm = { submitFromOverlay(candidate) },
            onDiscard = { discardFromOverlay(candidate.eventId) }
        )
        if (!overlayShown) {
            notifier.showTransientVerify(candidate.amountFen, candidate.merchant, duplicate)
        }
    }

    /** 高置信度自动入账成功：底部轻卡片「记账成功」，不询问、3 秒自动消失。 */
    fun notifyRecorded(amountFen: Long, remark: String) {
        runCatching { overlay.showSuccess(amountFen, remark) }
    }

    /** 确定重复已跳过：轻卡片提示；同一笔 8 秒内只提醒一次，避免事件潮连续弹。 */
    fun notifyDuplicated(amountFen: Long, remark: String) {
        val key = "$amountFen|${remark.normalizeRemark()}"
        val now = System.currentTimeMillis()
        val last = lastDuplicateNoticeAt[key] ?: 0L
        if (now - last < DUPLICATE_NOTICE_COOLDOWN_MS) return
        lastDuplicateNoticeAt[key] = now
        if (lastDuplicateNoticeAt.size > 200) {
            lastDuplicateNoticeAt.entries.removeIf { now - it.value > DUPLICATE_NOTICE_COOLDOWN_MS }
        }
        runCatching {
            overlay.showInfo(
                title = "这笔已记过，先跳过啦",
                detail = "${Money.format(amountFen)} ${remark}".trim(),
                note = "避免重复记账，放心～",
                autoDismissMs = 3_200L
            )
        }
    }

    /**
     * v1.3.8：识别到提现 / 充值 / 零钱通·余额宝划转。
     * 这类不是收支（钱只是换了账户），协调器已跳过不记账；
     * 这里给一张 3 秒轻提示，避免用户误以为识屏又失效了。
     */
    fun notifyInternalTransfer(sceneType: String) {
        val scene = runCatching { PaymentScene.valueOf(sceneType) }.getOrNull()
        val label = when (scene) {
            PaymentScene.WECHAT_WITHDRAW -> "微信零钱"
            PaymentScene.ALIPAY_WITHDRAW -> "支付宝余额"
            else -> "账户"
        }
        runCatching {
            overlay.showInfo(
                title = "这笔是账户内部划转",
                detail = "${label}提现 / 充值不计入收支，已跳过",
                note = "没有漏记，放心～",
                autoDismissMs = 3_200L
            )
        }
    }

    /** 悬浮窗「记一笔」：直接按识别字段确认入账（用户已点确认视为介入）。 */
    private fun submitFromOverlay(candidate: CaptureCandidate) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = submitDecision(
                VerifyDecision(
                    eventId = candidate.eventId,
                    accepted = true,
                    amountFen = candidate.amountFen,
                    merchant = candidate.merchant,
                    categoryId = candidate.categoryId,
                    accountId = candidate.accountId
                )
            )
            // v1.3.2：过去失败被静默吞掉，用户点了「记一笔」却什么都没发生。
            // 现在给一张 3 秒轻提示说明原因，绝不让确认按钮「假成功」。
            if (result is Result.Error) {
                overlay.showInfo(
                    title = "记账未完成",
                    detail = result.error.userMessage.ifBlank { "请手动记一笔" }
                )
            }
        }
    }

    private fun discardFromOverlay(eventId: Long) {
        pendingQuickEventIds.remove(eventId)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            captureDao.markStatus(eventId, CaptureEventStatus.DISCARDED.code)
            // DISCARDED 终态：校对已被用户放弃，截图缓存随之清理
            deleteCaptureImage(
                imagePath = captureDao.findByEventId(eventId)?.imagePath.orEmpty(),
                reason = "overlay_discarded"
            )
        }
    }

    /**
     * 校对终态后清理加密截图缓存（USER_CORRECTED / DISCARDED）。
     * 仅删除文件、不改 DB schema；删除失败只记 Timber 日志不抛异常。
     * 校对中（LOW_CONFIDENCE）与决定未落定前的错误路径（缺账本/金额/分类）保留文件，
     * 保证校对弹窗仍可看图。
     */
    private suspend fun deleteCaptureImage(imagePath: String, reason: String) {
        if (imagePath.isBlank()) return
        val deleted = encryptedFiles.deleteFile(imagePath)
        Timber.d(
            "verify image cleanup deleted=%b reason=%s path=%s",
            deleted, reason, imagePath
        )
    }

    /**
     * 提交用户决定。接受 → 事务建单并把捕获事件置 USER_CORRECTED；
     * 拒绝 → 事件置 DISCARDED 终态。全程不产生重复提醒。
     */
    suspend fun submitDecision(decision: VerifyDecision): Result<Long> {
        // 任何决定（确认 / 丢弃 / 补记成功）都清除补记挂起标记，允许事件进入终态后彻底静默。
        pendingQuickEventIds.remove(decision.eventId)
        val pending = captureDao.findByEventId(decision.eventId)
            ?: return Result.Error(
                com.azu.bookkeeper.core.AppError.Automation(
                    code = "E_AUTO_002",
                    userMessage = "该待校对记录已处理，已跳过"
                )
            )

        if (!decision.accepted) {
            captureDao.markStatus(decision.eventId, CaptureEventStatus.DISCARDED.code)
            // DISCARDED 终态：用户拒绝该识别记录，清理其截图缓存
            deleteCaptureImage(pending.imagePath, "verify_discarded")
            return Result.Error(
                com.azu.bookkeeper.core.AppError.Automation(
                    code = "E_AUTO_003",
                    userMessage = "已丢弃这条识别记录"
                )
            )
        }

        val candidate = pending.toCandidate()
        val bookId = settings.defaultBookId.first().takeIf { it > 0L }
            ?: return Result.Error(
                com.azu.bookkeeper.core.AppError.Validation(
                    code = "E_VALID_008", userMessage = "请先设置默认账本"
                )
            )
        val amountFen = decision.amountFen ?: candidate.amountFen
        if (amountFen == null || amountFen <= 0L) {
            return Result.Error(
                com.azu.bookkeeper.core.AppError.BillAmount()
            )
        }

        // 用户改写后的二次去重（金额/账户被改动时窗口键可能变化）
        val checkCandidate = candidate.copy(
            bookId = bookId,
            amountFen = amountFen,
            accountId = decision.accountId ?: candidate.accountId
        )
        val toleranceSec = automationSettings.smsDedupToleranceSec.first()
        if (candidate.captureType == com.azu.bookkeeper.domain.model.CaptureType.SMS &&
            dedup.isDuplicateWithTolerance(checkCandidate, toleranceSec)
        ) {
            // 用户介入路径：即使因「已存在相同账单」未建单，终态也必须是 USER_CORRECTED，
            // 不能标成「自动入账完成」（QA F-08）。
            captureDao.markStatus(decision.eventId, CaptureEventStatus.USER_CORRECTED.code)
            // USER_CORRECTED 终态：清理截图缓存
            deleteCaptureImage(pending.imagePath, "verify_dedup_terminal")
            return Result.Error(
                com.azu.bookkeeper.core.AppError.Validation(
                    code = "E_AUTO_004", userMessage = "已存在相同账单，无需重复添加"
                )
            )
        }

        // 与 AddBillUseCase.validate 同口径：分类未选择时拒绝写库，让用户继续校对，
        // 杜绝「无分类账单」被静默写入（QA F-02 闭环）。
        val categoryId = decision.categoryId ?: candidate.categoryId ?: 0L
        if (categoryId <= 0L) {
            return Result.Error(
                com.azu.bookkeeper.core.AppError.Validation(
                    code = "E_VALID_007", userMessage = "请先为该账单选择分类"
                )
            )
        }

        val now = System.currentTimeMillis()
        val bill = Bill(
            bookId = bookId,
            billType = candidate.billType,
            amountFen = amountFen,
            categoryId = categoryId,
            accountFrom = decision.accountId ?: candidate.accountId ?: 0L,
            billTime = candidate.billTime ?: now,
            remark = decision.merchant.ifBlank { candidate.merchant },
            source = BillSource.AUTO_CAPTURE,
            captureType = candidate.captureType,
            captureImagePath = candidate.imagePath,
            ocrConfidence = candidate.ocrConfidence,
            ocrRawText = candidate.rawText.take(MAX_RAW_TEXT_LENGTH),
            verifyStatus = VerifyStatus.USER_CORRECTED,
            createAt = now,
            updateAt = now
        )
        return billRepository.addWithBalanceTx(
            bill = bill,
            sourceEventId = decision.eventId,
            // 主理人裁决：用户介入（含低置信度确认 / 改写）→ USER_CORRECTED 终态
            terminalStatus = CaptureEventStatus.USER_CORRECTED
        ).also { result ->
            if (result.isSuccess) {
                // USER_CORRECTED 终态：确认入账成功后清理截图缓存
                deleteCaptureImage(pending.imagePath, "verify_bill_created")
                Timber.d("verified bill created eventId=%d", decision.eventId)
            }
        }
    }

    private companion object {
        const val MAX_RAW_TEXT_LENGTH = 2000
        const val DUPLICATE_NOTICE_COOLDOWN_MS = 8_000L
    }
}

package com.azu.bookkeeper.automation.capture

import com.azu.bookkeeper.automation.classify.ClassificationRuleEngine
import com.azu.bookkeeper.automation.dedup.DuplicateDetector
import com.azu.bookkeeper.automation.ocr.ConfidenceCalculator
import com.azu.bookkeeper.automation.ocr.ExtractedFields
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrEngine
import com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer
import com.azu.bookkeeper.automation.receiver.SmsSummary
import com.azu.bookkeeper.automation.receiver.TimeSource
import com.azu.bookkeeper.automation.verify.VerificationController
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.core.composeRemark
import com.azu.bookkeeper.core.normalizeRemark
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.data.local.db.entity.CaptureEventEntity
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 三路 / 四路捕获汇流中心（主架构 §5.6 + 增量 §2.6）。
 *
 * 职责：eventId 幂等 → 字段抽取（图像走 OCR，文本源直读）→ 规则分类 → 六因子置信度 →
 * 去重 → 高分自动入账 / 低分进校对队列。所有写库统一经 BillRepository 事务入口。
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    private val captureDao: CaptureDao,
    private val billDao: BillDao,
    private val ocrEngine: OcrEngine,
    private val extractor: FieldExtractor,
    private val analyzer: PaymentTextAnalyzer,
    private val confidence: ConfidenceCalculator,
    private val classifier: ClassificationRuleEngine,
    private val dedup: DuplicateDetector,
    private val verifier: VerificationController,
    private val billRepository: BillRepository,
    private val bookRepository: BookRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val automationSettings: AutomationSettingsDataStore,
    private val encryptedFiles: EncryptedFileDataSource,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun onCapture(event: CaptureEvent) = withContext(ioDispatcher) {
        // 1) eventId 幂等：同一捕获事件重复投递只处理一次
        if (captureDao.findByEventId(event.eventId) != null) return@withContext

        // 2) 落库捕获事件（PENDING 非终态）
        captureDao.insert(
            CaptureEventEntity(
                eventId = event.eventId,
                captureType = event.captureType.code,
                rawText = event.rawText,
                imagePath = event.imagePath,
                billTime = event.billTime,
                status = CaptureEventStatus.PENDING.code
            )
        )

        val bookId = settings.defaultBookId.first().takeIf { it > 0L }
            ?: bookRepository.findDefault().getOrNull()?.bookId
            ?: return@withContext
        val accounts = accountRepository.observeAll(bookId).first()
        val threshold = automationSettings.ocrThreshold.first()

        // 3) 字段抽取：短信源读结构化摘要（不再跑正则）；图像源走 OCR；其余文本源直读
        val isSms = event.captureType == CaptureType.SMS
        val timeSource = event.timeSource
        var fields: ExtractedFields
        // 短信解析已按银行简称 + 卡尾精配出账户 ID，必须透传，避免多卡用户记错账户（QA F-04）。
        val smsAccountId: Long
        val ocrResult = if (isSms) {
            val (parsedFields, parsedAccountId) = fieldsFromSmsSummary(event.rawText)
            fields = parsedFields
            smsAccountId = parsedAccountId
            null // 文本源 OCR 因子取满值
        } else if (event.imagePath.isNotBlank()) {
            val result = ocrEngine.recognizeFromFile(event.imagePath)
            fields = extractor.extract(result.rawText, fallbackTime = event.billTime.takeIf { it > 0L })
            smsAccountId = 0L
            result
        } else {
            fields = extractor.extract(event.rawText, fallbackTime = event.billTime.takeIf { it > 0L })
            smsAccountId = 0L
            null
        }

        // v1.3.1 统一两条识别链路：自动识屏过去只用 FieldExtractor，摇一摇用
        // PaymentTextAnalyzer + FieldExtractor，同一页面两条路会抽到不同商户/备注。
        // 现在都用同一个 analyzer 择优，并把展示备注统一写成「商户 · 购买内容」——
        // 这也让去重键与摇一摇写出的账单备注一致，跨链路重复才会被识别出来。
        val displayRemark: String = if (!isSms && event.rawText.isNotBlank()) {
            val analysis = runCatching { analyzer.analyze(event.rawText) }.getOrNull()
            if (analysis != null) {
                val bestMerchant = analysis.merchant.takeIf { it.isNotBlank() } ?: fields.merchant
                fields = fields.copy(
                    amountFen = analysis.amountFen ?: fields.amountFen,
                    merchant = bestMerchant
                )
                composeRemark(bestMerchant, analysis.goods)
            } else {
                composeRemark(fields.merchant, "")
            }
        } else {
            composeRemark(fields.merchant, "")
        }

        // 4) 分类（账户映射复用同一份 accounts）；短信精配账户优先级高于通用 matcher 猜测
        val baseRuleHit = classifier.classify(
            merchant = fields.merchant,
            rawText = if (isSms) "" else event.rawText,
            packageName = event.packageName,
            accounts = accounts,
            accountHint = fields.accountHint
        )
        val ruleHit = if (smsAccountId > 0L) baseRuleHit.copy(accountId = smsAccountId) else baseRuleHit

        // 5) 六因子置信度：文本源（读屏/通知/短信）OCR 因子取 1.0
        // v1.3.0：页面识别明确为「支付成功页」时给 0.05 加成——读屏链路已用 className /
        // 完成态文案确认了页面类型，比只看 OCR 六因子更可靠。加成不绕过 QA F-02 的
        // 分类/账户硬约束，只让字段齐全的支付结果更容易自动入账。
        val textSource = isSms || event.imagePath.isBlank()
        val screenBonus = if (event.screenType == PaymentScreen.SUCCESS.name) 0.05f else 0f
        val score = (confidence.calculate(
            result = ocrResult,
            fields = fields,
            ruleHit = ruleHit,
            textSource = textSource,
            timeSource = timeSource,
            businessTag = event.businessTag
        ) + screenBonus).coerceIn(0f, 1f)

        val candidate = CaptureCandidate(
            eventId = event.eventId,
            bookId = bookId,
            captureType = event.captureType,
            packageName = event.packageName,
            rawText = event.rawText,
            amountFen = fields.amountFen,
            merchant = displayRemark,
            billTime = fields.billTime,
            categoryId = ruleHit.categoryId.takeIf { it > 0L },
            accountId = ruleHit.accountId.takeIf { it > 0L },
            imagePath = event.imagePath,
            ocrConfidence = ocrResult?.confidence ?: 1f,
            finalScore = score
        )

        // 6) 去重：短信源用可配容差（默认 180s），其余精确键
        val toleranceSec = if (isSms) automationSettings.smsDedupToleranceSec.first() else 0L
        if (dedup.isDuplicateWithTolerance(candidate, toleranceSec)) {
            // 先去重命中不直接丢弃：若近 24 小时内同账本 + 同商户 + 同金额已有 >=2 笔，
            // 本次就是第 3 笔，属于「疑似重复」而不是确定重复——转人工校验让用户判断
            // （用户可能确实连续买了 3 次同价商品）。截图文件此时不删，校对弹窗还要看图。
            val recentSame = countRecentSameAmountMerchant(bookId, candidate)
            if (recentSame >= SUSPECTED_DUPLICATE_HIT_THRESHOLD) {
                captureDao.markStatus(event.eventId, CaptureEventStatus.LOW_CONFIDENCE.code)
                // v1.3.1：疑似重复走底部轻卡片，不再发通知栏确认
                verifier.requestVerify(candidate, duplicate = true)
                Timber.d(
                    "suspected duplicate, sent to verify eventId=%d amount=%d hits=%d",
                    event.eventId, candidate.amountFen ?: 0L, recentSame
                )
                return@withContext
            }
            // 确定重复：v1.3.1 不再静默丢弃——弹一张 3 秒消失的轻卡片，
            // 明确告诉用户「已经有同一笔，已跳过」，避免误以为识屏失败。
            verifier.notifyDuplicated(candidate.amountFen ?: 0L, displayRemark)
            captureDao.markStatus(event.eventId, CaptureEventStatus.DISCARDED.code)
            // 终态清理：去重丢弃的截图没有保留价值，删除加密文件释放存储
            deleteCaptureFile(event.imagePath, "dedup_discarded")
            Timber.d("capture deduped eventId=%d", event.eventId)
            return@withContext
        }

        // 7) 强制校对：字段缺失 / 分数不足 / 分类或账户无效 → 低置信度队列；否则自动入账。
        // categoryId<=0 的候选即使分数达标也绝不能直接建单，否则会产生「无分类账单」（QA F-02）；
        // 交由校对弹窗让用户选择分类 / 账户，符合 PRD 22.10「未命中归其他支出并弹窗校对」。
        val invalidBillFields = (candidate.categoryId ?: 0L) <= 0L || (candidate.accountId ?: 0L) <= 0L
        if (candidate.needsVerification || score < threshold || invalidBillFields) {
            captureDao.markStatus(event.eventId, CaptureEventStatus.LOW_CONFIDENCE.code)
            verifier.requestVerify(candidate)
            return@withContext
        }

        val bill = buildBill(candidate, bookId)
        when (val result = billRepository.addWithBalanceTx(
            bill = bill,
            sourceEventId = event.eventId,
            terminalStatus = CaptureEventStatus.CONSUMED_AUTO
        )) {
            is Result.Success -> {
                // 终态清理：自动入账成功后截图缓存不再需要，删除加密文件并丢弃内存候选中的路径
                deleteCaptureFile(candidate.imagePath, "auto_bill_created")
                // v1.3.1：高分自动入账后给一次轻打扰成功反馈（3 秒自消），不再「什么都不说」
                verifier.notifyRecorded(candidate.amountFen ?: 0L, displayRemark)
                Timber.d("auto bill created id=%d score=%.2f", result.data, score)
            }
            is Result.Error -> {
                captureDao.markStatus(event.eventId, CaptureEventStatus.LOW_CONFIDENCE.code)
                verifier.requestVerify(candidate)
            }
            Result.Loading -> Unit
        }
    }

    /**
     * 终态后清理加密截图缓存（自动入账成功 / 去重丢弃）。
     * 仅删除文件、不改 DB schema；内存候选中的 imagePath 已在调用前不再被引用。
     * 删除失败只记 Timber 日志不抛异常，避免影响主流程。
     * LOW_CONFIDENCE / 校对中路径不调用本方法，保留文件供校对弹窗看图；
     * 校对终态（USER_CORRECTED / DISCARDED）的删除由 VerificationController 负责。
     */
    private suspend fun deleteCaptureFile(imagePath: String, reason: String) {
        if (imagePath.isBlank()) return
        val deleted = encryptedFiles.deleteFile(imagePath)
        Timber.d(
            "capture image cleanup deleted=%b reason=%s path=%s",
            deleted, reason, imagePath
        )
    }

    /**
     * 短信摘要 → 字段：完全跳过正则，直接读结构化字段（增量 §2.3）。
     * 返回 Pair(fields, accountId)：accountId 为短信解析阶段按银行简称 + 卡尾精配出的账户，
     * 必须透传给分类结果，避免多卡用户「B 卡扣款记到 A 卡」（QA F-04）。
     */
    private fun fieldsFromSmsSummary(rawText: String): Pair<ExtractedFields, Long> = runCatching {
        val summary = json.decodeFromString(SmsSummary.serializer(), rawText)
        val fields = ExtractedFields(
            amountFen = summary.amountFen.takeIf { it > 0L },
            merchant = summary.merchant,
            billTime = summary.billTime.takeIf { it > 0L },
            accountHint = ""
        )
        fields to (summary.acct.takeIf { it > 0L } ?: 0L)
    }.getOrDefault(ExtractedFields() to 0L)

    /**
     * 统计近 24 小时内同账本 + 同商户（写库时统一归一化）+ 同金额的账单条数。
     *
     * 商户为空或金额非正时无从判断重复程度，返回 0（保持原有「确定重复 → 丢弃」语义）。
     * 查询异常只记日志并返回 0，绝不因为统计失败而丢失正常记账流程。
     */
    private suspend fun countRecentSameAmountMerchant(
        bookId: Long,
        candidate: CaptureCandidate
    ): Int {
        val amountFen = candidate.amountFen ?: 0L
        val merchant = candidate.merchant.normalizeRemark()
        if (amountFen <= 0L || merchant.isBlank()) return 0
        val sinceMillis = System.currentTimeMillis() - SUSPECTED_DUPLICATE_WINDOW_MILLIS
        return try {
            billDao.countRecentSameAmountMerchant(
                bookId = bookId,
                merchant = merchant,
                amountFen = amountFen,
                sinceMillis = sinceMillis
            )
        } catch (throwable: Throwable) {
            Timber.w(throwable, "count recent same amount merchant failed")
            0
        }
    }

    /** 高分候选 → 自动账单（verifyStatus=auto_passed，source=auto_capture）。 */
    private fun buildBill(candidate: CaptureCandidate, bookId: Long): Bill {
        val now = System.currentTimeMillis()
        return Bill(
            bookId = bookId,
            billType = BillType.EXPENSE,
            amountFen = candidate.amountFen ?: 0L,
            categoryId = candidate.categoryId ?: 0L,
            accountFrom = candidate.accountId ?: 0L,
            billTime = candidate.billTime ?: now,
            remark = candidate.merchant,
            source = BillSource.AUTO_CAPTURE,
            captureType = candidate.captureType,
            captureImagePath = candidate.imagePath,
            ocrConfidence = candidate.ocrConfidence,
            ocrRawText = candidate.rawText.take(MAX_RAW_TEXT_LENGTH),
            verifyStatus = VerifyStatus.AUTO_PASSED,
            createAt = now,
            updateAt = now
        )
    }

    private companion object {
        const val MAX_RAW_TEXT_LENGTH = 2000

        /** 疑似重复判定的回溯窗口：近 24 小时。 */
        const val SUSPECTED_DUPLICATE_WINDOW_MILLIS = 24L * 60L * 60L * 1000L

        /** 窗口内已有 >=2 笔同账本/同商户/同金额账单时，本次（第 3 笔）转人工校验。 */
        const val SUSPECTED_DUPLICATE_HIT_THRESHOLD = 2
    }
}

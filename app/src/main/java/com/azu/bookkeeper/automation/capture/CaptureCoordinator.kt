package com.azu.bookkeeper.automation.capture

import com.azu.bookkeeper.automation.classify.ClassificationRuleEngine
import com.azu.bookkeeper.automation.dedup.DuplicateDetector
import com.azu.bookkeeper.automation.ocr.ConfidenceCalculator
import com.azu.bookkeeper.automation.ocr.ExtractedFields
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrEngine
import com.azu.bookkeeper.automation.receiver.SmsSummary
import com.azu.bookkeeper.automation.receiver.TimeSource
import com.azu.bookkeeper.automation.verify.VerificationController
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.data.local.db.entity.CaptureEventEntity
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
    private val ocrEngine: OcrEngine,
    private val extractor: FieldExtractor,
    private val confidence: ConfidenceCalculator,
    private val classifier: ClassificationRuleEngine,
    private val dedup: DuplicateDetector,
    private val verifier: VerificationController,
    private val billRepository: BillRepository,
    private val bookRepository: BookRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val automationSettings: AutomationSettingsDataStore,
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
        val fields: ExtractedFields
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
        val textSource = isSms || event.imagePath.isBlank()
        val score = confidence.calculate(
            result = ocrResult,
            fields = fields,
            ruleHit = ruleHit,
            textSource = textSource,
            timeSource = timeSource,
            businessTag = event.businessTag
        )

        val candidate = CaptureCandidate(
            eventId = event.eventId,
            bookId = bookId,
            captureType = event.captureType,
            packageName = event.packageName,
            rawText = event.rawText,
            amountFen = fields.amountFen,
            merchant = fields.merchant,
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
            captureDao.markStatus(event.eventId, CaptureEventStatus.DISCARDED.code)
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
            is Result.Success -> Timber.d("auto bill created id=%d score=%.2f", result.data, score)
            is Result.Error -> {
                captureDao.markStatus(event.eventId, CaptureEventStatus.LOW_CONFIDENCE.code)
                verifier.requestVerify(candidate)
            }
            Result.Loading -> Unit
        }
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
    }
}

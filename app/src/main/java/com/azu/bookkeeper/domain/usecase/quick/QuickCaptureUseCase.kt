package com.azu.bookkeeper.domain.usecase.quick

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.BillQuickPrefill
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickCaptureResult
import com.azu.bookkeeper.domain.model.ResolvedBillInput
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.usecase.bill.AddBillUseCase
import javax.inject.Inject

/**
 * 快捷补记唯一写入口（T7 / AC-14、AC-16）。
 *
 * 校验 + 幂等短路后，统一调用 [BillRepository.addWithBalanceTx]，
 * 显式传入 `terminalStatus = USER_CORRECTED`：同一 @Transaction 内完成
 * 插账单 + 调余额 + 把来源捕获事件置终态，杜绝「已补记仍重复提醒」。
 *
 * 契约层不新增 DB 列（增量架构 §1.4 Q-DY-3）：fromCaptureEventId 只在内存/Intent 中流转。
 */
class QuickCaptureUseCase @Inject constructor(
    private val billRepository: BillRepository,
    private val addBillUseCase: AddBillUseCase
) {

    /** 表单最终确认后的保存动作；取消不应调用本方法。 */
    suspend operator fun invoke(
        request: QuickCaptureRequest,
        resolved: ResolvedBillInput
    ): QuickCaptureResult {
        if (resolved.amountFen <= 0L) {
            return QuickCaptureResult.Rejected(AppError.BillAmount())
        }
        if (isAlreadyHandled(request)) {
            return QuickCaptureResult.Rejected(
                AppError.Automation(
                    code = "E_AUTO_005",
                    userMessage = "该笔已补记，无需重复添加"
                )
            )
        }

        // 有来源事件时一律视为用户改写；纯快捷入口按普通用户确认标记。
        val verifyStatus = if (request.fromCaptureEventId != null) {
            VerifyStatus.USER_CORRECTED
        } else {
            resolved.verifyStatus
        }
        val bill = Bill(
            billId = 0L,
            bookId = resolved.bookId,
            billType = resolved.billType,
            amountFen = resolved.amountFen,
            categoryId = if (resolved.billType == BillType.TRANSFER) 0L else resolved.categoryId,
            tagIds = resolved.tagIds,
            accountFrom = resolved.accountFrom,
            accountTo = resolved.accountTo,
            billTime = resolved.billTime,
            remark = resolved.merchant,
            imagePaths = resolved.imagePaths,
            cycleType = resolved.cycleType,
            source = request.source,
            verifyStatus = verifyStatus
        )
        // 复用 T3 表单校验规则，避免两套校验口径。
        addBillUseCase.validate(bill)?.let { error ->
            return QuickCaptureResult.Rejected(error)
        }

        return when (
            val result = billRepository.addWithBalanceTx(
                bill = bill,
                sourceEventId = request.fromCaptureEventId,
                // 主理人裁决：用户介入的补记必须显式 USER_CORRECTED，不依赖默认 CONSUMED_AUTO。
                terminalStatus = CaptureEventStatus.USER_CORRECTED
            )
        ) {
            is Result.Success -> QuickCaptureResult.Saved(result.data)
            is Result.Error -> QuickCaptureResult.Rejected(result.error)
            Result.Loading -> QuickCaptureResult.Rejected(AppError.Unknown())
        }
    }

    /**
     * 幂等检查：来源事件不存在或已处于终态（CONSUMED_AUTO / USER_CORRECTED / DISCARDED）
     * 时视为已处理，表单在 init 阶段即可短路，不产生重复建单。
     */
    suspend fun isAlreadyHandled(request: QuickCaptureRequest): Boolean {
        val eventId = request.fromCaptureEventId ?: return false
        return billRepository.isCaptureEventHandled(eventId)
    }

    /** 兼容直接从路由预填对象进入的调用方。 */
    suspend fun isAlreadyHandled(prefill: BillQuickPrefill): Boolean =
        isAlreadyHandled(prefill.toRequest())
}

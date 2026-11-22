package com.azu.bookkeeper.domain.usecase.auto

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/**
 * 自动入账提交用例（架构 §5.3：source=auto_capture / verify=auto_passed）。
 * 仍走 BillRepository 单一 @Transaction 写入口，并把捕获事件在事务内置终态。
 */
class CommitAutoBillUseCase @Inject constructor(
    private val repository: BillRepository
) {
    suspend operator fun invoke(
        bill: Bill,
        sourceEventId: Long? = null
    ): Result<Long> = repository.addWithBalanceTx(
        bill = bill.copy(
            source = BillSource.AUTO_CAPTURE,
            billType = bill.billType.takeIf { it != BillType.TRANSFER } ?: BillType.EXPENSE,
            verifyStatus = VerifyStatus.AUTO_PASSED
        ),
        sourceEventId = sourceEventId,
        terminalStatus = CaptureEventStatus.CONSUMED_AUTO
    )
}

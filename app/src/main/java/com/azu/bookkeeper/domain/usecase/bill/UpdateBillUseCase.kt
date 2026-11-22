package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 修改账单：事务内反向回滚旧余额再应用新余额（PRD §9 示例：100→200 再扣 100）。 */
class UpdateBillUseCase @Inject constructor(
    private val repository: BillRepository,
    private val addBillUseCase: AddBillUseCase
) {

    suspend operator fun invoke(bill: Bill): Result<Unit> {
        if (bill.billId <= 0L) {
            return Result.Error(AppError.Validation(code = "E_VALID_008", userMessage = "账单不存在"))
        }
        addBillUseCase.validate(bill)?.let { return Result.Error(it) }
        return repository.updateWithBalanceTx(
            bill.copy(updateAt = DateTimeUtils.nowMillis())
        )
    }
}

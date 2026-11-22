package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 删除账单：事务内反向回滚账户余额。 */
class DeleteBillUseCase @Inject constructor(
    private val repository: BillRepository
) {

    suspend operator fun invoke(billId: Long): Result<Unit> {
        if (billId <= 0L) {
            return Result.Error(AppError.Validation(code = "E_VALID_009", userMessage = "账单不存在"))
        }
        return repository.deleteWithBalanceTx(billId)
    }
}

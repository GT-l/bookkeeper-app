package com.azu.bookkeeper.domain.usecase.account

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.repository.AccountRepository
import javax.inject.Inject

/** 余额校准：按用户输入的实际余额与当前余额之差生成调整账单。 */
class CalibrateBalanceUseCase @Inject constructor(
    private val accountRepository: AccountRepository,
    private val applyBalanceDelta: ApplyBalanceDeltaUseCase
) {

    suspend operator fun invoke(accountId: Long, actualBalanceFen: Long): Result<Long> {
        val account = when (val result = accountRepository.findById(accountId)) {
            is Result.Success -> result.data
                ?: return Result.Error(AppError.Balance(userMessage = "账户不存在"))
            is Result.Error -> return Result.Error(result.error)
            Result.Loading -> return Result.Error(AppError.Balance())
        }
        val delta = actualBalanceFen - account.balanceFen
        if (delta == 0L) return Result.Success(0L)
        return applyBalanceDelta(
            bookId = account.bookId,
            accountId = accountId,
            deltaFen = delta,
            billTime = DateTimeUtils.nowMillis(),
            remark = "余额校准：${account.name}"
        )
    }
}

package com.azu.bookkeeper.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.CycleType
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/** 周期账单：每天扫描模板，命中当天则经 BillRepository 事务入口生成账单。 */
@HiltWorker
class RecurringBillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val billRepository: BillRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): WorkResult {
        val bookId = settings.defaultBookId.first().takeIf { it > 0L }
            ?: bookRepository.findDefault().getOrNull()?.bookId
            ?: return WorkResult.success()
        val now = DateTimeUtils.nowMillis()
        val dayStart = DateTimeUtils.startOfDay(now)
        val dayEnd = DateTimeUtils.endOfDay(now)
        val existing = billRepository.observeBills(bookId).first()
        var created = 0

        existing.filter { it.cycleType != CycleType.NONE && it.cycleEndTime > now }
            .forEach { template ->
                val occurrence = nextOccurrence(template.billTime, template.cycleType, dayStart, dayEnd)
                    ?: return@forEach
                val candidate = template.copy(billId = 0L, billTime = occurrence)
                val duplicate = billRepository.findDuplicate(candidate.dedupKey()).getOrNull()
                if (duplicate == null) {
                    when (billRepository.addWithBalanceTx(
                        candidate.copy(
                            billId = 0L,
                            source = BillSource.MANUAL,
                            createAt = 0L,
                            updateAt = 0L
                        )
                    )) {
                        is Result.Success -> created++
                        is Result.Error -> Unit
                        Result.Loading -> Unit
                    }
                }
            }
        return WorkResult.success()
    }

    /** 从模板时间推进到 [dayStart]~[dayEnd] 窗口内的下一次发生时间。 */
    private fun nextOccurrence(
        templateTime: Long,
        cycle: CycleType,
        dayStart: Long,
        dayEnd: Long
    ): Long? {
        var cursor = templateTime
        repeat(MAX_ITERATIONS) {
            if (cursor in dayStart..dayEnd) return cursor
            if (cursor > dayEnd) return null
            cursor = when (cycle) {
                CycleType.DAILY -> DateTimeUtils.plusMonths(cursor, 0L) + 86_400_000L
                CycleType.WEEKLY -> DateTimeUtils.plusMonths(cursor, 0L) + 7L * 86_400_000L
                CycleType.MONTHLY -> DateTimeUtils.plusMonths(cursor, 1L)
                CycleType.YEARLY -> DateTimeUtils.plusMonths(cursor, 12L)
                CycleType.NONE -> return null
            }
        }
        return null
    }

    private companion object {
        const val MAX_ITERATIONS = 500
    }
}

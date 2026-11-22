package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.chunkedBySize
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 批量导入：默认按去重键跳过重复账单，每 500 条一批并回调进度。 */
class BulkInsertBillsUseCase @Inject constructor(
    private val repository: BillRepository
) {

    suspend operator fun invoke(
        bills: List<Bill>,
        skipDuplicates: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> {
        val candidates = if (skipDuplicates) {
            bills.filter { bill ->
                when (val existing = repository.findDuplicate(bill.dedupKey())) {
                    is Result.Success -> existing.data == null
                    is Result.Error -> true // 查询失败不阻塞导入，由后续写入事务保证原子性
                    Result.Loading -> true
                }
            }
        } else {
            bills
        }

        var inserted = 0
        candidates.chunkedBySize(Constants.IMPORT_BATCH_SIZE).forEach { batch ->
            when (val result = repository.bulkInsert(batch)) {
                is Result.Success -> {
                    inserted += result.data
                    onProgress(inserted, candidates.size)
                }
                is Result.Error -> return result
                Result.Loading -> Unit
            }
        }
        onProgress(inserted, candidates.size)
        return Result.Success(inserted)
    }
}

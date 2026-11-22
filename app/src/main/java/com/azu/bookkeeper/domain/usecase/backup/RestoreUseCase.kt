package com.azu.bookkeeper.domain.usecase.backup

import com.azu.bookkeeper.domain.repository.BackupRepository
import javax.inject.Inject

/** 从加密备份恢复（覆盖前二次确认由 UI 负责）。 */
class RestoreUseCase @Inject constructor(
    private val repository: BackupRepository
) {
    suspend operator fun invoke(path: String) = repository.restoreBackup(path)
}

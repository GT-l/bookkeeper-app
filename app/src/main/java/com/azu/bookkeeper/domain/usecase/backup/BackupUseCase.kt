package com.azu.bookkeeper.domain.usecase.backup

import com.azu.bookkeeper.domain.repository.BackupRepository
import javax.inject.Inject

/** 创建全账本加密备份，返回文件路径供 UI 分享 / 保存。 */
class BackupUseCase @Inject constructor(
    private val repository: BackupRepository
) {
    suspend operator fun invoke() = repository.createBackup()
}

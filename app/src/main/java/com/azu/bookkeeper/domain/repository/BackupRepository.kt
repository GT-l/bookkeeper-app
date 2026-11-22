package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result

/** 备份文件元信息。 */
data class BackupFileInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long
)

interface BackupRepository {

    /** 全账本加密备份，返回文件路径。 */
    suspend fun createBackup(): Result<String>

    /** 从加密备份恢复（覆盖前二次确认由 UI 负责）。 */
    suspend fun restoreBackup(path: String): Result<Unit>

    suspend fun listBackups(): Result<List<BackupFileInfo>>

    suspend fun deleteBackup(path: String): Result<Unit>

    /** 把 SAF 选中的外部 .bak 复制进私有备份目录，返回可恢复的绝对路径。 */
    suspend fun importBackupFile(uriString: String): Result<String>
}

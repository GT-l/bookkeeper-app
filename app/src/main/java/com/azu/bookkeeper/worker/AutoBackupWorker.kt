package com.azu.bookkeeper.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.backup.BackupUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/** 自动备份：每天由 WorkManager 触发，关闭开关时直接成功退出。 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupUseCase: BackupUseCase,
    private val settings: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): androidx.work.Result {
        if (!settings.autoBackupEnabled.first()) return androidx.work.Result.success()
        return when (val result = backupUseCase()) {
            is Result.Success -> {
                settings.setLastBackupAt(DateTimeUtils.nowMillis())
                androidx.work.Result.success()
            }
            is Result.Error -> androidx.work.Result.retry()
            Result.Loading -> androidx.work.Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "bookkeeper_auto_backup"
    }
}

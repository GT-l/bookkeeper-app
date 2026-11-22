package com.azu.bookkeeper.ui.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.domain.repository.BackupFileInfo
import com.azu.bookkeeper.domain.repository.BackupRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.backup.BackupUseCase
import com.azu.bookkeeper.domain.usecase.backup.RestoreUseCase
import com.azu.bookkeeper.worker.AutoBackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** 备份与恢复状态源：全账本加密包 + 覆盖前二次确认 + 自动备份开关。 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupUseCase: BackupUseCase,
    private val restoreUseCase: RestoreUseCase,
    private val backupRepository: BackupRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val backups: List<BackupFileInfo> = emptyList(),
        val autoBackupEnabled: Boolean = false,
        val lastBackupAt: Long = 0L,
        val pendingRestorePath: String? = null,
        val pendingRestoreName: String = "",
        val busy: Boolean = false,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.autoBackupEnabled.collect { enabled ->
                _uiState.update { it.copy(autoBackupEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(lastBackupAt = settings.lastBackupAt.first()) }
        }
        loadBackups()
    }

    fun loadBackups() {
        viewModelScope.launch {
            _uiState.update { it.copy(backups = backupRepository.listBackups().getOrDefault(emptyList())) }
        }
    }

    fun createBackup() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val result = backupUseCase()) {
                is Result.Success -> {
                    settings.setLastBackupAt(DateTimeUtils.nowMillis())
                    _uiState.update { it.copy(busy = false, message = "备份已创建：${result.data}") }
                }
                is Result.Error -> _uiState.update { it.copy(busy = false, message = result.error.userMessage) }
                Result.Loading -> Unit
            }
            loadBackups()
        }
    }

    /** SAF 选中的备份先复制进私有目录，再由用户二次确认覆盖。 */
    fun onRestorePicked(uriString: String) {
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val result = backupRepository.importBackupFile(uriString)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        busy = false,
                        pendingRestorePath = result.data,
                        pendingRestoreName = java.io.File(result.data).name
                    )
                }
                is Result.Error -> _uiState.update { it.copy(busy = false, message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun confirmRestore() {
        val path = _uiState.value.pendingRestorePath ?: return
        _uiState.update { it.copy(busy = true, message = null, pendingRestorePath = null) }
        viewModelScope.launch {
            when (val result = restoreUseCase(path)) {
                is Result.Success -> _uiState.update {
                    it.copy(busy = false, message = "恢复完成，请重新启动 App 以刷新全部页面")
                }
                is Result.Error -> _uiState.update { it.copy(busy = false, message = result.error.userMessage) }
                Result.Loading -> Unit
            }
            loadBackups()
        }
    }

    fun cancelRestore() = _uiState.update { it.copy(pendingRestorePath = null) }

    fun deleteBackup(path: String) {
        viewModelScope.launch {
            when (val result = backupRepository.deleteBackup(path)) {
                is Result.Success -> _uiState.update { it.copy(message = "备份已删除") }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
            loadBackups()
        }
    }

    /** 自动备份：每天凌晨触发一次（WorkManager 周期任务 + 初始延迟对齐次日 00:10）。 */
    fun setAutoBackup(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoBackupEnabled(enabled)
            _uiState.update { it.copy(autoBackupEnabled = enabled) }
            val workManager = WorkManager.getInstance(context)
            if (enabled) {
                val now = DateTimeUtils.localDateTime(DateTimeUtils.nowMillis())
                val next = now.plusDays(1).withHour(0).withMinute(10).withSecond(0).withNano(0)
                val delayMillis = java.time.Duration.between(now, next).toMillis().coerceAtLeast(0L)
                val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().build())
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    AutoBackupWorker.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                _uiState.update { it.copy(message = "已开启自动备份：每天 00:10") }
            } else {
                workManager.cancelUniqueWork(AutoBackupWorker.UNIQUE_WORK_NAME)
                _uiState.update { it.copy(message = "已关闭自动备份") }
            }
        }
    }


    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}

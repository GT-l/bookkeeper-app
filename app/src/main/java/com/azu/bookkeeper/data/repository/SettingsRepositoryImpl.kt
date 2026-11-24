package com.azu.bookkeeper.data.repository

import com.azu.bookkeeper.data.local.datastore.SettingsDataStore
import com.azu.bookkeeper.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** 设置仓储：纯委托，避免 UI 直接持有 DataStore。 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore
) : SettingsRepository {

    override val defaultBookId: Flow<Long> get() = dataStore.defaultBookId

    // 接口约定返回 Unit；DataStore.edit 返回 Preferences，必须用块体丢弃结果，
    // 否则表达式体会把返回类型推断为 Preferences，与 override 的 Unit 冲突。
    override suspend fun setDefaultBookId(value: Long) {
        dataStore.setDefaultBookId(value)
    }

    override val defaultAccountId: Flow<Long> get() = dataStore.defaultAccountId

    override suspend fun setDefaultAccountId(value: Long) {
        dataStore.setDefaultAccountId(value)
    }

    override val defaultExpenseCategoryId: Flow<Long> get() = dataStore.defaultExpenseCategoryId

    override suspend fun setDefaultExpenseCategoryId(value: Long) {
        dataStore.setDefaultExpenseCategoryId(value)
    }

    override val defaultIncomeCategoryId: Flow<Long> get() = dataStore.defaultIncomeCategoryId

    override suspend fun setDefaultIncomeCategoryId(value: Long) {
        dataStore.setDefaultIncomeCategoryId(value)
    }

    override val currency: Flow<String> get() = dataStore.currency

    override suspend fun setCurrency(value: String) {
        dataStore.setCurrency(value)
    }

    override val dateFormat: Flow<String> get() = dataStore.dateFormat

    override suspend fun setDateFormat(value: String) {
        dataStore.setDateFormat(value)
    }

    override val weekStartDay: Flow<Int> get() = dataStore.weekStartDay

    override suspend fun setWeekStartDay(value: Int) {
        dataStore.setWeekStartDay(value)
    }

    override val appLockEnabled: Flow<Boolean> get() = dataStore.appLockEnabled

    override suspend fun setAppLockEnabled(value: Boolean) {
        dataStore.setAppLockEnabled(value)
    }

    override val appLockPin: Flow<String> get() = dataStore.appLockPin

    override suspend fun setAppLockPin(value: String) {
        dataStore.setAppLockPin(value)
    }

    override val lastUnlockAt: Flow<Long> get() = dataStore.lastUnlockAt

    override suspend fun setLastUnlockAt(value: Long) {
        dataStore.setLastUnlockAt(value)
    }

    override val autoBackupEnabled: Flow<Boolean> get() = dataStore.autoBackupEnabled

    override suspend fun setAutoBackupEnabled(value: Boolean) {
        dataStore.setAutoBackupEnabled(value)
    }

    override val lastBackupAt: Flow<Long> get() = dataStore.lastBackupAt

    override suspend fun setLastBackupAt(value: Long) {
        dataStore.setLastBackupAt(value)
    }
}

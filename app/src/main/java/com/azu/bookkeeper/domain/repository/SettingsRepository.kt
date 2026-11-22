package com.azu.bookkeeper.domain.repository

import kotlinx.coroutines.flow.Flow

/** 基础设置仓储：只暴露 Flow 读与 suspend 写，UI 不直接访问 DataStore。 */
interface SettingsRepository {

    val defaultBookId: Flow<Long>
    suspend fun setDefaultBookId(value: Long)

    val defaultAccountId: Flow<Long>
    suspend fun setDefaultAccountId(value: Long)

    val defaultExpenseCategoryId: Flow<Long>
    suspend fun setDefaultExpenseCategoryId(value: Long)

    val defaultIncomeCategoryId: Flow<Long>
    suspend fun setDefaultIncomeCategoryId(value: Long)

    val currency: Flow<String>
    suspend fun setCurrency(value: String)

    val dateFormat: Flow<String>
    suspend fun setDateFormat(value: String)

    val weekStartDay: Flow<Int>
    suspend fun setWeekStartDay(value: Int)

    val appLockEnabled: Flow<Boolean>
    suspend fun setAppLockEnabled(value: Boolean)

    val appLockPin: Flow<String>
    suspend fun setAppLockPin(value: String)

    val lastUnlockAt: Flow<Long>
    suspend fun setLastUnlockAt(value: Long)

    val autoBackupEnabled: Flow<Boolean>
    suspend fun setAutoBackupEnabled(value: Boolean)

    val lastBackupAt: Flow<Long>
    suspend fun setLastBackupAt(value: Long)
}

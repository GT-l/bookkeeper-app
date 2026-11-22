package com.azu.bookkeeper.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.azu.bookkeeper.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.SETTINGS_DATASTORE
)

/** 基础设置 KV 存储（DataStore Preferences，异步无 ANR）。 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val DEFAULT_BOOK_ID = longPreferencesKey("default_book_id")
        val DEFAULT_ACCOUNT_ID = longPreferencesKey("default_account_id")
        val DEFAULT_EXPENSE_CATEGORY_ID = longPreferencesKey("default_expense_category_id")
        val DEFAULT_INCOME_CATEGORY_ID = longPreferencesKey("default_income_category_id")
        val CURRENCY = stringPreferencesKey("currency")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        val WEEK_START = intPreferencesKey("week_start")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN = stringPreferencesKey("app_lock_pin")
        val LAST_UNLOCK_AT = longPreferencesKey("last_unlock_at")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    }

    private val store: DataStore<Preferences> get() = context.settingsDataStore

    val defaultBookId: Flow<Long> = store.data.map { it[Keys.DEFAULT_BOOK_ID] ?: 0L }

    suspend fun setDefaultBookId(value: Long) = store.edit { it[Keys.DEFAULT_BOOK_ID] = value }

    val defaultAccountId: Flow<Long> = store.data.map { it[Keys.DEFAULT_ACCOUNT_ID] ?: 0L }

    suspend fun setDefaultAccountId(value: Long) = store.edit { it[Keys.DEFAULT_ACCOUNT_ID] = value }

    val defaultExpenseCategoryId: Flow<Long> =
        store.data.map { it[Keys.DEFAULT_EXPENSE_CATEGORY_ID] ?: 0L }

    suspend fun setDefaultExpenseCategoryId(value: Long) =
        store.edit { it[Keys.DEFAULT_EXPENSE_CATEGORY_ID] = value }

    val defaultIncomeCategoryId: Flow<Long> =
        store.data.map { it[Keys.DEFAULT_INCOME_CATEGORY_ID] ?: 0L }

    suspend fun setDefaultIncomeCategoryId(value: Long) =
        store.edit { it[Keys.DEFAULT_INCOME_CATEGORY_ID] = value }

    val currency: Flow<String> = store.data.map { it[Keys.CURRENCY] ?: "CNY" }

    suspend fun setCurrency(value: String) = store.edit { it[Keys.CURRENCY] = value }

    val dateFormat: Flow<String> = store.data.map { it[Keys.DATE_FORMAT] ?: "yyyy-MM-dd HH:mm" }

    suspend fun setDateFormat(value: String) = store.edit { it[Keys.DATE_FORMAT] = value }

    val weekStartDay: Flow<Int> = store.data.map { it[Keys.WEEK_START] ?: 1 }

    suspend fun setWeekStartDay(value: Int) = store.edit { it[Keys.WEEK_START] = value }

    val appLockEnabled: Flow<Boolean> = store.data.map { it[Keys.APP_LOCK_ENABLED] ?: false }

    suspend fun setAppLockEnabled(value: Boolean) = store.edit { it[Keys.APP_LOCK_ENABLED] = value }

    /** 应用锁数字密码（个人自用本地存储；生产强化可换 Keystore 哈希）。 */
    val appLockPin: Flow<String> = store.data.map { it[Keys.APP_LOCK_PIN] ?: "" }

    suspend fun setAppLockPin(value: String) = store.edit { it[Keys.APP_LOCK_PIN] = value }

    val lastUnlockAt: Flow<Long> = store.data.map { it[Keys.LAST_UNLOCK_AT] ?: 0L }

    suspend fun setLastUnlockAt(value: Long) = store.edit { it[Keys.LAST_UNLOCK_AT] = value }

    val autoBackupEnabled: Flow<Boolean> = store.data.map { it[Keys.AUTO_BACKUP_ENABLED] ?: false }

    suspend fun setAutoBackupEnabled(value: Boolean) =
        store.edit { it[Keys.AUTO_BACKUP_ENABLED] = value }

    val lastBackupAt: Flow<Long> = store.data.map { it[Keys.LAST_BACKUP_AT] ?: 0L }

    suspend fun setLastBackupAt(value: Long) = store.edit { it[Keys.LAST_BACKUP_AT] = value }
}

package com.azu.bookkeeper.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.azu.bookkeeper.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.automationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.AUTOMATION_DATASTORE
)

/** 自动化开关与阈值配置（T5 消费；T2 先把存储能力落地）。 */
@Singleton
class AutomationSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("automation_enabled")
        val SCREEN_CAPTURE_ENABLED = booleanPreferencesKey("screen_capture_enabled")
        val OCR_THRESHOLD = floatPreferencesKey("ocr_threshold")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val VERIFY_MODE = intPreferencesKey("verify_mode")
        val SERVICE_RUNNING = booleanPreferencesKey("service_running")
        val LAST_SERVICE_START_AT = longPreferencesKey("last_service_start_at")
        val DEDUP_ENABLED = booleanPreferencesKey("dedup_enabled")
        // T6：短信源开关 / 容差 / 抖音默认分类 / 悬浮球（T7 用）
        val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
        val SMS_DEDUP_TOLERANCE_SEC = intPreferencesKey("sms_dedup_tolerance_sec")
        val DOUYIN_DEFAULT_CATEGORY_ID = longPreferencesKey("douyin_default_category_id")
        val FLOATING_BALL_ENABLED = booleanPreferencesKey("floating_ball_enabled")
    }

    private val store: DataStore<Preferences> get() = context.automationDataStore

    val enabled: Flow<Boolean> = store.data.map { it[Keys.ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) = store.edit { it[Keys.ENABLED] = value }

    val screenCaptureEnabled: Flow<Boolean> =
        store.data.map { it[Keys.SCREEN_CAPTURE_ENABLED] ?: false }

    suspend fun setScreenCaptureEnabled(value: Boolean) =
        store.edit { it[Keys.SCREEN_CAPTURE_ENABLED] = value }

    val ocrThreshold: Flow<Float> =
        store.data.map { it[Keys.OCR_THRESHOLD] ?: Constants.DEFAULT_OCR_THRESHOLD }

    suspend fun setOcrThreshold(value: Float) = store.edit {
        it[Keys.OCR_THRESHOLD] = value.coerceIn(Constants.MIN_OCR_THRESHOLD, Constants.MAX_OCR_THRESHOLD)
    }

    val overlayEnabled: Flow<Boolean> = store.data.map { it[Keys.OVERLAY_ENABLED] ?: false }

    suspend fun setOverlayEnabled(value: Boolean) = store.edit { it[Keys.OVERLAY_ENABLED] = value }

    /** 0=自动 1=悬浮窗 2=通知 3=仅前台弹窗 */
    val verifyMode: Flow<Int> = store.data.map { it[Keys.VERIFY_MODE] ?: 0 }

    suspend fun setVerifyMode(value: Int) = store.edit { it[Keys.VERIFY_MODE] = value }

    val serviceRunning: Flow<Boolean> = store.data.map { it[Keys.SERVICE_RUNNING] ?: false }

    suspend fun setServiceRunning(value: Boolean) = store.edit { it[Keys.SERVICE_RUNNING] = value }

    val lastServiceStartAt: Flow<Long> = store.data.map { it[Keys.LAST_SERVICE_START_AT] ?: 0L }

    suspend fun setLastServiceStartAt(value: Long) =
        store.edit { it[Keys.LAST_SERVICE_START_AT] = value }

    val dedupEnabled: Flow<Boolean> = store.data.map { it[Keys.DEDUP_ENABLED] ?: true }

    suspend fun setDedupEnabled(value: Boolean) = store.edit { it[Keys.DEDUP_ENABLED] = value }

    // ---------- T6：短信 / 抖音 ----------

    /** 短信识别开关默认关闭；开启需用户主动授权 READ_SMS（AC-17）。 */
    val smsEnabled: Flow<Boolean> = store.data.map { it[Keys.SMS_ENABLED] ?: false }

    suspend fun setSmsEnabled(value: Boolean) = store.edit { it[Keys.SMS_ENABLED] = value }

    /** 短信容差去重窗口（秒），0~600，仅短信源启用。 */
    val smsDedupToleranceSec: Flow<Long> = store.data.map {
        (it[Keys.SMS_DEDUP_TOLERANCE_SEC] ?: Constants.SMS_DEDUP_TOLERANCE_SEC.toInt()).toLong()
    }

    suspend fun setSmsDedupToleranceSec(value: Long) = store.edit {
        it[Keys.SMS_DEDUP_TOLERANCE_SEC] = value.coerceIn(0L, 600L).toInt()
    }

    /** 抖音默认分类：0 表示尚未配置，种子规则注入时回落到「购物」分类。 */
    val douyinDefaultCategoryId: Flow<Long> =
        store.data.map { it[Keys.DOUYIN_DEFAULT_CATEGORY_ID] ?: 0L }

    suspend fun setDouyinDefaultCategoryId(value: Long) = store.edit {
        it[Keys.DOUYIN_DEFAULT_CATEGORY_ID] = value
    }

    /** 悬浮球（T7 入口）预留开关，默认关闭。 */
    val floatingBallEnabled: Flow<Boolean> =
        store.data.map { it[Keys.FLOATING_BALL_ENABLED] ?: false }

    suspend fun setFloatingBallEnabled(value: Boolean) =
        store.edit { it[Keys.FLOATING_BALL_ENABLED] = value }
}

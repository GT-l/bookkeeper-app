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
import com.azu.bookkeeper.core.QuickGestureCache
import com.azu.bookkeeper.core.ShakeActions
import com.azu.bookkeeper.core.ShakeSensitivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val SERVICE_START_FAILURE_COUNT = intPreferencesKey("service_start_failure_count")
        val SERVICE_NEXT_RETRY_AT = longPreferencesKey("service_next_retry_at")
        val DEDUP_ENABLED = booleanPreferencesKey("dedup_enabled")
        // T6：短信源开关 / 容差 / 抖音默认分类 / 悬浮球（T7 用）
        val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
        val SMS_DEDUP_TOLERANCE_SEC = intPreferencesKey("sms_dedup_tolerance_sec")
        val DOUYIN_DEFAULT_CATEGORY_ID = longPreferencesKey("douyin_default_category_id")
        val FLOATING_BALL_ENABLED = booleanPreferencesKey("floating_ball_enabled")
        /** 系统手势触发记一笔：取值见 QuickGestureCodes；0=关闭。 */
        val QUICK_GESTURE = intPreferencesKey("quick_gesture")
        val SHAKE_ACTION = intPreferencesKey("shake_action")
        /** 摇一摇灵敏度：取值见 ShakeSensitivity（0 稳 / 1 标准 / 2 灵敏）。 */
        val SHAKE_SENSITIVITY = intPreferencesKey("shake_sensitivity")
    }

    private val store: DataStore<Preferences> get() = context.automationDataStore

    val enabled: Flow<Boolean> = store.data.map { it[Keys.ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) = store.edit { prefs ->
        prefs[Keys.ENABLED] = value
        if (value) {
            // 用户手动开启视为明确的重试意图：清掉失败计数与退避窗口，保证正常路径立即生效。
            prefs.remove(Keys.SERVICE_START_FAILURE_COUNT)
            prefs.remove(Keys.SERVICE_NEXT_RETRY_AT)
        }
    }

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

    // ---------- 服务启动失败退避（防止闪退 / 反复拉起） ----------

    val serviceStartFailureCount: Flow<Int> =
        store.data.map { it[Keys.SERVICE_START_FAILURE_COUNT] ?: 0 }

    val serviceNextRetryAt: Flow<Long> =
        store.data.map { it[Keys.SERVICE_NEXT_RETRY_AT] ?: 0L }

    /**
     * 探活 / 开机恢复拉起前统一询问：连续失败未达上限、且已过退避窗口才允许启动。
     */
    suspend fun canStartService(nowMillis: Long): Boolean {
        val prefs = store.data.first()
        val count = prefs[Keys.SERVICE_START_FAILURE_COUNT] ?: 0
        val nextRetryAt = prefs[Keys.SERVICE_NEXT_RETRY_AT] ?: 0L
        return count < MAX_SERVICE_START_FAILURES && nowMillis >= nextRetryAt
    }

    /** 启动成功：复位失败计数与退避窗口，并落盘运行状态。 */
    suspend fun markServiceStarted(nowMillis: Long) = store.edit { prefs ->
        prefs[Keys.SERVICE_RUNNING] = true
        prefs[Keys.LAST_SERVICE_START_AT] = nowMillis
        prefs.remove(Keys.SERVICE_START_FAILURE_COUNT)
        prefs.remove(Keys.SERVICE_NEXT_RETRY_AT)
    }

    /**
     * 记录一次启动失败：运行状态置 false，按指数退避设置下次允许时间。
     * @return 连续失败是否已达上限（调用方应据此关闭开关并提示用户）。
     */
    suspend fun recordServiceStartFailure(nowMillis: Long): Boolean {
        var failureCount = 0
        store.edit { prefs ->
            failureCount = (prefs[Keys.SERVICE_START_FAILURE_COUNT] ?: 0) + 1
            prefs[Keys.SERVICE_START_FAILURE_COUNT] = failureCount
            prefs[Keys.SERVICE_NEXT_RETRY_AT] = nowMillis + backoffAfterFailure(failureCount)
            prefs[Keys.SERVICE_RUNNING] = false
        }
        return failureCount >= MAX_SERVICE_START_FAILURES
    }

    /** 显式清除失败状态（手动开启时由 setEnabled(true) 完成，保留给测试 / 未来入口）。 */
    suspend fun resetServiceStartFailures() = store.edit { prefs ->
        prefs.remove(Keys.SERVICE_START_FAILURE_COUNT)
        prefs.remove(Keys.SERVICE_NEXT_RETRY_AT)
    }

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

    /** 用户选择的系统手势（指纹四向 / 摇一摇），0 表示不启用。 */
    val quickGesture: Flow<Int> = store.data.map { it[Keys.QUICK_GESTURE] ?: 0 }

    /** 摇一摇触发的动作：0=打开记一笔表单，1=直接识屏。 */
    val shakeAction: Flow<Int> = store.data.map { it[Keys.SHAKE_ACTION] ?: ShakeActions.OPEN_FORM }

    suspend fun setShakeAction(value: Int) {
        store.edit { it[Keys.SHAKE_ACTION] = value }
        QuickGestureCache.shakeAction = value
    }

    /** 摇一摇灵敏度：0 稳 / 1 标准 / 2 灵敏。 */
    val shakeSensitivity: Flow<Int> =
        store.data.map { it[Keys.SHAKE_SENSITIVITY] ?: ShakeSensitivity.STANDARD }

    suspend fun setShakeSensitivity(value: Int) {
        store.edit { it[Keys.SHAKE_SENSITIVITY] = value }
        QuickGestureCache.shakeSensitivity = value
    }

    suspend fun setQuickGesture(value: Int) {
        store.edit { it[Keys.QUICK_GESTURE] = value }
        // 辅助功能服务与主进程同进程，缓存同步更新保证手势立即生效（无需等服务重启）
        QuickGestureCache.code = value
    }

    companion object {
        /** 连续启动失败 3 次后自动关闭自动化开关，彻底停止重试。 */
        const val MAX_SERVICE_START_FAILURES = 3

        /** 退避窗口：第 1 次 1 分钟、第 2 次 5 分钟、第 3 次及以后 15 分钟。 */
        private val SERVICE_START_BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)

        /** 第 failureCount 次失败后的退避时长；failureCount <= 0 时无退避。 */
        fun backoffAfterFailure(failureCount: Int): Long {
            if (failureCount <= 0) return 0L
            return SERVICE_START_BACKOFF_MS[minOf(failureCount - 1, SERVICE_START_BACKOFF_MS.lastIndex)]
        }
    }
}

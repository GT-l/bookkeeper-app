package com.azu.bookkeeper.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationDisplayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notification_display_settings"
)

/** 常驻通知上要显示哪些内容、用哪种样式。独立于自动化开关，改动后即时生效。 */
data class NotificationDisplayPrefs(
    val style: Int = NotificationDisplayStyles.DETAILED,
    val showToday: Boolean = true,
    val showMonth: Boolean = true,
    val showBudget: Boolean = true,
    val showLastBill: Boolean = false,
    val showCount: Boolean = false,
    val showQuickAdd: Boolean = true,
    val showBallToggle: Boolean = true
)

object NotificationDisplayStyles {
    const val COMPACT = 0
    const val DETAILED = 1
    const val BIG = 2

    val ALL = intArrayOf(COMPACT, DETAILED, BIG)

    fun label(style: Int): String = when (style) {
        COMPACT -> "简洁"
        BIG -> "大字"
        else -> "明细"
    }
}

@Singleton
class NotificationDisplaySettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val STYLE = intPreferencesKey("style")
        val SHOW_TODAY = booleanPreferencesKey("show_today")
        val SHOW_MONTH = booleanPreferencesKey("show_month")
        val SHOW_BUDGET = booleanPreferencesKey("show_budget")
        val SHOW_LAST = booleanPreferencesKey("show_last")
        val SHOW_COUNT = booleanPreferencesKey("show_count")
        val SHOW_QUICK_ADD = booleanPreferencesKey("show_quick_add")
        val SHOW_BALL = booleanPreferencesKey("show_ball")
    }

    private val store: DataStore<Preferences> get() = context.notificationDisplayDataStore

    val settings: Flow<NotificationDisplayPrefs> = store.data.map { p ->
        NotificationDisplayPrefs(
            style = p[Keys.STYLE] ?: NotificationDisplayStyles.DETAILED,
            showToday = p[Keys.SHOW_TODAY] ?: true,
            showMonth = p[Keys.SHOW_MONTH] ?: true,
            showBudget = p[Keys.SHOW_BUDGET] ?: true,
            showLastBill = p[Keys.SHOW_LAST] ?: false,
            showCount = p[Keys.SHOW_COUNT] ?: false,
            showQuickAdd = p[Keys.SHOW_QUICK_ADD] ?: true,
            showBallToggle = p[Keys.SHOW_BALL] ?: true
        )
    }

    suspend fun update(transform: (NotificationDisplayPrefs) -> NotificationDisplayPrefs) {
        val current = settings.first()
        val next = transform(current)
        store.edit { p ->
            p[Keys.STYLE] = next.style
            p[Keys.SHOW_TODAY] = next.showToday
            p[Keys.SHOW_MONTH] = next.showMonth
            p[Keys.SHOW_BUDGET] = next.showBudget
            p[Keys.SHOW_LAST] = next.showLastBill
            p[Keys.SHOW_COUNT] = next.showCount
            p[Keys.SHOW_QUICK_ADD] = next.showQuickAdd
            p[Keys.SHOW_BALL] = next.showBallToggle
        }
    }
}


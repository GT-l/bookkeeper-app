package com.azu.bookkeeper.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.BudgetRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 桌面组件展示数据：今日已记笔数 + 当月预算余额（分）。 */
data class QuickWidgetSnapshot(
    val todayCount: Int = 0,
    val budgetRemainingFen: Long = 0L,
    val hasBudget: Boolean = false
)

/**
 * Glance 组件读取既有 T2 Repository 的 Hilt 入口。
 * GlanceAppWidget 实例由框架创建，无法构造器注入，故用 EntryPoint 访问单例图。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickWidgetEntryPoint {
    fun settings(): SettingsRepository
    fun bills(): BillRepository
    fun books(): BookRepository
    fun budgets(): BudgetRepository
}

/**
 * 桌面快捷记账组件：显示今日已记 / 预算余额，整块点击拉起带 APP_WIDGET 的表单。
 * 数据源全部复用 T2，不新增表、不新增写库路径（PRD 23.2.2）。
 */
class QuickCaptureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        val snapshot = withContext(Dispatchers.IO) { loadSnapshot(appContext) }
        provideContent { WidgetContent(snapshot, appContext) }
    }

    private suspend fun loadSnapshot(context: Context): QuickWidgetSnapshot {
        val deps = EntryPointAccessors.fromApplication(context, QuickWidgetEntryPoint::class.java)
        val bookId = deps.settings().defaultBookId.first().takeIf { it > 0L }
            ?: deps.books().findDefault().getOrNull()?.bookId
            ?: 0L
        if (bookId <= 0L) return QuickWidgetSnapshot()

        val now = DateTimeUtils.nowMillis()
        val todayBills = deps.bills().findByTimeRange(
            bookId = bookId,
            startMillis = DateTimeUtils.startOfDay(now),
            endMillis = DateTimeUtils.endOfDay(now)
        ).getOrNull().orEmpty()
        val budget = deps.budgets()
            .findByBookAndMonth(bookId, DateTimeUtils.monthKey(now))
            .getOrNull()
        val monthExpense = deps.bills().sumByType(
            bookId = bookId,
            startMillis = DateTimeUtils.startOfMonth(now),
            endMillis = DateTimeUtils.endOfMonth(now)
        ).getOrNull()?.get(BillType.EXPENSE) ?: 0L

        return QuickWidgetSnapshot(
            todayCount = todayBills.size,
            budgetRemainingFen = if (budget == null) 0L else budget.totalLimitFen - monthExpense,
            hasBudget = budget != null
        )
    }

    companion object {
        /** 账单保存成功后由 UI 触发刷新全部组件实例。 */
        suspend fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = GlanceAppWidgetManager(appContext)
            manager.getGlanceIds(QuickCaptureWidget::class.java).forEach { glanceId ->
                QuickCaptureWidget().update(appContext, glanceId)
            }
        }
    }
}

@Composable
private fun WidgetContent(snapshot: QuickWidgetSnapshot, context: Context) {
    val intent = QuickCaptureLauncher.intent(
        context,
        QuickCaptureRequest(entry = QuickEntry.APP_WIDGET)
    )
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.background)
            .padding(12.dp)
            .clickable(actionStartActivity(intent))
    ) {
        Text(
            text = "＋ 记一笔",
            style = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WidgetColors.accent
            )
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = "今日已记 ${snapshot.todayCount} 笔",
            style = TextStyle(fontSize = 13.sp, color = WidgetColors.primaryText)
        )
        Text(
            text = if (snapshot.hasBudget) {
                "预算余额 ${Money.format(snapshot.budgetRemainingFen)}"
            } else {
                "未设置月度预算"
            },
            style = TextStyle(fontSize = 13.sp, color = WidgetColors.secondaryText)
        )
    }
}

/**
 * Glance 1.1.0 的 ColorProvider 工厂函数为 internal/后续版本才公开，
 * 这里直接实现接口（1.0 起稳定公开 API），避免依赖 1.2.0 才有的 ColorProvider(Color)。
 */
private object WidgetColors {
    val background: ColorProvider = object : ColorProvider {
        override fun getColor(context: Context): Color = Color.White
    }
    val accent: ColorProvider = object : ColorProvider {
        override fun getColor(context: Context): Color = Color(0xFFC62828)
    }
    val primaryText: ColorProvider = object : ColorProvider {
        override fun getColor(context: Context): Color = Color(0xFF333333)
    }
    val secondaryText: ColorProvider = object : ColorProvider {
        override fun getColor(context: Context): Color = Color(0xFF666666)
    }
}

package com.azu.bookkeeper.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.usecase.stats.StatsGranularity
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.home.BillRow
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.stats.component.PieChartView
import com.azu.bookkeeper.ui.stats.component.StatsSummaryCards
import com.azu.bookkeeper.ui.stats.component.TrendChartView
import com.azu.bookkeeper.ui.stats.component.TrendMode
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/**
 * 统计报表。
 *
 * 这一版的主题是「和全站对齐」：顶栏用 [AppTopBar]，时间范围与页签都改成胶囊，
 * 图表全部装进 [SoftCard] 并配标题/说明，明细列表用「名次 + 金额 + 占比条」的卡片行，
 * 不再是裸 Row + Divider 拼出来的表格感。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    navController: NavController,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drill = state.drill

    // 底部 tab 的 saveState/restoreState 会复用 ViewModel，init 不会重跑。
    // 每次进入统计页主动刷新，保证换账本 / 新增账单后回到这里看到的是新数据。
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 截图成功 / 加载失败的提示自动消失，别一直压在页面上
    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(3_000L)
            viewModel.dismissMessage()
        }
    }

    // 下钻：独立的整页，不再在统计页顶上硬塞一行「返回统计」
    if (drill != null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppTopBar(
                    title = "明细",
                    subtitle = drill.title,
                    actions = {
                        TextButton(onClick = { viewModel.closeDrill() }) {
                            Text("返回统计", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                )
            }
        ) { padding ->
            if (state.drillBills.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    EmptyState("该条件下没有账单")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(items = state.drillBills, key = { it.billId }) { bill ->
                        BillRow(
                            bill = bill,
                            onClick = { navController.navigate(Screen.billDetail(bill.billId)) }
                        )
                    }
                }
            }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "统计",
                subtitle = listOf(
                    state.bookName.ifBlank { "当前账本" },
                    state.range.label
                ).joinToString(" · "),
                actions = {
                    // 日历 / 热力 / 同比环比入口：独立页面，不占 7 个页签
                    TextButton(onClick = { navController.navigate(Screen.CalendarStats.route) }) {
                        Text("日历", style = MaterialTheme.typography.labelMedium)
                    }
                    // 截图目前由 MPAndroidChart 组件完成；纯列表页没有可截的图表，
                    // 与其点完毫无反应，不如只在 1/2/3 三个图表页显示按钮。
                    if (state.tab in 1..3) {
                        TextButton(onClick = { viewModel.requestScreenshot() }) {
                            Text(text = "📷", fontSize = 16.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 时间范围：横向滚动的胶囊，选中填充主色
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = StatsRange.ALL, key = { it.name }) { range ->
                    StatPill(
                        label = range.label,
                        selected = state.range == range,
                        onClick = {
                            if (range == StatsRange.CUSTOM) {
                                viewModel.setCustomRange(state.bounds.first, state.bounds.second)
                            } else {
                                viewModel.setRange(range)
                            }
                        }
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = StatisticsViewModel.TAB_TITLES.withIndex().toList(), key = { it.index }) { entry ->
                    StatPill(
                        label = entry.value,
                        selected = state.tab == entry.index,
                        style = PillStyle.SOFT,
                        onClick = { viewModel.setTab(entry.index) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (state.tab) {
                    0 -> OverviewTab(state, viewModel)
                    1 -> CategoryPieTab(
                        title = "支出构成",
                        subtitle = "点扇区可下钻到对应分类",
                        slices = state.bundle.expenseByCategory.map { item ->
                            (state.categoryNames[item.categoryId] ?: "未分类") to item.amountFen / 100f
                        },
                        items = state.bundle.expenseByCategory,
                        names = state.categoryNames,
                        emptyText = "当前范围没有支出数据",
                        accent = ExpenseRed,
                        captureRequest = state.captureRequest,
                        onCaptured = viewModel::saveScreenshot,
                        onSliceClick = { index ->
                            state.bundle.expenseByCategory.getOrNull(index)?.let { item ->
                                viewModel.drillCategory(item.categoryId, BillType.EXPENSE)
                            }
                            Unit
                        },
                        onRowClick = { categoryId ->
                            viewModel.drillCategory(categoryId, BillType.EXPENSE)
                        }
                    )

                    2 -> CategoryPieTab(
                        title = "收入构成",
                        subtitle = "点扇区可下钻到对应分类",
                        slices = state.bundle.incomeByCategory.map { item ->
                            (state.categoryNames[item.categoryId] ?: "未分类") to item.amountFen / 100f
                        },
                        items = state.bundle.incomeByCategory,
                        names = state.categoryNames,
                        emptyText = "当前范围没有收入数据",
                        accent = IncomeGreen,
                        captureRequest = state.captureRequest,
                        onCaptured = viewModel::saveScreenshot,
                        onSliceClick = { index ->
                            state.bundle.incomeByCategory.getOrNull(index)?.let { item ->
                                viewModel.drillCategory(item.categoryId, BillType.INCOME)
                            }
                            Unit
                        },
                        onRowClick = { categoryId ->
                            viewModel.drillCategory(categoryId, BillType.INCOME)
                        }
                    )

                    3 -> TrendTab(state, viewModel)
                    4 -> CategoryTable(state, viewModel)
                    5 -> AccountTable(state, viewModel)
                    6 -> TagTable(state, viewModel)
                }

                state.message?.let { message ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 统一的胶囊筛选：PRIMARY 用于时间范围，SOFT 用于页签（选中浅底 + 主色字）。 */
private enum class PillStyle { PRIMARY, SOFT }

@Composable
private fun StatPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    style: PillStyle = PillStyle.PRIMARY
) {
    val background = when {
        selected && style == PillStyle.PRIMARY -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        selected && style == PillStyle.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = background,
        contentColor = content,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/** 概览：统计摘要卡（净结余主卡 + 预算完成度）。 */
@Composable
private fun OverviewTab(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            StatsSummaryCards(
                summary = state.bundle.summary,
                onIncomeClick = { viewModel.drillType(BillType.INCOME) },
                onExpenseClick = { viewModel.drillType(BillType.EXPENSE) }
            )
        }
    }
}

/** 收支饼图页：饼图卡 + 分类排行卡，两张卡一套。 */
@Composable
private fun CategoryPieTab(
    title: String,
    subtitle: String,
    slices: List<Pair<String, Float>>,
    items: List<com.azu.bookkeeper.domain.model.CategoryAmount>,
    names: Map<Long, String>,
    emptyText: String,
    accent: Color,
    captureRequest: Long,
    onCaptured: (android.graphics.Bitmap) -> Unit,
    onSliceClick: (Int) -> Unit,
    onRowClick: (Long) -> Unit
) {
    if (slices.isEmpty()) {
        EmptyState(emptyText)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "共 ${items.size} 类",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                    }
                    PieChartView(
                        slices = slices,
                        captureRequest = captureRequest,
                        onCaptured = onCaptured,
                        onSliceClick = onSliceClick,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "排行",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    items.forEachIndexed { index, item ->
                        RankRow(
                            rank = index + 1,
                            name = names[item.categoryId] ?: "未分类",
                            count = item.count,
                            amountFen = item.amountFen,
                            ratio = item.ratio,
                            accent = accent,
                            onClick = { onRowClick(item.categoryId) }
                        )
                    }
                }
            }
        }
    }
}

/** 趋势页：粒度 / 图形切换 + 图表卡。 */
@Composable
private fun TrendTab(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    if (state.bundle.trends.isEmpty()) {
        EmptyState("当前范围没有趋势数据")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatsGranularity.entries.forEach { granularity ->
                    StatPill(
                        label = when (granularity) {
                            StatsGranularity.DAY -> "按天"
                            StatsGranularity.WEEK -> "按周"
                            StatsGranularity.MONTH -> "按月"
                        },
                        selected = state.granularity == granularity,
                        style = PillStyle.SOFT,
                        onClick = { viewModel.setGranularity(granularity) }
                    )
                }
                Box(modifier = Modifier.weight(1f))
                TrendMode.entries.forEach { mode ->
                    StatPill(
                        label = mode.label,
                        selected = state.trendMode == mode,
                        style = PillStyle.SOFT,
                        onClick = { viewModel.setTrendMode(mode) }
                    )
                }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "收支趋势",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "收入绿 / 支出红，点柱子可下钻",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LegendDot("收入", IncomeGreen)
                            LegendDot("支出", ExpenseRed)
                        }
                    }
                    val pattern = when (state.granularity) {
                        StatsGranularity.DAY -> "MM-dd"
                        StatsGranularity.WEEK -> "MM-dd"
                        StatsGranularity.MONTH -> "yyyy-MM"
                    }
                    TrendChartView(
                        labels = state.bundle.trends.map { DateTimeUtils.format(it.bucketStart, pattern) },
                        incomeFen = state.bundle.trends.map { it.incomeFen },
                        expenseFen = state.bundle.trends.map { it.expenseFen },
                        mode = state.trendMode,
                        captureRequest = state.captureRequest,
                        onCaptured = viewModel::saveScreenshot,
                        onBucketClick = { viewModel.drillBucket(it) },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** 分类明细：支出在前、收入在后，逐行带占比条。 */
@Composable
private fun CategoryTable(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    val expense = state.bundle.expenseByCategory
    val income = state.bundle.incomeByCategory
    if (expense.isEmpty() && income.isEmpty()) {
        EmptyState("当前范围没有分类数据")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (expense.isNotEmpty()) {
            item { SectionTitle("支出 · ${expense.size} 类") }
            item {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        expense.forEachIndexed { index, item ->
                            RankRow(
                                rank = index + 1,
                                name = state.categoryNames[item.categoryId] ?: "未分类",
                                count = item.count,
                                amountFen = item.amountFen,
                                ratio = item.ratio,
                                accent = ExpenseRed,
                                onClick = { viewModel.drillCategory(item.categoryId, BillType.EXPENSE) }
                            )
                        }
                    }
                }
            }
        }
        if (income.isNotEmpty()) {
            item { SectionTitle("收入 · ${income.size} 类") }
            item {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        income.forEachIndexed { index, item ->
                            RankRow(
                                rank = index + 1,
                                name = state.categoryNames[item.categoryId] ?: "未分类",
                                count = item.count,
                                amountFen = item.amountFen,
                                ratio = item.ratio,
                                accent = IncomeGreen,
                                onClick = { viewModel.drillCategory(item.categoryId, BillType.INCOME) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 账户分析：余额快照，负数为负债。 */
@Composable
private fun AccountTable(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    if (state.bundle.accounts.isEmpty()) {
        EmptyState("还没有账户数据")
        return
    }
    val total = state.bundle.accounts.sumOf { it.balanceFen }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "净资产",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Money.format(total),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (total >= 0) IncomeGreen else ExpenseRed
                    )
                    Text(
                        text = "共 ${state.bundle.accounts.size} 个账户 · 点一行看该账户的账单",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    state.bundle.accounts.forEach { account ->
                        val negative = account.balanceFen < 0L
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.drillAccount(account.accountId) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "💳", fontSize = 13.sp)
                            }
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                                maxLines = 1
                            )
                            Text(
                                text = Money.format(account.balanceFen),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (negative) ExpenseRed else IncomeGreen
                            )
                            Text(
                                text = "›",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 标签统计：标签是扁平的，只按金额排序。 */
@Composable
private fun TagTable(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    if (state.bundle.tags.isEmpty()) {
        EmptyState("当前范围没有标签支出")
        return
    }
    val max = state.bundle.tags.maxOf { it.amountFen }.coerceAtLeast(1L)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    state.bundle.tags.forEachIndexed { index, tag ->
                        val ratio = tag.amountFen.toFloat() / max.toFloat()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.drillTag(tag.tagId) }
                                .padding(top = 10.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${state.tagNames[tag.tagId] ?: "标签 ${tag.tagId}"}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Text(
                                text = Money.format(tag.amountFen),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "›",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        RatioBar(ratio = ratio, color = MaterialTheme.colorScheme.primary)
                        if (index == state.bundle.tags.lastIndex) {
                            Box(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 分组小标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

/** 名次行：序号圆标 + 名称/笔数 + 金额 + 占比条。 */
@Composable
private fun RankRow(
    rank: Int,
    name: String,
    count: Int,
    amountFen: Long,
    ratio: Float,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (rank <= 3) accent.copy(alpha = 0.16f) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rank <= 3) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1
            )
            Text(
                text = "$count 笔",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = Money.format(amountFen),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                RatioBar(ratio = ratio, color = accent)
            }
            Text(
                text = "${(ratio * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** 细占比条，与「我的」页同款。 */
@Composable
private fun RatioBar(ratio: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.85f))
        )
    }
}

package com.azu.bookkeeper.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.usecase.stats.StatsGranularity
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.home.BillRow
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.stats.component.PieChartView
import com.azu.bookkeeper.ui.stats.component.StatsSummaryCards
import com.azu.bookkeeper.ui.stats.component.TrendChartView
import com.azu.bookkeeper.ui.stats.component.TrendMode

/** 统计报表：7 个 tab + 时间筛选 + 图表点击下钻 + 图表截图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    navController: NavController,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drill = state.drill

    if (drill != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("下钻：${drill.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.closeDrill() }) { Text("返回统计") }
            }
            if (state.drillBills.isEmpty()) {
                EmptyState("该条件下没有账单")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.drillBills, key = { it.billId }) { bill ->
                        BillRow(bill = bill, onClick = { navController.navigate(Screen.billDetail(bill.billId)) })
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        com.azu.bookkeeper.ui.common.AppTopBar(
            title = "统计报表 · ${state.bookName.ifBlank { "当前账本" }}",
            actions = { TextButton(onClick = { viewModel.requestScreenshot() }) { Text("截图") } }
        )

        // 时间范围改成胶囊筛选：原来是一排 TextButton，选中态只靠「✓」前缀，看起来像普通文字
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            StatsRange.ALL.forEach { range ->
                androidx.compose.material3.FilterChip(
                    selected = state.range == range,
                    onClick = { viewModel.setRange(range) },
                    label = {
                        Text(range.label, style = MaterialTheme.typography.labelMedium)
                    }
                )
            }
            OutlinedButton(
                onClick = { viewModel.setCustomRange(state.bounds.first, state.bounds.second) },
                modifier = Modifier.padding(start = 2.dp)
            ) {
                Text("自定义", style = MaterialTheme.typography.labelMedium)
            }
        }

        ScrollableTabRow(selectedTabIndex = state.tab, edgePadding = 8.dp) {
            StatisticsViewModel.TAB_TITLES.forEachIndexed { index, title ->
                Tab(
                    selected = state.tab == index,
                    onClick = { viewModel.setTab(index) },
                    text = { Text(title) }
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (state.tab) {
                0 -> StatsSummaryCards(
                    summary = state.bundle.summary,
                    onIncomeClick = { viewModel.drillType(BillType.INCOME) },
                    onExpenseClick = { viewModel.drillType(BillType.EXPENSE) }
                )
                1 -> {
                    val slices = state.bundle.expenseByCategory.map { item ->
                        (state.categoryNames[item.categoryId] ?: "未分类") to item.amountFen / 100f
                    }
                    if (slices.isEmpty()) {
                        EmptyState("当前范围没有支出数据")
                    } else {
                        PieChartView(
                            slices = slices,
                            captureRequest = state.captureRequest,
                            onCaptured = { viewModel.saveScreenshot(it) },
                            onSliceClick = { index ->
                                state.bundle.expenseByCategory.getOrNull(index)?.let { item ->
                                    viewModel.drillCategory(item.categoryId, BillType.EXPENSE)
                                }
                            }
                        )
                    }
                }
                2 -> {
                    val slices = state.bundle.incomeByCategory.map { item ->
                        (state.categoryNames[item.categoryId] ?: "未分类") to item.amountFen / 100f
                    }
                    if (slices.isEmpty()) {
                        EmptyState("当前范围没有收入数据")
                    } else {
                        PieChartView(
                            slices = slices,
                            captureRequest = state.captureRequest,
                            onCaptured = { viewModel.saveScreenshot(it) },
                            onSliceClick = { index ->
                                state.bundle.incomeByCategory.getOrNull(index)?.let { item ->
                                    viewModel.drillCategory(item.categoryId, BillType.INCOME)
                                }
                            }
                        )
                    }
                }
                3 -> TrendTab(state, viewModel)
                4 -> CategoryTable(state, viewModel)
                5 -> AccountTable(state, viewModel)
                6 -> TagTable(state, viewModel)
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun TrendTab(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            StatsGranularity.entries.forEach { granularity ->
                TextButton(onClick = { viewModel.setGranularity(granularity) }) {
                    Text(
                        when (granularity) {
                            StatsGranularity.DAY -> if (state.granularity == granularity) "✓按天" else "按天"
                            StatsGranularity.WEEK -> if (state.granularity == granularity) "✓按周" else "按周"
                            StatsGranularity.MONTH -> if (state.granularity == granularity) "✓按月" else "按月"
                        }
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TrendMode.entries.forEach { mode ->
                TextButton(onClick = { viewModel.setTrendMode(mode) }) {
                    Text(if (state.trendMode == mode) "✓${mode.label}" else mode.label)
                }
            }
        }
        if (state.bundle.trends.isEmpty()) {
            EmptyState("当前范围没有趋势数据")
        } else {
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
                onCaptured = { viewModel.saveScreenshot(it) },
                onBucketClick = { viewModel.drillBucket(it) }
            )
        }
    }
}

@Composable
private fun CategoryTable(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    val items = state.bundle.expenseByCategory + state.bundle.incomeByCategory
    if (items.isEmpty()) {
        EmptyState("当前范围没有分类数据")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = items, key = { "cat_${it.categoryId}_${it.amountFen}" }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val isIncome = state.bundle.incomeByCategory.any { it.categoryId == item.categoryId }
                        viewModel.drillCategory(
                            item.categoryId,
                            if (isIncome) BillType.INCOME else BillType.EXPENSE
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(state.categoryNames[item.categoryId] ?: "未分类", modifier = Modifier.weight(1f))
                Text("${item.count} 笔 · ${(item.ratio * 100).toInt()}%", modifier = Modifier.padding(end = 12.dp))
                Text(Money.format(item.amountFen))
            }
            Divider()
        }
    }
}

@Composable
private fun AccountTable(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    if (state.bundle.accounts.isEmpty()) {
        EmptyState("还没有账户数据")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = state.bundle.accounts, key = { it.accountId }) { account ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.drillAccount(account.accountId) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(account.name, modifier = Modifier.weight(1f))
                Text(Money.format(account.balanceFen))
            }
            Divider()
        }
    }
}

@Composable
private fun TagTable(state: StatisticsViewModel.UiState, viewModel: StatisticsViewModel) {
    if (state.bundle.tags.isEmpty()) {
        EmptyState("当前范围没有标签支出")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = state.bundle.tags, key = { it.tagId }) { tag ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.drillTag(tag.tagId) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(state.tagNames[tag.tagId] ?: "标签 ${tag.tagId}", modifier = Modifier.weight(1f))
                Text(Money.format(tag.amountFen))
            }
            Divider()
        }
    }
}

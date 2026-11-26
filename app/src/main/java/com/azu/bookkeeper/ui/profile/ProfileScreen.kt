package com.azu.bookkeeper.ui.profile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.BuildConfig
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CutePill
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/**
 * 「我的」页。
 *
 * 底部第四个 tab 从「设置」换成「我的」的原因：设置是低频动作，不该占主入口；
 * 而用户真正想在这个位置看到的是「我记了多少、花了多少、哪些是自动记的」。
 *
 * 结构从上到下：身份 + 本月总结 → 累计数据 → 本月分类 Top3 → 预算 → 功能入口 → 版本。
 * 右上角一个「设置」按钮进入原来的偏好设置页，功能入口集中在这里，避免两页重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "我的",
                subtitle = state.bookName.ifBlank { "少花点儿" },
                actions = {
                    TextButton(
                        onClick = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } }
                    ) {
                        Text(
                            text = "⚙ 设置",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SummaryHero(state) }

            item { TotalStatsRow(state) }

            if (state.topCategories.isNotEmpty()) {
                item { TopCategoryCard(state) }
            }

            if (state.hasBudget) {
                item { BudgetCard(state) }
            }

            item {
                ProfileGroup(
                    title = "记账数据",
                    entries = listOf(
                        Entry("📚", "账本管理", "日常 / 家庭 / 旅行分开记", Screen.Books.route),
                        Entry("🏷️", "分类管理", "分类和二级分类都在这里", Screen.Categories.route),
                        Entry("#️⃣", "标签管理", "给账单打标签，方便复盘", Screen.Tags.route),
                        Entry("💳", "资产账户", "银行卡、微信、支付宝余额", Screen.Accounts.route),
                        Entry("🧾", "批量添加", "一次录入多笔，共用分类账户", Screen.BatchAdd.route),
                        Entry("📊", "年度预算", "看全年花销总量", Screen.AnnualBudget.route)
                    ),
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            item {
                ProfileGroup(
                    title = "资金往来",
                    entries = listOf(
                        Entry("↩️", "退款管理", "登记退款，自动记回一笔收入", Screen.Refunds.route),
                        Entry("📤", "报销管理", "垫付的钱，到账时自动入账", Screen.Reimbursements.route),
                        Entry("🤝", "债务 / 借贷", "借入借出与每笔还款", Screen.Debts.route),
                        Entry("🗓️", "分期计划", "大额消费拆期，尾差自动补齐", Screen.Installments.route)
                    ),
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            item {
                ProfileGroup(
                    title = "智能记账",
                    entries = listOf(
                        Entry("🤖", "自动化设置", "识屏开关、静默时段、置信度阈值", Screen.AutomationSettings.route),
                        Entry("📳", "摇一摇与手势", "触发动作、灵敏度、悬浮球", Screen.QuickGestureSettings.route),
                        Entry("🔔", "通知设置", "自动记账提示与校对提醒", Screen.NotificationSettings.route),
                        Entry("🧪", "识屏自检", "读屏节点、截图、权限逐项体检", Screen.CaptureDiagnostics.route)
                    ),
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            item {
                ProfileGroup(
                    title = "数据与安全",
                    entries = listOf(
                        Entry("☁️", "备份与恢复", "本地加密备份，一键还原", Screen.Backup.route),
                        Entry("📥", "导入账单", "CSV / 模板批量导入", Screen.Import.route),
                        Entry("📤", "导出账单", "导出成 CSV 存档", Screen.Export.route),
                        Entry("🧹", "存储排查", "看看历史遗留的数据库和文件", Screen.StorageAudit.route),
                        Entry("🩺", "崩溃日志", "闪退时把栈复制给我", "debug:crash_log")
                    ),
                    onNavigate = { route -> navController.navigate(route) },
                    onExtra = { route ->
                        if (route == "debug:crash_log") {
                            // 零依赖日志页：依赖图或 Compose 崩了也能打开
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        context,
                                        com.azu.bookkeeper.ui.debug.CaptureLogActivity::class.java
                                    )
                                )
                            }
                        }
                    }
                )
            }

            item {
                ProfileGroup(
                    title = "关于",
                    entries = listOf(
                        Entry("✨", "关于与更新介绍", "v${BuildConfig.VERSION_NAME} · 最近改了什么", Screen.About.route)
                    ),
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            item {
                Text(
                    text = "少花点儿 v${BuildConfig.VERSION_NAME} · 数据只存在这台设备上",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** 顶部渐变卡：本月支出做大字，右边挂「已记账 N 天」，底部一行收入 / 结余 / 自动占比。 */
@Composable
private fun SummaryHero(state: ProfileViewModel.UiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                )
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🐷 少花点儿",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                ) {
                    Text(
                        // 没有账单时不编造「第 0 天」，直接给一句引导
                        text = if (state.recordDays > 0) "已记账 ${state.recordDays} 天" else "今天开始记",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "本月支出",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                text = Money.format(state.monthExpenseFen),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(top = 2.dp)
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                HeroMetric(
                    label = "本月收入",
                    value = Money.format(state.monthIncomeFen),
                    modifier = Modifier.weight(1f)
                )
                HeroMetric(
                    label = "本月结余",
                    value = Money.format(state.monthBalanceFen),
                    modifier = Modifier.weight(1f)
                )
                HeroMetric(
                    label = "自动记账",
                    value = if (state.totalBills > 0) "${state.autoPercent}%" else "—",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1
        )
    }
}

/** 累计数据：三格并排，比再堆一张大卡更省空间。 */
@Composable
private fun TotalStatsRow(state: ProfileViewModel.UiState) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatCell("累计账单", "${state.totalBills}", "笔", Modifier.weight(1f))
            VerticalDivider()
            StatCell("记账天数", "${state.recordDays}", "天", Modifier.weight(1f))
            VerticalDivider()
            StatCell("自动记账", "${state.autoBills}", "笔", Modifier.weight(1f))
        }
        if (state.firstBillTime > 0L) {
            Text(
                text = "第一笔记于 ${DateTimeUtils.format(state.firstBillTime, "yyyy-MM-dd")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 本月支出 Top3：只列前三，条长按占比，不做完整排行榜（那是统计页的活）。 */
@Composable
private fun TopCategoryCard(state: ProfileViewModel.UiState) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "本月花在哪",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            CutePill(text = "Top 3", color = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            state.topCategories.forEach { slice ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = slice.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        text = Money.format(slice.amountFen),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(slice.ratio * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                RatioBar(ratio = slice.ratio)
            }
        }
    }
}

/** 预算卡：只在设了预算时出现，避免给不用预算的人多一块噪音。 */
@Composable
private fun BudgetCard(state: ProfileViewModel.UiState) {
    val over = state.monthExpenseFen > state.budgetLimitFen
    val ratio = (state.monthExpenseFen.toFloat() / state.budgetLimitFen.toFloat()).coerceIn(0f, 1f)
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本月预算",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${state.budgetUsedPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (over) ExpenseRed else MaterialTheme.colorScheme.primary
                )
            }
            RatioBar(ratio = ratio, height = 8.dp, color = if (over) ExpenseRed else MaterialTheme.colorScheme.primary)
            Text(
                text = if (over) {
                    "已超支 ${Money.format(state.monthExpenseFen - state.budgetLimitFen)}"
                } else {
                    "还剩 ${Money.format(state.budgetLimitFen - state.monthExpenseFen)} 可用"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (over) ExpenseRed else IncomeGreen,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** 细进度条：总长就是卡片可用宽度，按比例填充。 */
@Composable
private fun RatioBar(
    ratio: Float,
    height: androidx.compose.ui.unit.Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.85f))
        )
    }
}

/** 功能入口的一行数据。 */
private data class Entry(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val route: String
)

/** 一组入口：标题 + 白卡 + 每行 emoji 圆标 + 标题/副标题 + 箭头。 */
@Composable
private fun ProfileGroup(
    title: String,
    entries: List<Entry>,
    onNavigate: (String) -> Unit,
    onExtra: (String) -> Unit = {}
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        SoftCard(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.route.startsWith("debug:")) onExtra(entry.route) else onNavigate(entry.route)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = entry.emoji, fontSize = 17.sp)
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (index < entries.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 62.dp, end = 14.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}

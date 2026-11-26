package com.azu.bookkeeper.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.theme.ExpenseRed

/**
 * 月度预算页。
 *
 * 参考主流记账 App（钱迹 / 随手记 / 薄荷）的预算页结构，从上到下四层：
 * 1. **渐变主卡**：本月预算总额、还剩多少、进度条、日均可用——一眼看清「还能花多少」；
 * 2. **总预算行**：点一下就能改总上限（不填则按分类预算合计）；
 * 3. **分类预算列表**：每个分类一条，进度条 + 已花/上限，点条目弹小窗直接改，不用一屏铺满输入框；
 * 4. **底部固定操作栏**：保存 / 按上月重置，永远看得见、够得着。
 */
@Composable
fun BudgetScreen(viewModel: BudgetViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingTotal by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar("预算", subtitle = "${state.month} · 本月已花 ${Money.format(state.spentFen)}")
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CuteOutlinedButton(
                        onClick = viewModel::resetMonth,
                        modifier = Modifier.weight(1f)
                    ) { Text("按上月重置", maxLines = 1, softWrap = false) }
                    CutePrimaryButton(
                        onClick = viewModel::save,
                        modifier = Modifier.weight(1.4f)
                    ) { Text("保存预算", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { BudgetHeroCard(state) }

            item {
                TotalLimitRow(
                    amountText = state.totalLimitText,
                    derivedTotalFen = state.effectiveTotalLimitFen,
                    derived = state.totalLimitDerived,
                    onClick = { editingTotal = true }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        "分类预算",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "点一行改上限",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.categories.isEmpty()) {
                item {
                    SoftCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "还没有支出分类，先去「分类管理」加几个再来设预算",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(items = state.categories, key = { it.categoryId }) { category ->
                    CategoryBudgetRow(
                        category = category,
                        spentFen = state.categorySpentFen[category.categoryId] ?: 0L,
                        limitFen = Money.parse(
                            state.categoryLimitTexts[category.categoryId].orEmpty()
                        ) ?: 0L,
                        onClick = { editingCategory = category }
                    )
                }
            }

            state.message?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    if (editingTotal) {
        BudgetEditDialog(
            title = "总预算",
            hint = "留空 = 按分类预算之和自动计算",
            initial = state.totalLimitText,
            onConfirm = { text ->
                viewModel.setTotalLimit(text)
                viewModel.save()
                editingTotal = false
            },
            onDismiss = { editingTotal = false }
        )
    }

    editingCategory?.let { category ->
        BudgetEditDialog(
            title = "${category.name} 的预算",
            hint = "填 0 或留空 = 不单独限制",
            initial = state.categoryLimitTexts[category.categoryId].orEmpty(),
            onConfirm = { text ->
                viewModel.saveCategoryLimit(category.categoryId, text)
                editingCategory = null
            },
            onDismiss = { editingCategory = null }
        )
    }
}

/** 渐变主卡：本月预算 / 还剩 / 已用进度 / 日均可用。 */
@Composable
private fun BudgetHeroCard(state: BudgetViewModel.UiState) {
    val hasLimit = state.effectiveTotalLimitFen > 0L
    val over = state.isOverBudget
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            )
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月预算",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.86f)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        !hasLimit -> "还没设上限"
                        state.totalLimitDerived -> "按分类合计"
                        else -> "手动设定"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
            Text(
                text = if (hasLimit) Money.format(state.effectiveTotalLimitFen) else "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = when {
                    !hasLimit -> "先设一个总预算，或给分类设上限"
                    over -> "已超支 ${Money.format(-state.remainingFen)}"
                    else -> "还剩 ${Money.format(state.remainingFen)} · 已用 ${state.usedPercent}%"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(top = 2.dp)
            )

            // 进度条：白色轨道 + 实心进度，超支时进度条顶满并提示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(if (over) Color(0xFFFFE2E2) else Color.White)
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                HeroMetric("已花", Money.format(state.spentFen), Modifier.weight(1f))
                HeroMetric("日均可用", Money.format(state.dailyAvailableFen), Modifier.weight(1f))
                HeroMetric("剩余天数", "${state.daysLeft} 天", Modifier.weight(1f), alignEnd = true)
            }
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 总预算一行：显示当前值（或分类合计），点一下弹窗改。 */
@Composable
private fun TotalLimitRow(
    amountText: String,
    derivedTotalFen: Long,
    derived: Boolean,
    onClick: () -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = noRipple, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "总预算",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        amountText.isNotBlank() -> "手动设定 · 点此修改"
                        derived -> "未单独设置，当前按分类预算合计 ${Money.format(derivedTotalFen)}"
                        else -> "点此设置一个总额度"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = if (amountText.isNotBlank()) {
                    Money.format(Money.parse(amountText) ?: 0L)
                } else {
                    "未设置"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (amountText.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 分类预算行：名称 + 进度 + 已花/上限。 */
@Composable
private fun CategoryBudgetRow(
    category: Category,
    spentFen: Long,
    limitFen: Long,
    onClick: () -> Unit
) {
    val ratio = if (limitFen > 0L) (spentFen.toFloat() / limitFen).coerceIn(0f, 1f) else 0f
    val over = limitFen > 0L && spentFen > limitFen
    val barColor = when {
        over -> ExpenseRed
        limitFen > 0L && ratio >= 0.8f -> Color(0xFFE8963C)
        else -> MaterialTheme.colorScheme.primary
    }
    val noRipple = remember { MutableInteractionSource() }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = noRipple, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 分类首字做圆标：不依赖分类图标字段是否填了 emoji
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.icon.ifBlank { category.name.take(1) },
                    style = MaterialTheme.typography.titleSmall,
                    color = barColor
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = when {
                            limitFen <= 0L -> "未设上限"
                            over -> "超 ${Money.format(spentFen - limitFen)}"
                            else -> "${(ratio * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (over) ExpenseRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (limitFen > 0L) ratio else 0f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(barColor)
                    )
                }
                Text(
                    text = "已花 ${Money.format(spentFen)}" +
                        if (limitFen > 0L) " / ${Money.format(limitFen)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** 单值编辑弹窗：总预算 / 某个分类的上限共用。 */
@Composable
private fun BudgetEditDialog(
    title: String,
    hint: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                CuteTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "金额（元）",
                    placeholder = "0.00",
                    leadingEmoji = "💰",
                    keyboardType = KeyboardType.Decimal,
                    supportingText = hint
                )
                if (text.isNotBlank()) {
                    Text(
                        text = "= ${Money.format(Money.parse(text) ?: 0L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            CutePrimaryButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            CuteOutlinedButton(onClick = { onConfirm("") }) { Text("清除") }
        }
    )
}

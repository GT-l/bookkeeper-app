package com.azu.bookkeeper.ui.stats.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.StatisticsSummary
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/**
 * 概览页：一块渐变主卡（净结余）+ 一张预算完成度卡。
 *
 * 之前是 2×2 四张 Material 默认 Card，和全站的糖果风完全两张皮，
 * 而且四个数字一样大，看不出「这段时间到底赚了还是花了」。
 * 现在净结余做成大字，收入/支出可点下钻，转账退到最后一行。
 */
@Composable
fun StatsSummaryCards(
    summary: StatisticsSummary,
    onIncomeClick: () -> Unit,
    onExpenseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
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
                Text(
                    text = "净结余",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                )
                Text(
                    text = Money.format(summary.netFen),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    HeroMetric(
                        label = "总收入",
                        value = Money.format(summary.incomeFen),
                        hint = "看明细 ›",
                        modifier = Modifier.weight(1f),
                        onClick = onIncomeClick
                    )
                    HeroMetric(
                        label = "总支出",
                        value = Money.format(summary.expenseFen),
                        hint = "看明细 ›",
                        modifier = Modifier.weight(1f),
                        onClick = onExpenseClick
                    )
                    HeroMetric(
                        label = "转账流水",
                        value = Money.format(summary.transferFen),
                        hint = null,
                        modifier = Modifier.weight(1f),
                        onClick = null
                    )
                }
            }
        }

        if (summary.budgetLimitFen > 0L) {
            val progress = (summary.budgetUsedFen.toFloat() / summary.budgetLimitFen.toFloat())
                .coerceIn(0f, 1f)
            val over = summary.budgetUsedFen > summary.budgetLimitFen
            SoftCard(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "预算完成度",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (over) ExpenseRed else MaterialTheme.colorScheme.primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(if (over) ExpenseRed else MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        text = if (over) {
                            "已超支 ${Money.format(summary.budgetUsedFen - summary.budgetLimitFen)}"
                        } else {
                            "已用 ${Money.format(summary.budgetUsedFen)} / 预算 ${Money.format(summary.budgetLimitFen)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (over) ExpenseRed else IncomeGreen,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            SoftCard(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "这段时间没有预算",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "去预算页设一个上限，这里就会显示完成度",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(text = "🎯", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** 主卡里的一个指标；[onClick] 为空时不可点（转账流水）。 */
@Composable
private fun HeroMetric(
    label: String,
    value: String,
    hint: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?
) {
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
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
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
        }
    }
}

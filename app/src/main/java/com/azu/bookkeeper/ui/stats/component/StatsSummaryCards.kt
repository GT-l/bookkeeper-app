package com.azu.bookkeeper.ui.stats.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.StatisticsSummary
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen
import com.azu.bookkeeper.ui.theme.TransferGray

/** 概览页四张卡片；点击收入/支出卡片下钻账单列表。 */
@Composable
fun StatsSummaryCards(
    summary: StatisticsSummary,
    onIncomeClick: () -> Unit,
    onExpenseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard(
                title = "总收入",
                value = Money.format(summary.incomeFen),
                color = IncomeGreen,
                onClick = onIncomeClick,
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "总支出",
                value = Money.format(summary.expenseFen),
                color = ExpenseRed,
                onClick = onExpenseClick,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard(
                title = "净结余",
                value = Money.format(summary.netFen),
                color = if (summary.netFen >= 0) IncomeGreen else ExpenseRed,
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "转账流水",
                value = Money.format(summary.transferFen),
                color = TransferGray,
                onClick = {},
                modifier = Modifier.weight(1f)
            )
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("预算完成度", style = MaterialTheme.typography.titleMedium)
                if (summary.budgetLimitFen > 0L) {
                    val progress = (summary.budgetUsedFen.toFloat() / summary.budgetLimitFen.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(
                        "已用 ${Money.format(summary.budgetUsedFen)} / 预算 ${Money.format(summary.budgetLimitFen)}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Text("本月尚未设置预算", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            Text(value, color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

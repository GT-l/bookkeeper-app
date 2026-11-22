package com.azu.bookkeeper.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen
import com.azu.bookkeeper.ui.theme.TransferGray

/** 账单列表：日期分组 + 当日收入/支出/净额小计 + 加载更多。 */
@Composable
fun BillList(
    groups: List<HomeViewModel.DayGroup>,
    onBillClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        groups.forEach { group ->
            item(key = "header_${group.dayStart}") { DayHeader(group) }
            items(items = group.bills, key = { it.billId }) { bill ->
                BillRow(bill = bill, onClick = { onBillClick(bill.billId) })
            }
        }
        item(key = "load_more") {
            Button(onClick = onLoadMore, modifier = Modifier.padding(16.dp)) { Text("加载更多") }
        }
    }
}

@Composable
private fun DayHeader(group: HomeViewModel.DayGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = DateTimeUtils.formatDateHeader(group.dayStart),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "收 ${Money.format(group.incomeFen)}",
                color = IncomeGreen,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "支 ${Money.format(group.expenseFen)}",
                color = ExpenseRed,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Divider(modifier = Modifier.padding(top = 6.dp))
    }
}

/** 支出红、收入绿、转账灰（架构 §9.3 固定语义色）。 */
@Composable
fun BillRow(bill: Bill, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val amountColor = when (bill.billType) {
        BillType.EXPENSE -> ExpenseRed
        BillType.INCOME -> IncomeGreen
        BillType.TRANSFER -> TransferGray
    }
    val sign = when (bill.billType) {
        BillType.EXPENSE -> "-"
        BillType.INCOME -> "+"
        BillType.TRANSFER -> ""
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = bill.remark.ifBlank { bill.billType.label },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$sign${Money.format(bill.amountFen)}",
                color = amountColor,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End
            )
        }
        Text(
            text = "${bill.billType.label} · ${DateTimeUtils.format(bill.billTime, "HH:mm")} · ${bill.source.label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Divider()
}

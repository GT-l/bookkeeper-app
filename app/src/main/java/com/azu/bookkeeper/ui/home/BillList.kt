package com.azu.bookkeeper.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePill
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.theme.AmountStyle
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen
import com.azu.bookkeeper.ui.theme.TransferGray

/** 账单列表：日期分组 + 当日收入/支出/净额小计 + 右滑/长按删除 + 加载更多。 */
@Composable
fun BillList(
    groups: List<HomeViewModel.DayGroup>,
    onBillClick: (Long) -> Unit,
    onSwipeDelete: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** 还有没有更多：false 时连「加载更多」按钮都不显示，避免点了没反应的错觉。 */
    hasMore: Boolean = true
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        groups.forEach { group ->
            item(key = "header_${group.dayStart}") { DayHeader(group) }
            items(items = group.bills, key = { it.billId }) { bill ->
                SwipeDismissableBillRow(
                    bill = bill,
                    onClick = { onBillClick(bill.billId) },
                    onLongClick = { onSwipeDelete(bill.billId) },
                    onSwipeDelete = onSwipeDelete
                )
            }
        }
        if (hasMore) {
            item(key = "load_more") {
                CuteOutlinedButton(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                ) { Text("加载更多") }
            }
        } else if (groups.isNotEmpty()) {
            item(key = "list_end") {
                Text(
                    text = "— 到底了 —",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
                )
            }
        }
    }
}

/**
 * 可右滑删除的账单条目：
 * - 右滑（EndToStart）露出粉色「删除」背景；
 * - 滑到底只回调 [onSwipeDelete] 给上层弹确认框，不直接删除；
 * - confirmValueChange 返回 false 让条目回弹复位，取消滑动也自动归位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDismissableBillRow(
    bill: Bill,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                // 只上报待删账单，真正的删除由上层确认对话框触发
                onSwipeDelete(bill.billId)
            }
            // 返回 false：无论确认还是取消，条目都会回弹到原位
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeDeleteBackground() }
    ) {
        BillRow(bill = bill, onClick = onClick, onLongClick = onLongClick)
    }
}

/** 右滑露出的删除背景：主色圆角容器 + 白色「删除」文字，圆角 20dp 与条目卡片一致。 */
@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = "删除",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge
        )
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
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            CutePill(text = "收 ${Money.format(group.incomeFen)}", color = IncomeGreen)
            CutePill(
                text = "支 ${Money.format(group.expenseFen)}",
                color = ExpenseRed,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp
        )
    }
}

/** 支出红、收入绿、转账灰（中国记账语义色，随深浅主题切换）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BillRow(
    bill: Bill,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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
    SoftCard(
        // v1.3.9：列表行不画阴影（每行一条 shadow 是滑动掉帧的主因）
        flat = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = bill.remark.ifBlank { bill.billType.label },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$sign${Money.format(bill.amountFen)}",
                    color = amountColor,
                    style = AmountStyle,
                    textAlign = TextAlign.End
                )
            }
            Text(
                text = "${bill.billType.label} · ${DateTimeUtils.format(bill.billTime, "HH:mm")} · ${bill.source.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

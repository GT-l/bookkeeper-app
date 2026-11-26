package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import com.azu.bookkeeper.domain.usecase.bill.AutoRecordUseCase
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.theme.BookkeeperTheme
import com.azu.bookkeeper.ui.theme.ExpenseRed
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 识屏结果的**轻确认弹窗**，一个页面承担两种确认：
 *
 * 1) 中等置信度（0.5 ~ 0.8）：只展示「金额 + 商户 + 购买内容」，主按钮「确认记账」直接落库，
 *    「修改」进完整表单——不把用户丢进表单逐项核对；
 * 2) **疑似重复**（近 24 小时同商户同金额已有一笔）：文案改成「这笔好像记过了」，
 *    并把已有那一笔的时间/金额摆出来，让用户自己决定「仍然记账 / 不记了」。
 *    是否重复永远由用户拍板，程序不替用户丢单。
 *
 * 用透明 Activity + Compose 卡片实现（不用 DialogFragment）：
 * 后台服务可直接 startActivity 拉起，且不受 Dialog 需要前台 Activity 的限制。
 */
class QuickConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amountFen = intent.getLongExtra(EXTRA_AMOUNT, 0L)
        val merchant = intent.getStringExtra(EXTRA_MERCHANT).orEmpty()
        val goods = intent.getStringExtra(EXTRA_GOODS).orEmpty()
        val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
        val duplicate = intent.getStringExtra(EXTRA_DUPLICATE).orEmpty()
        if (amountFen <= 0L) {
            finish()
            return
        }
        setContent {
            BookkeeperTheme {
                ConfirmCard(
                    amountFen = amountFen,
                    merchant = merchant,
                    goods = goods,
                    duplicate = duplicate,
                    onConfirm = { confirm(amountFen, merchant, goods, confidence) },
                    onEdit = { openForm(amountFen, merchant, goods) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun confirm(amountFen: Long, merchant: String, goods: String, confidence: Float) {
        val recorder = runCatching {
            EntryPointAccessors.fromApplication(
                application, QuickConfirmEntryPoint::class.java
            ).autoRecord()
        }.getOrNull()
        if (recorder == null) {
            openForm(amountFen, merchant, goods)
            return
        }
        lifecycleScope.launch {
            val billId = runCatching {
                recorder(
                    amountFen = amountFen,
                    merchant = merchant,
                    goods = goods,
                    confidence = confidence
                )
            }.getOrNull()?.getOrNull()
            if (billId != null) {
                val detail = listOf(merchant, goods).filter { it.isNotBlank() }
                    .joinToString(" · ")
                Toast.makeText(
                    this@QuickConfirmActivity,
                    "记账成功 · ${Money.format(amountFen)}" + if (detail.isBlank()) "" else " · $detail",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                Toast.makeText(
                    this@QuickConfirmActivity, "自动记账失败，已打开表单", Toast.LENGTH_SHORT
                ).show()
                openForm(amountFen, merchant, goods)
            }
        }
    }

    private fun openForm(amountFen: Long, merchant: String, goods: String) {
        val remark = com.azu.bookkeeper.core.composeRemark(merchant, goods)
        runCatching {
            startActivity(
                QuickCaptureLauncher.intent(
                    this,
                    QuickCaptureRequest(
                        entry = QuickEntry.SYSTEM_GESTURE,
                        amountFen = amountFen,
                        merchant = remark
                    )
                )
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_AMOUNT = "extra_amount_fen"
        private const val EXTRA_MERCHANT = "extra_merchant"
        private const val EXTRA_GOODS = "extra_goods"
        private const val EXTRA_CONFIDENCE = "extra_confidence"
        private const val EXTRA_DUPLICATE = "extra_duplicate"

        fun intent(
            context: Context,
            amountFen: Long,
            merchant: String,
            confidence: Float,
            goods: String = "",
            duplicate: String = ""
        ): Intent = Intent(context, QuickConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_AMOUNT, amountFen)
            putExtra(EXTRA_MERCHANT, merchant)
            putExtra(EXTRA_GOODS, goods)
            putExtra(EXTRA_CONFIDENCE, confidence)
            putExtra(EXTRA_DUPLICATE, duplicate)
        }
    }
}

/** 惰性依赖入口：不在 Activity 上用字段注入，避免注入失败把弹窗变成闪退。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickConfirmEntryPoint {
    fun autoRecord(): AutoRecordUseCase
}

@Composable
private fun ConfirmCard(
    amountFen: Long,
    merchant: String,
    goods: String,
    duplicate: String,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    val isDuplicate = duplicate.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                // 吞掉点击，避免点卡片本身触发遮罩的关闭
                .clickable(interactionSource = noRipple, indication = null) { }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isDuplicate) "这笔好像记过了" else "识别到一笔支出",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isDuplicate) ExpenseRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    Money.format(amountFen),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isDuplicate) ExpenseRed else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    merchant.ifBlank { "未识别商户" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (goods.isNotBlank()) {
                    Text(
                        "购买内容：$goods",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (isDuplicate) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ExpenseRed.copy(alpha = 0.10f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            duplicate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                } else {
                    Text(
                        "确认后将直接记入默认账本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDuplicate) {
                        // 重复场景给三个出口：不记了 / 去改金额 / 仍然记账，避免「只能二选一」的憋屈感
                        CuteOutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) { Text("NO", maxLines = 1, softWrap = false) }
                        CuteOutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f)
                        ) { Text("修改", maxLines = 1, softWrap = false) }
                        CutePrimaryButton(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1.35f)
                        ) {
                            Text(
                                "仍然记账",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        CuteOutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f)
                        ) { Text("修改", maxLines = 1, softWrap = false) }
                        CutePrimaryButton(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1.4f)
                        ) { Text("确认记账", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

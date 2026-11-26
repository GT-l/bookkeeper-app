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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 识屏结果的**轻确认弹窗**（中等置信度时使用）。
 *
 * 设计目标：确认成本尽可能低——只展示「金额 + 商户」两个关键信息，
 * 一颗主按钮「确认记账」直接落库，不想用就点「修改」进完整表单。
 * 不再把用户丢进完整记账表单去逐项核对。
 *
 * 用透明 Activity + Compose 卡片实现（不用 DialogFragment）：
 * 后台服务可直接 startActivity 拉起，且不受 Dialog 需要前台 Activity 的限制。
 */
class QuickConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amountFen = intent.getLongExtra(EXTRA_AMOUNT, 0L)
        val merchant = intent.getStringExtra(EXTRA_MERCHANT).orEmpty()
        val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
        if (amountFen <= 0L) {
            finish()
            return
        }
        setContent {
            BookkeeperTheme {
                ConfirmCard(
                    amountFen = amountFen,
                    merchant = merchant,
                    onConfirm = { confirm(amountFen, merchant, confidence) },
                    onEdit = { openForm(amountFen, merchant) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun confirm(amountFen: Long, merchant: String, confidence: Float) {
        val recorder = runCatching {
            EntryPointAccessors.fromApplication(
                application, QuickConfirmEntryPoint::class.java
            ).autoRecord()
        }.getOrNull()
        if (recorder == null) {
            openForm(amountFen, merchant)
            return
        }
        lifecycleScope.launch {
            val billId = runCatching { recorder(amountFen, merchant, confidence) }
                .getOrNull()
                ?.getOrNull()
            if (billId != null) {
                Toast.makeText(
                    this@QuickConfirmActivity,
                    "记账成功 · ${Money.format(amountFen)}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                Toast.makeText(
                    this@QuickConfirmActivity, "自动记账失败，已打开表单", Toast.LENGTH_SHORT
                ).show()
                openForm(amountFen, merchant)
            }
        }
    }

    private fun openForm(amountFen: Long, merchant: String) {
        runCatching {
            startActivity(
                QuickCaptureLauncher.intent(
                    this,
                    QuickCaptureRequest(
                        entry = QuickEntry.SYSTEM_GESTURE,
                        amountFen = amountFen,
                        merchant = merchant
                    )
                )
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_AMOUNT = "extra_amount_fen"
        private const val EXTRA_MERCHANT = "extra_merchant"
        private const val EXTRA_CONFIDENCE = "extra_confidence"

        fun intent(
            context: Context,
            amountFen: Long,
            merchant: String,
            confidence: Float
        ): Intent = Intent(context, QuickConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_AMOUNT, amountFen)
            putExtra(EXTRA_MERCHANT, merchant)
            putExtra(EXTRA_CONFIDENCE, confidence)
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
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                // 吞掉点击，避免点卡片本身触发遮罩的关闭
                .clickable(interactionSource = noRipple, indication = null) { }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "识别到一笔支出",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    Money.format(amountFen),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    merchant.ifBlank { "未识别商户" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    "确认后将直接记入默认账本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CuteOutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f)
                    ) { Text("修改") }
                    CutePrimaryButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1.4f)
                    ) { Text("确认记账", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

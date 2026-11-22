package com.azu.bookkeeper.automation.verify

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.ui.quick.QuickCaptureLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 前台校对弹窗的宿主 ViewModel：收集请求、提交决定并在提交后清空当前候选以关闭弹窗。 */
@HiltViewModel
class VerificationDialogViewModel @Inject constructor(
    private val controller: VerificationController
) : ViewModel() {

    private val _current = MutableStateFlow<CaptureCandidate?>(null)
    val current: StateFlow<CaptureCandidate?> = _current.asStateFlow()

    /** T7：前台弹窗的「一键补记」请求，宿主收集后拉起表单（SharedFlow 仅作前台辅助通道）。 */
    val quickCaptureEvents: SharedFlow<QuickCaptureRequest> get() = controller.quickCaptureEvents

    init {
        viewModelScope.launch {
            controller.requests.collect { candidate ->
                // 前台才弹窗：仅在尚无待处理候选时接收，避免连续捕获堆叠弹窗
                if (_current.value == null) _current.value = candidate
            }
        }
    }

    fun submit(decision: VerifyDecision) = viewModelScope.launch {
        controller.submitDecision(decision)
        _current.value = null
    }

    fun discard(eventId: Long) {
        _current.value = null
        viewModelScope.launch {
            controller.submitDecision(VerifyDecision(eventId, accepted = false))
        }
    }

    /** 用户点「一键补记」：带预填字段和事件 ID 进入表单；此处不写库。 */
    fun quickCapture(candidate: CaptureCandidate) {
        controller.requestQuickCapture(candidate)
        _current.value = null
    }
}

/**
 * 前台校对 Dialog（三档中的最高优先档）：App 在前台时由 [VerificationDialogHost] 收集请求并展示。
 * 用户可改写金额 / 商家后确认；确认视为用户介入，事件置 USER_CORRECTED 终态。
 */
@Composable
fun VerificationDialogHost(viewModel: VerificationDialogViewModel = hiltViewModel()) {
    val candidate by viewModel.current.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.quickCaptureEvents.collect { request ->
            runCatching { context.startActivity(QuickCaptureLauncher.intent(context, request)) }
        }
    }
    candidate?.let { c ->
        VerificationDialogContent(
            candidate = c,
            onConfirm = { amountFen, merchant ->
                viewModel.submit(
                    VerifyDecision(
                        eventId = c.eventId,
                        accepted = true,
                        amountFen = amountFen,
                        merchant = merchant,
                        categoryId = c.categoryId,
                        accountId = c.accountId
                    )
                )
            },
            onDiscard = { viewModel.discard(c.eventId) },
            onQuickCapture = { viewModel.quickCapture(c) }
        )
    }
}

@Composable
private fun VerificationDialogContent(
    candidate: CaptureCandidate,
    onConfirm: (Long, String) -> Unit,
    onDiscard: () -> Unit,
    onQuickCapture: () -> Unit
) {
    var amountText by remember(candidate.eventId) {
        mutableStateOf(candidate.amountFen?.let { Money.format(it, withSymbol = false) }.orEmpty())
    }
    var merchantText by remember(candidate.eventId) { mutableStateOf(candidate.merchant) }

    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text("确认这笔自动识别的账单") },
        text = {
            Column {
                Text(
                    "识别来源：${candidate.captureType.label}",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = merchantText,
                    onValueChange = { merchantText = it },
                    label = { Text("商家 / 备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "置信度 ${String.format("%.0f%%", candidate.finalScore * 100)}，请核对后确认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // T7 抖音兜底：识别字段不全时走最短补记路径，保存仍收敛到单一写事务。
                OutlinedButton(
                    onClick = onQuickCapture,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text("一键补记（打开表单）") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fen = Money.parse(amountText)
                if (fen != null && fen > 0L) onConfirm(fen, merchantText.trim())
            }) { Text("确认入账") }
        },
        dismissButton = { TextButton(onClick = onDiscard) { Text("忽略") } }
    )
}

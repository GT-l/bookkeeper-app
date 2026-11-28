package com.azu.bookkeeper.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.core.AppError

/** 柔和卡片：白底 / 深色主题深紫底 + 浅描边 + 低抬升阴影。 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    /**
     * v1.3.9：列表行传 true 去掉阴影层。
     *
     * 列表里每一行都是一张带 3dp elevation 的 Card，等于每行一条 RenderNode 阴影；
     * 账单几百条时上下滑动会明显掉帧。行的层次本来就靠间距 + 描边区分，不需要阴影。
     */
    flat: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = if (flat) {
            CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
        } else {
            CardDefaults.cardElevation(defaultElevation = 3.dp, pressedElevation = 6.dp)
        },
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/**
 * 主按钮：糖果渐变（粉 → 深粉）+ 胶囊圆角 + 按下回弹，禁用态退回浅粉容器色。
 * 用 interactionSource 手动做按压缩放，无需额外动画库。
 */
@Composable
fun CutePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (pressed && enabled) 0.95f else 1f
    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
            ),
        enabled = enabled,
        interactionSource = interaction,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        content = content
    )
}

/** 次按钮：主色描边 + 大圆角，用于取消 / 加载更多等非主操作。 */
@Composable
fun CuteOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        content = content
    )
}

/** 全圆角色块：用于收支标签、分组头小徽标。 */
@Composable
fun CutePill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** 通用加载态。 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
    }
}

/** 通用空态。 */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SoftCard(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "🐷",
                fontSize = 36.sp,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

/** 通用错误态，带重试入口。 */
@Composable
fun ErrorState(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SoftCard(modifier = Modifier.padding(bottom = 12.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = error.userMessage, color = MaterialTheme.colorScheme.error)
                Text(
                    text = error.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        CutePrimaryButton(onClick = onRetry) { Text("重试") }
    }
}

/** 二次确认弹窗，用于删除账本 / 删除账单等破坏性操作。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { CutePrimaryButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { CuteOutlinedButton(onClick = onDismiss) { Text(dismissText) } }
    )
}

/** T1 骨架占位页：后续任务按路由逐个替换为真实页面。 */
@Composable
fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

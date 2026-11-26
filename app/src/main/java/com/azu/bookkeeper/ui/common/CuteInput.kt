package com.azu.bookkeeper.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一风格的输入框（替代 Material 默认 OutlinedTextField）。
 *
 * 观感要点：
 * - 标签在框**上方**，聚焦时变成主色，视线不用在浮动标签上跳；
 * - 填充式圆角容器（未聚焦淡紫底、聚焦纯白/纯深底），聚焦时描边渐变到主色；
 * - 可选的左侧 emoji 小图标 + 占位文案，空值时占位不抢焦点；
 * - 高度固定，多个输入框纵向排在一起时基线整齐。
 *
 * 为什么不直接在主题里改：Material3 的 OutlinedTextField 样式只能逐处传参，
 * 想要「聚焦渐变描边 + 标签外置」必须自己组合，所以抽成组件统一调用。
 */
@Composable
fun CuteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingEmoji: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    textStyle: TextStyle? = null,
    supportingText: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.primary else colors.outlineVariant,
        label = "inputBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = if (focused) colors.surface else colors.surfaceVariant.copy(alpha = 0.55f),
        label = "inputContainer"
    )
    val labelColor by animateColorAsState(
        targetValue = if (focused) colors.primary else colors.onSurfaceVariant,
        label = "inputLabel"
    )

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!leadingEmoji.isNullOrBlank()) {
                Text(
                    text = leadingEmoji,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(containerColor, RoundedCornerShape(18.dp))
                .border(1.5.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = (textStyle ?: MaterialTheme.typography.bodyLarge)
                .copy(color = colors.onSurface),
            singleLine = singleLine,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = (textStyle ?: MaterialTheme.typography.bodyLarge)
                                .copy(color = colors.onSurfaceVariant.copy(alpha = 0.55f))
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

/**
 * 金额输入：整张卡片就是输入框。
 *
 * - 「¥」符号固定在最左侧，金额用 34sp 等宽字体，放大时也不会左右抖动；
 * - 聚焦时外圈是主色→次色的渐变描边（2dp），未聚焦是浅描边，不做无谓动画；
 * - 右侧固定「元」单位，空值展示 0.00 占位；
 * - 空值/聚焦时描边轻微放大 1.01，给一点「活」的反馈。
 */
@Composable
fun CuteAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "金额（元）"
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderBrush = if (focused) {
        Brush.linearGradient(listOf(colors.primary, colors.secondary))
    } else {
        Brush.linearGradient(listOf(colors.outlineVariant, colors.outlineVariant))
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1f else 0.995f,
        label = "amountScale"
    )

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) colors.primary else colors.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .scale(scale)
                .background(borderBrush, RoundedCornerShape(26.dp))
                .padding(2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "¥",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    interactionSource = interaction,
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = "0.00",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Text(
                    text = "元",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

package com.azu.bookkeeper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azu.bookkeeper.ui.theme.LavenderContainer
import com.azu.bookkeeper.ui.theme.LightBackground
import com.azu.bookkeeper.ui.theme.MintContainer
import com.azu.bookkeeper.ui.theme.PeachContainer
import com.azu.bookkeeper.ui.theme.PinkContainer
import com.azu.bookkeeper.ui.theme.Primary

/**
 * 全局可爱风背景：奶油粉渐变 + 散布的贴纸形象（小猪 / 爱心 / 星星 / 云朵 / 草莓 / 彩虹）。
 *
 * 贴纸用 emoji + 柔和色块绘制，透明度很低，只做氛围不抢内容可读性；
 * 位置按屏幕宽高百分比排布，不同尺寸屏幕都能铺开。深色主题由调用方传入深色渐变。
 */
@Composable
fun CuteBackdrop(
    modifier: Modifier = Modifier,
    dark: Boolean = false
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight

        // 奶油底色渐变：略带粉/桃，避免纯白单调
        val top = if (dark) androidx.compose.ui.graphics.Color(0xFF2E2635) else LightBackground
        val bottom = if (dark) androidx.compose.ui.graphics.Color(0xFF3A2B3E) else PeachContainer
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom)))
        )

        // 柔和色块：像贴在纸上的圆形贴纸底
        Blob(color = PinkContainer, size = 180.dp, x = w * 0.72f, y = h * 0.06f, alpha = 0.55f)
        Blob(color = MintContainer, size = 140.dp, x = (-40).dp, y = h * 0.32f, alpha = 0.45f)
        Blob(color = LavenderContainer, size = 160.dp, x = w * 0.78f, y = h * 0.55f, alpha = 0.40f)
        Blob(color = PeachContainer, size = 120.dp, x = w * 0.05f, y = h * 0.78f, alpha = 0.55f)

        // 贴纸形象：分散在四角与边缘，中间留给正文
        Sticker("🐷", w * 0.80f, h * 0.08f, 58.dp, 12f, 0.42f)
        Sticker("💗", w * 0.14f, h * 0.16f, 34.dp, -18f, 0.34f)
        Sticker("⭐", w * 0.62f, h * 0.36f, 26.dp, 15f, 0.30f)
        Sticker("☁️", (-8).dp, h * 0.46f, 52.dp, -6f, 0.36f)
        Sticker("🍓", w * 0.86f, h * 0.70f, 34.dp, 20f, 0.32f)
        Sticker("✨", w * 0.28f, h * 0.88f, 28.dp, -12f, 0.30f)
        Sticker("🧁", w * 0.66f, h * 0.92f, 40.dp, 8f, 0.30f)
        Sticker("💕", w * 0.05f, h * 0.66f, 24.dp, 16f, 0.28f)
    }
}

@Composable
private fun Blob(color: androidx.compose.ui.graphics.Color, size: Dp, x: Dp, y: Dp, alpha: Float) {
    Box(
        Modifier
            .offset(x = x, y = y)
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

@Composable
private fun Sticker(emoji: String, x: Dp, y: Dp, size: Dp, rotation: Float, alpha: Float) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Text(
        text = emoji,
        fontSize = fontSize,
        fontWeight = FontWeight.Normal,
        modifier = Modifier
            .offset(x = x, y = y)
            .rotate(rotation)
            .alpha(alpha)
    )
}

/** 小标题旁的装饰爱心，给分区标题、空态、按钮加一点可爱感。 */
@Composable
fun CuteHeart(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Text(text = "♥", color = Primary, fontSize = fontSize, modifier = modifier)
}

/** 顶部对齐辅助：让贴纸层铺满但不吃点击。 */
@Composable
fun CuteScreenBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CuteBackdrop(Modifier.align(Alignment.TopStart))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) { content() }
    }
}

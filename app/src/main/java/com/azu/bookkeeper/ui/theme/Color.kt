package com.azu.bookkeeper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 可爱风调色板：草莓粉主色 + 奶油白背景 + 柔和薰衣草/薄荷点缀。
 *
 * 语义色（支出 / 收入 / 转账 / 错误）保持中国记账习惯：支出珊瑚红、收入青绿。
 * 这些颜色被多个未改动页面以普通属性形式引用，因此这里用 @get:Composable
 * 让它随系统深浅主题自动切换，无需修改调用方。
 */
val Primary = Color(0xFFC74372)
val PrimaryDark = Color(0xFF9C2D58)
val PrimaryLight = Color(0xFFFFB3C8)

val ExpenseRedLight = Color(0xFFC9453F)
val IncomeGreenLight = Color(0xFF0E7C66)
val TransferGrayLight = Color(0xFF6F6879)
val ErrorRedLight = Color(0xFFC7434A)

val ExpenseRedDark = Color(0xFFFF9A90)
val IncomeGreenDark = Color(0xFF6BDEC5)
val TransferGrayDark = Color(0xFFC3BAD1)
val ErrorRedDark = Color(0xFFFF8A82)

/** 支出：珊瑚红（中国习惯，支出为红）。 */
@get:Composable
val ExpenseRed: Color
    get() = if (isSystemInDarkTheme()) ExpenseRedDark else ExpenseRedLight

/** 收入：青绿（中国习惯，收入为绿）。 */
@get:Composable
val IncomeGreen: Color
    get() = if (isSystemInDarkTheme()) IncomeGreenDark else IncomeGreenLight

/** 转账：柔和灰紫。 */
@get:Composable
val TransferGray: Color
    get() = if (isSystemInDarkTheme()) TransferGrayDark else TransferGrayLight

/** 错误提示：珊瑚红。 */
@get:Composable
val ErrorRed: Color
    get() = if (isSystemInDarkTheme()) ErrorRedDark else ErrorRedLight

val LightBackground = Color(0xFFFFF9F5)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF4A3F48)
val LightOutline = Color(0xFFF0DCE2)

/** 浅色主题下的柔和容器色，用于 Chip / 概览卡底色。 */
val PinkContainer = Color(0xFFFFE4EC)
val MintContainer = Color(0xFFDFF5EE)
val PeachContainer = Color(0xFFFFEBDD)
val LavenderContainer = Color(0xFFEEE8FA)

val DarkBackground = Color(0xFF241F2B)
val DarkSurface = Color(0xFF302A38)
val DarkOnSurface = Color(0xFFF7F0F5)
val DarkOutline = Color(0xFF4A4152)

/** 深色主题下的柔和容器色，避免纯黑与高饱和撞色。 */
val DarkPrimaryContainer = Color(0xFF6E2A4A)
val DarkSecondaryContainer = Color(0xFF45376A)
val DarkTertiaryContainer = Color(0xFF1E5A4D)

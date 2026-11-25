package com.azu.bookkeeper.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** 浅色：奶油白底 + 纯白卡片 + 草莓粉主色 + 薰衣草紫/薄荷青点缀。 */
private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PinkContainer,
    onPrimaryContainer = Color(0xFF7A2347),
    secondary = Color(0xFF7A67B5),
    onSecondary = Color.White,
    secondaryContainer = LavenderContainer,
    onSecondaryContainer = Color(0xFF3B2E63),
    tertiary = IncomeGreenLight,
    onTertiary = Color.White,
    tertiaryContainer = MintContainer,
    onTertiaryContainer = Color(0xFF08483B),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFFFF0F3),
    onSurfaceVariant = Color(0xFF6B5C66),
    surfaceTint = Primary,
    error = ErrorRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFE2E0),
    onErrorContainer = Color(0xFF6E1F1B),
    outline = LightOutline,
    outlineVariant = Color(0xFFF7E9ED),
    scrim = Color(0xFF44293A)
)

/** 深色：柔和深紫蓝灰底 + 浅粉主色，避免纯黑与刺眼高饱和。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA8C2),
    onPrimary = Color(0xFF3B1226),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color(0xFFFFD9E5),
    secondary = Color(0xFFC3B0F0),
    onSecondary = Color(0xFF2E1F4D),
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = Color(0xFFE8DEFF),
    tertiary = IncomeGreenDark,
    onTertiary = Color(0xFF0A3B31),
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = Color(0xFFCFF6EA),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF3B3342),
    onSurfaceVariant = Color(0xFFD7CBD9),
    surfaceTint = Color(0xFFFFA8C2),
    error = ErrorRedDark,
    onError = Color(0xFF3B1226),
    errorContainer = Color(0xFF6E2A2A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = DarkOutline,
    outlineVariant = Color(0xFF3B3342),
    scrim = Color(0xFF0E0A12)
)

/** 全局大圆角：卡片 20dp、弹窗/大容器 28dp，按钮/输入框走 small 12dp。 */
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** 应用主题；Android 12+ 优先跟随动态取色，关闭场景由 darkTheme 控制。 */
@Composable
fun BookkeeperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            // 浅色主题状态栏/导航栏图标用深色，深色主题用浅色，保证可读性。
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

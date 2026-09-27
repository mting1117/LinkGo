package com.moting.linkgo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// 谷歌蓝+黑白灰明亮主题 (完美去除默认紫/粉杂色)
private val GrayscaleLightColorScheme = lightColorScheme(
    primary = GoogleBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = GoogleBlueLight,
    secondary = GrayscaleTextSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F3F4), // 纯浅灰替代带紫偏色
    onSecondaryContainer = GrayscaleTextPrimaryLight,
    tertiary = GoogleBlueLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8F0FE),
    onTertiaryContainer = GoogleBlueLight,
    background = GrayscaleBgLight,
    onBackground = GrayscaleTextPrimaryLight,
    surface = GrayscaleSurfaceLight,
    onSurface = GrayscaleTextPrimaryLight,
    surfaceVariant = Color(0xFFE8EAED), // 完全与背景一体化的白色卡片面变为灰色底面
    onSurfaceVariant = GrayscaleTextSecondaryLight,
    outline = Color(0xFF75777A),
    outlineVariant = Color(0xFFC4C7CC),

    // M3 深度净化属性
    surfaceDim = Color(0xFFE5E7EB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFFFFFFF), // 强制定为纯白卡片底板，与对比图2一致
    surfaceContainerHigh = Color(0xFFF3F4F6),
    surfaceContainerHighest = Color(0xFFE5E7EB)
)

// 谷歌蓝+黑白灰暗黑主题 (完美去除默认紫/粉杂色)
private val GrayscaleDarkColorScheme = darkColorScheme(
    primary = GoogleBlueDark,
    onPrimary = GrayscaleSurfaceDark,
    primaryContainer = Color(0xFF3C4043),
    onPrimaryContainer = GoogleBlueDark,
    secondary = GrayscaleTextSecondaryDark,
    onSecondary = GrayscaleSurfaceDark,
    secondaryContainer = Color(0xFF303134), // 纯中灰替代带紫偏色
    onSecondaryContainer = GrayscaleTextPrimaryDark,
    tertiary = GoogleBlueDark,
    onTertiary = GrayscaleSurfaceDark,
    tertiaryContainer = Color(0xFF3C4043),
    onTertiaryContainer = GoogleBlueDark,
    background = GrayscaleBgDark,
    onBackground = GrayscaleTextPrimaryDark,
    surface = GrayscaleSurfaceDark,
    onSurface = GrayscaleTextPrimaryDark,
    surfaceVariant = GrayscaleSurfaceDark,
    onSurfaceVariant = GrayscaleTextSecondaryDark,
    outline = Color(0xFF75777A),
    outlineVariant = Color(0xFF4C4F52),

    // M3 深度净化属性
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF2C2C30),
    surfaceContainerLowest = Color(0xFF0F0F11),
    surfaceContainerLow = Color(0xFF1C1D20),
    surfaceContainer = GrayscaleSurfaceDark,
    surfaceContainerHigh = Color(0xFF2E3034),
    surfaceContainerHighest = Color(0xFF3C3E42)
)

@Composable
fun 链接跳转Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> GrayscaleDarkColorScheme
        else -> GrayscaleLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
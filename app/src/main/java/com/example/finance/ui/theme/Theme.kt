package com.example.finance.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

// 财务主题深色方案
private val DarkColorScheme = darkColorScheme(
    primary = FinanceGreen80,
    onPrimary = Color(0xFF00382A),
    primaryContainer = FinanceGreenContainerDark,
    onPrimaryContainer = OnFinanceGreenContainerDark,
    secondary = FinanceMint80,
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF0F4E4E),
    onSecondaryContainer = Color(0xFFBDD7D7),
    tertiary = FinanceGold80,
    onTertiary = Color(0xFF4B2E00),
    tertiaryContainer = FinanceGoldContainerDark,
    onTertiaryContainer = OnFinanceGoldContainerDark,
    background = FinanceBgDark,
    onBackground = OnFinanceDark,
    surface = FinanceSurfaceDark,
    onSurface = OnFinanceDark,
    surfaceVariant = Color(0xFF2A312E),
    onSurfaceVariant = Color(0xFFC5CEC8),
    outline = FinanceOutlineDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// 财务主题浅色方案（墨绿主色）
private val LightColorScheme = lightColorScheme(
    primary = FinanceGreen40,
    onPrimary = Color.White,
    primaryContainer = FinanceGreenContainer,
    onPrimaryContainer = OnFinanceGreenContainer,
    secondary = FinanceMint40,
    onSecondary = Color.White,
    secondaryContainer = FinanceMintContainer,
    onSecondaryContainer = OnFinanceMintContainer,
    tertiary = FinanceGold40,
    onTertiary = Color.White,
    tertiaryContainer = FinanceGoldContainer,
    onTertiaryContainer = OnFinanceGoldContainer,
    background = FinanceBg,
    onBackground = OnFinance,
    surface = FinanceSurface,
    onSurface = OnFinance,
    surfaceVariant = Color(0xFFE5E8E3),
    onSurfaceVariant = Color(0xFF414845),
    outline = FinanceOutline,
    error = FinanceError,
    onError = Color.White
)

// 蓝色主题浅色方案（实验版：替换墨绿为"冷静理性"的蓝，见 Color.kt RationalBlue*）
private val BlueLightColorScheme = lightColorScheme(
    primary = RationalBlue40,
    onPrimary = Color.White,
    primaryContainer = RationalBlueContainer,
    onPrimaryContainer = OnRationalBlueContainer,
    secondary = RationalBlueSecondary40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4E8EC),
    onSecondaryContainer = Color(0xFF00262B),
    tertiary = RationalBlueTertiary40,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2B1600),
    background = RationalBlueBg,
    onBackground = OnFinance,
    surface = RationalBlueSurface,
    onSurface = OnFinance,
    surfaceVariant = Color(0xFFDAE3EA),
    onSurfaceVariant = Color(0xFF41474D),
    outline = RationalBlueOutline,
    error = FinanceError,
    onError = Color.White
)

// 蓝色主题深色方案
private val BlueDarkColorScheme = darkColorScheme(
    primary = RationalBlue80,
    onPrimary = Color(0xFF00344F),
    primaryContainer = RationalBlueContainerDark,
    onPrimaryContainer = OnRationalBlueContainerDark,
    secondary = RationalBlueSecondary80,
    onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF004E52),
    onSecondaryContainer = Color(0xFFC4E8EC),
    tertiary = RationalBlueTertiary80,
    onTertiary = Color(0xFF3D2500),
    tertiaryContainer = Color(0xFF4A3100),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = RationalBlueBgDark,
    onBackground = OnFinanceDark,
    surface = RationalBlueSurfaceDark,
    onSurface = OnFinanceDark,
    surfaceVariant = Color(0xFF394148),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = RationalBlueOutlineDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun FinanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色默认关闭：保持"墨绿金"品牌观感（需要系统动态色可传 true）
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
        }

        darkTheme -> BlueDarkColorScheme
        else -> BlueLightColorScheme
    }

    // 让系统状态栏/导航栏贴合主题
    (context as? Activity)?.let { act ->
        val window = act.window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        window.statusBarColor = colorScheme.background.toArgb()
        window.navigationBarColor = colorScheme.background.toArgb()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

package com.example.finance.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

// ===== 理性 Rational · 薄荷绿设计稿浅色方案 (rational-ui-components.html) =====
private val MintLightColorScheme = lightColorScheme(
    primary = RationalMint40,
    onPrimary = Color.White,
    primaryContainer = RationalMintContainer,
    onPrimaryContainer = OnRationalMintContainer,
    secondary = RationalSky40,
    onSecondary = Color.White,
    secondaryContainer = RationalSkyContainer,
    onSecondaryContainer = OnRationalSkyContainer,
    tertiary = Color(0xFFB26A00),            // 强调：琥珀金
    onTertiary = Color.White,
    tertiaryContainer = RationalWarningContainer,
    onTertiaryContainer = RationalOnWarning,
    background = RationalBg,
    onBackground = RationalText,
    surface = RationalCard,
    onSurface = RationalText,
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = RationalText2,
    outline = RationalText3,
    outlineVariant = RationalBorder,
    error = RationalDanger40,
    onError = Color.White,
    errorContainer = RationalDangerContainer,
    onErrorContainer = RationalOnDanger
)

// ===== 薄荷绿深色方案 =====
private val MintDarkColorScheme = darkColorScheme(
    primary = RationalMintDarkPrimary,
    onPrimary = RationalMintDarkOnPrimary,
    primaryContainer = RationalMintDarkContainer,
    onPrimaryContainer = RationalMintDarkOnContainer,
    secondary = RationalSkyDark,
    onSecondary = Color(0xFF0B2C45),
    secondaryContainer = RationalSkyDarkContainer,
    onSecondaryContainer = RationalSkyDarkOnContainer,
    tertiary = Color(0xFFFFC663),
    onTertiary = Color(0xFF3D2600),
    tertiaryContainer = Color(0xFF4A3A00),
    onTertiaryContainer = Color(0xFFFFDDB1),
    background = RationalBgDark,
    onBackground = RationalTextDark,
    surface = RationalCardDark,
    onSurface = RationalTextDark,
    surfaceVariant = Color(0xFF232C27),
    onSurfaceVariant = RationalText2Dark,
    outline = RationalText3Dark,
    outlineVariant = RationalBorderDark,
    error = RationalDangerDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

// 圆角体系：参照设计稿 sm 8 / md 12 / lg 16 / xl 24
private val MintShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun FinanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色默认关闭：保持"理性·薄荷绿"品牌观感（需要系统动态色可传 true）
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

        darkTheme -> MintDarkColorScheme
        else -> MintLightColorScheme
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
        shapes = MintShapes,
        content = content
    )
}

// ui/theme/LibanTokens.kt
// liban-main 移植：全局设计 Token（颜色 / 间距 / 圆角）。
// 页面与组件统一从这里取色，避免大量硬编码；改这里即可全局换肤。
// 来源：liban-main/app/src/main/java/com/liban/android/ui/theme/LibanTheme.kt（MIT, 整体移植）。

package com.example.finance.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距 Token */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
}

/** 圆角 Token */
object Radius {
    val s: Dp = 12.dp
    val m: Dp = 16.dp
    val l: Dp = 20.dp
    val sheet: Dp = 28.dp
}

/**
 * 语义色板：浅色背景、青绿色主色（liban-main 原值）。
 */
data class LibanColors(
    val background: Color = Color(0xFFF5FAF8),
    val surface: Color = Color(0xFFFFFFFF),
    val textPrimary: Color = Color(0xFF182B26),
    val textSecondary: Color = Color(0xFF6E837D),
    val textTertiary: Color = Color(0xFF9DB0AA),
    val primary: Color = Color(0xFF27BD9F),
    val primaryDeep: Color = Color(0xFF14907A),
    val primaryContainer: Color = Color(0xFFDFF5EE),
    val onPrimary: Color = Color(0xFFFFFFFF),
    val success: Color = Color(0xFF27BD9F),
    val successContainer: Color = Color(0xFFE3F7EF),
    val danger: Color = Color(0xFFEF6A6A),
    val dangerContainer: Color = Color(0xFFFDE8E8),
    val warning: Color = Color(0xFFF5A623),
    val warningContainer: Color = Color(0xFFFDF1DC),
    val info: Color = Color(0xFF5A8DEE),
    val infoContainer: Color = Color(0xFFE8F0FE),
    val darkCard: Color = Color(0xFF14352C),
    val gold: Color = Color(0xFFF2B01E),
    val outline: Color = Color(0xFFE3EDE9),
)

val LocalLibanColors = staticCompositionLocalOf { LibanColors() }

/** 组件内快速取色：val c = libanColors() */
@Composable
fun libanColors(): LibanColors = LocalLibanColors.current

/**
 * 全局主题：把 liban Token 映射到 MaterialTheme。
 * 老页面（账单/我的等）走 colorScheme 自动继承新配色。
 */
@Composable
fun LibanTheme(content: @Composable () -> Unit) {
    val c = LibanColors()
    CompositionLocalProvider(LocalLibanColors provides c) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = c.primary,
                onPrimary = c.onPrimary,
                primaryContainer = c.primaryContainer,
                onPrimaryContainer = c.textPrimary,
                secondary = c.primaryDeep,
                background = c.background,
                onBackground = c.textPrimary,
                surface = c.surface,
                onSurface = c.textPrimary,
                surfaceVariant = c.primaryContainer,
                onSurfaceVariant = c.textSecondary,
                error = c.danger,
                outline = c.outline,
            ),
            content = content,
        )
    }
}

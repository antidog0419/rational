package com.example.finance.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ===== liban-main 移植：全局统一使用 LibanColors（青绿 #27BD9F 体系），见 LibanTokens.kt =====
// liban 为浅色设计稿，故固定 light scheme，不随系统明暗切换；仅同步状态栏/导航栏配色。

// 圆角体系（与既有 8/12/16/24 档近似；liban 组件多以 Radius.* 内联，见 LibanTokens.kt）
private val FinanceShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun FinanceTheme(
    // 兼容旧签名保留参数；liban 换肤为浅色体系，不随系统明暗切换
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    // 动态取色关闭：保持 liban 品牌观感
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val c = LibanColors()

    // 让系统状态栏/导航栏贴合主题背景（浅色背景 → 深色系统图标）
    (context as? Activity)?.let { act ->
        val window = act.window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = c.background.toArgb()
        window.navigationBarColor = c.background.toArgb()
    }

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
            typography = Typography,
            shapes = FinanceShapes,
            content = content
        )
    }
}

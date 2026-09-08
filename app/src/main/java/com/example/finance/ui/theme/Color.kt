package com.example.finance.ui.theme

import androidx.compose.ui.graphics.Color

// ============ liban-main 统一配色（#27BD9F 青绿体系，2026-09-08 全面换肤） ============
// 说明：为保持旧页面（RationalHome 等）直接引用常量不炸，标识符沿用历史名，
// 值已整体替换为 liban-main 的 LibanColors 设计稿色值；新组件走 LibanTokens.kt 的 libanColors()。

// 主色（primary）
val RationalMint40 = Color(0xFF27BD9F)        // 主色深(按钮/进度条/激活态文字) = liban primary
val RationalMint50 = Color(0xFF3FD0B1)        // 主色亮(环形/渐变亮部/徽章) = liban primary 高光
val RationalMintContainer = Color(0xFFDFF5EE) // 主色浅底(卡片/图标底) = liban primaryContainer
val OnRationalMintContainer = Color(0xFF0C3B2F)
val RationalMintRing = Color(0xFFC9EDE3)      // 环形轨道
val RationalMintBorder = Color(0xFFC8EBE0)    // 描边
// 辅助蓝（info）
val RationalSky40 = Color(0xFF5A8DEE)         // = liban info
val RationalSkyContainer = Color(0xFFE8F0FE)
val OnRationalSkyContainer = Color(0xFF16375C)
// 语义色
val RationalDanger40 = Color(0xFFEF6A6A)      // = liban danger
val RationalDangerContainer = Color(0xFFFDE8E8)
val RationalOnDanger = Color(0xFF8C3A3A)
val RationalSuccess40 = Color(0xFF27BD9F)     // = liban success（同主色）
val RationalSuccessContainer = Color(0xFFE3F7EF)
val RationalOnSuccess = Color(0xFF0F5A43)
val RationalWarning40 = Color(0xFFF5A623)     // = liban warning
val RationalWarningContainer = Color(0xFFFDF1DC)
val RationalOnWarning = Color(0xFF7A4A00)
val RationalPurple40 = Color(0xFF805AD5)
val RationalPurpleContainer = Color(0xFFF3EDFF)
// 中性色
val RationalBg = Color(0xFFF5FAF8)            // 页面背景 = liban background
val RationalCard = Color(0xFFFFFFFF)          // 卡片 = liban surface
val RationalText = Color(0xFF182B26)          // 主文字 = liban textPrimary
val RationalText2 = Color(0xFF6E837D)         // 次级文字 = liban textSecondary
val RationalText3 = Color(0xFF9DB0AA)         // 弱文字 = liban textTertiary
val RationalBorder = Color(0xFFE3EDE9)        // 分隔线/描边 = liban outline

// ============ 深色（薄荷绿暗色，值按 liban 主色推导；FinanceTheme 固定浅色，仅供残留引用） ============
val RationalMintDarkPrimary = Color(0xFF63D6BC)
val RationalMintDarkOnPrimary = Color(0xFF0B3B2F)
val RationalMintDarkContainer = Color(0xFF0E5342)
val RationalMintDarkOnContainer = Color(0xFFC9F5E6)
val RationalSkyDark = Color(0xFF8CC7F0)
val RationalSkyDarkContainer = Color(0xFF0F3A5E)
val RationalSkyDarkOnContainer = Color(0xFFD2E9FF)
val RationalDangerDark = Color(0xFFFFB4AB)
val RationalBgDark = Color(0xFF101513)
val RationalCardDark = Color(0xFF1A211D)
val RationalTextDark = Color(0xFFE3E9E4)
val RationalText2Dark = Color(0xFFAEB8B1)
val RationalText3Dark = Color(0xFF79857E)
val RationalBorderDark = Color(0xFF2A332D)

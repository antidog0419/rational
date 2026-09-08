package com.example.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.components.HomeHeader
import com.example.finance.ui.components.LibanCard
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * "我的"聚合页：新页面（数据权限 / 会员订阅 / AI 咨询）与真实功能（预算 / 储蓄目标 / 系统设置）的入口。
 */
@Composable
fun ProfileScreen(
    onOpenDataPrivacy: () -> Unit,
    onOpenMembership: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenGoal: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConsult: (() -> Unit)? = null,
) {
    val c = libanColors()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        item { HomeHeader(greeting = "我的", subtitle = "账号与功能入口") }

        item {
            // 新 UI 页面入口
            LibanCard {
                if (onOpenConsult != null) {
                    ProfileRow("🧭", "AI 咨询与识屏助手", "每周小结 / Top3 / 识屏决策", onClick = onOpenConsult)
                    HorizontalDivider(color = c.outline)
                }
                ProfileRow("🛡️", "我的数据与权限", "本地存储声明 / 灵敏度 / 场景开关", onClick = onOpenDataPrivacy)
                HorizontalDivider(color = c.outline)
                ProfileRow("👑", "会员订阅", "基础版 / 认知版 / 家庭版", onClick = onOpenMembership)
            }
        }

        item {
            // 原有功能入口
            LibanCard {
                ProfileRow("📊", "预算设置", "月预算与分类预算（真实）", onClick = onOpenBudget)
                HorizontalDivider(color = c.outline)
                ProfileRow("🎯", "储蓄目标", "目标名称 / 金额 / 进度（真实）", onClick = onOpenGoal)
                HorizontalDivider(color = c.outline)
                ProfileRow("⚙️", "系统与账单设置", "无障碍 / DeepSeek / CSV / 清空", onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun ProfileRow(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    val c = libanColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(Radius.m)).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji) }
        Spacer(Modifier.size(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = c.textTertiary)
    }
}

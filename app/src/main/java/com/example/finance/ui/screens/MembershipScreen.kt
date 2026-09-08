package com.example.finance.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.finance.ui.components.BannerStyle
import com.example.finance.ui.components.InfoBanner
import com.example.finance.ui.components.PageHeader
import com.example.finance.ui.components.PlanCard
import com.example.finance.ui.mock.MembershipMock
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 页面 5：会员订阅（参考图 5）。
 * 本地交互：方案选择高亮（Mock，无真实购买）。
 */
@Composable
fun MembershipScreen(onBack: () -> Unit) {
    val mock = MembershipMock()
    val c = libanColors()
    var selectedId by remember { mutableStateOf("pro") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("会员订阅", onBack)
        Column(
            Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.s))

            // 皇冠图标 + 标题
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text("👑", style = MaterialTheme.typography.headlineMedium) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(mock.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    mock.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // 方案三列
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                mock.plans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        selected = selectedId == plan.id,
                        onSelect = { selectedId = plan.id },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 省钱 banner
            InfoBanner(text = mock.savingBanner, style = BannerStyle.SUCCESS, leadingEmoji = "💰")

            // CTA
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(Radius.m))
                    .background(c.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(mock.cta, color = c.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }
            Text(mock.footnote, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

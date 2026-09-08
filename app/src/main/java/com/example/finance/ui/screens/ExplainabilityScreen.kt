package com.example.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.components.LibanCard
import com.example.finance.ui.components.PageHeader
import com.example.finance.ui.components.SectionCard
import com.example.finance.ui.components.TagChip
import com.example.finance.ui.mock.ExplainabilityMock
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 页面 3：为什么给出这条提醒（可解释性页，参考图 3）。
 * 纯 Mock 展示：基础判断依据 + 行为经济学原理 + 反事实推演。
 */
@Composable
fun ExplainabilityScreen(onBack: () -> Unit) {
    val mock = ExplainabilityMock()
    val c = libanColors()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("为什么给出这条提醒", onBack)
        Column(
            Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // 基础判断依据
            SectionCard(title = "📋 基础判断依据") {
                mock.facts.forEachIndexed { index, fact ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.s),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text(fact.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (index != mock.facts.lastIndex) HorizontalDivider(color = c.outline)
                }
            }

            // 行为经济学原理
            SectionCard(title = "🧩 行为经济学原理") {
                mock.principles.forEachIndexed { index, principle ->
                    Row(Modifier.padding(vertical = Spacing.s)) {
                        Text(principle.emoji, style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.padding(start = Spacing.s)) {
                            Text(principle.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(principle.body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                    if (index != mock.principles.lastIndex) HorizontalDivider(color = c.outline)
                }
            }

            // 反事实推演
            LibanCard(backgroundColor = c.darkCard) {
                TagChip("🔮 反事实推演", container = c.primary, contentColor = c.onPrimary)
                Spacer(Modifier.height(Spacing.s))
                Text(mock.counterfactual, style = MaterialTheme.typography.bodySmall, color = c.surface.copy(alpha = 0.85f))
            }
        }
    }
}

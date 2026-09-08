package com.example.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.components.BannerStyle
import com.example.finance.ui.components.InfoBanner
import com.example.finance.ui.components.LibanCard
import com.example.finance.ui.components.MiniBars
import com.example.finance.ui.components.PageHeader
import com.example.finance.ui.components.SectionCard
import com.example.finance.ui.components.ToggleRow
import com.example.finance.ui.mock.PrivacyMock
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 页面 4：我的数据与权限（参考图 4）。
 * 本地交互：灵敏度 Slider + 场景开关（Mock 状态，不落库）。
 */
@Composable
fun DataPrivacyScreen(onBack: () -> Unit) {
    val mock = PrivacyMock()
    val c = libanColors()
    var sensitivity by remember { mutableFloatStateOf(mock.sensitivity.toFloat()) }
    val switches = remember { mutableStateListOf<Boolean>().apply { addAll(mock.scenes.map { it.enabled }) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("我的数据与权限", onBack)
        Column(
            Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // 本地存储声明
            InfoBanner(
                text = "所有数据均加密存储于本地设备，绝不上传云端。截图即时识别后即刻销毁。",
                style = BannerStyle.SUCCESS,
                leadingEmoji = "🛡️",
            )

            // AI 干预灵敏度
            SectionCard(title = "🎚️ AI 干预灵敏度") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${sensitivity.toInt()}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = c.primaryDeep,
                    )
                    Text("  ${mock.sensitivityLabel}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = c.primary,
                        activeTrackColor = c.primary,
                        inactiveTrackColor = c.primaryContainer,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1 宽松", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text("5 平衡", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text("10 严格", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }

            // 场景管理
            SectionCard(title = "🗂️ 场景管理") {
                mock.scenes.forEachIndexed { index, scene ->
                    ToggleRow(
                        emoji = scene.emoji,
                        title = scene.title,
                        subtitle = scene.subtitle,
                        checked = switches[index],
                        onChange = { switches[index] = it },
                    )
                }
            }

            // 近 7 天干预分布
            LibanCard {
                Text("📊 近 7 天干预分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Spacing.m))
                MiniBars(values = mock.weeklyBars, highlightIndex = mock.weeklyHighlight)
                Spacer(Modifier.height(Spacing.s))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                        Text(day, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    }
                }
            }
        }
    }
}

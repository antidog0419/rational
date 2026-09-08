package com.example.finance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.mock.InterventionDetail
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 支付临界点 AI 干预弹窗（ModalBottomSheet）。
 * 结构参考图 2：预算进度 + 明细行 + AI 分析 + 采纳/仍要支付 + 为什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterventionSheet(
    data: InterventionDetail,
    onDismiss: () -> Unit,
    onAdopt: () -> Unit,
    onPayAnyway: () -> Unit,
    onWhy: () -> Unit,
) {
    val c = libanColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = c.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛒", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "  支付临界点提醒",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TagChip("⚡ AI 干预")
            }
            Spacer(Modifier.height(Spacing.l))

            // 预算进度块（红色警戒）
            LibanCard(backgroundColor = c.dangerContainer, cornerRadius = Radius.m) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("本月预算剩余", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text("${data.remainingPercent}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = c.danger)
                    Text(" %", style = MaterialTheme.typography.titleMedium, color = c.danger)
                }
                Spacer(Modifier.height(Spacing.s))
                LinearProgressIndicator(
                    progress = { 1f - data.remainingPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = c.danger,
                    trackColor = c.surface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text("已用 ${data.usedAmount} · 预算 ${data.budgetAmount}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Spacer(Modifier.height(Spacing.l))

            // 明细行
            data.rows.forEach { row ->
                KeyValueRow(row.label, row.value, row.valueColor ?: c.textPrimary)
            }
            Spacer(Modifier.height(Spacing.l))

            // AI 分析
            LibanCard(backgroundColor = c.successContainer, cornerRadius = Radius.m, contentPadding = Spacing.l) {
                Text(
                    "AI 分析：" + data.aiAnalysis,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.primaryDeep,
                )
                Spacer(Modifier.height(Spacing.s))
                Text("💰 " + data.saveHint, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = c.primaryDeep)
            }
            Spacer(Modifier.height(Spacing.l))

            // 主操作
            Button(
                onClick = onAdopt,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary),
            ) { Text("采纳建议 · 冷静 24 小时", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(Spacing.s))
            OutlinedButton(
                onClick = onPayAnyway,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger),
            ) { Text("我已深思熟虑，仍要支付", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = onWhy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("为什么给出这条提醒 →", color = c.textSecondary)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                data.footerNote,
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

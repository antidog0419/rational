// ui/screens/RealSecondaryScreens.kt
// liban 移植的「我的」二级真实页：预算设置 / 储蓄目标。
// 预算 = BudgetStore（FinanceDb 口径，首页/AI 点评共用同一事实源）；
// 储蓄目标 = agent.db SavingGoal（AgentGraph.repository，识屏决策引擎真实使用）。

package com.example.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.finance.agent.AgentGraph
import com.example.finance.data.BillCategories
import com.example.finance.data.BudgetStore
import com.example.finance.ui.components.InfoBanner
import com.example.finance.ui.components.LibanCard
import com.example.finance.ui.components.PageHeader
import com.example.finance.ui.components.BannerStyle
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fmtPlain(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else "%.2f".format(v).trimEnd('0').trimEnd('.')

/**
 * 预算设置（真实）：月总预算 + 分类预算 → BudgetStore。
 * 供「我的 → 预算设置」二级页。
 */
@Composable
fun BudgetRealScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = libanColors()
    var monthlyInput by remember { mutableStateOf(fmtPlain(BudgetStore.monthlyBudget())) }
    var catInputs by remember { mutableStateOf(HashMap(BudgetStore.catBudgets())) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("预算设置", onBack)
        Column(
            Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            InfoBanner(
                text = "预算用于：首页预算进度、实时 AI 点评与悬浮窗消费提醒。所有数据仅存本机。",
                style = BannerStyle.INFO,
                leadingEmoji = "🎯",
            )
            LibanCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    OutlinedTextField(
                        value = monthlyInput,
                        onValueChange = { monthlyInput = it },
                        label = { Text("本月总预算（元）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("分类预算（留空 = 该分类不限）", style = MaterialTheme.typography.labelMedium,
                        color = c.textSecondary)
                    BillCategories.PRESETS.forEach { cat ->
                        val cur = catInputs[cat]
                        OutlinedTextField(
                            value = cur?.let(::fmtPlain) ?: "",
                            onValueChange = { v ->
                                catInputs = HashMap(catInputs).apply {
                                    if (v.isBlank()) remove(cat)
                                    else {
                                        val num = v.toDoubleOrNull()
                                        if (num != null && num > 0.0) put(cat, num)
                                    }
                                }
                            },
                            label = { Text("$cat（元）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(onClick = {
                        val mv = monthlyInput.trim().toDoubleOrNull()
                        if (mv == null || mv < 0.0) {
                            android.widget.Toast.makeText(
                                context, "月预算需为 ≥ 0 的数字", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            BudgetStore.setMonthlyBudget(mv)
                            BudgetStore.setCatBudgets(catInputs)
                            android.widget.Toast.makeText(context, "✅ 预算已保存", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存预算") }
                    TextButton(onClick = {
                        BudgetStore.resetDemo()
                        monthlyInput = fmtPlain(BudgetStore.monthlyBudget())
                        catInputs = HashMap(BudgetStore.catBudgets())
                        android.widget.Toast.makeText(context, "已恢复演示预算", android.widget.Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("恢复演示预算", style = MaterialTheme.typography.labelMedium, color = c.primaryDeep)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text("当前月预算：¥${fmtPlain(BudgetStore.monthlyBudget())} · 分类 ${BudgetStore.catBudgets().size} 项",
                style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

/**
 * 储蓄目标（真实）：读/写 agent.db SavingGoal（识屏决策引擎「目标占用/延迟」用它）。
 */
@Composable
fun GoalRealScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = libanColors()
    val scope = rememberCoroutineScope()
    val goal by AgentGraph.repository.goal.collectAsState(initial = null)

    var name by remember(goal) { mutableStateOf(goal?.name.orEmpty()) }
    var target by remember(goal) { mutableStateOf(goal?.targetAmountCents?.div(100.0)?.let(::fmtPlain).orEmpty()) }
    var current by remember(goal) { mutableStateOf(goal?.currentAmountCents?.div(100.0)?.let(::fmtPlain).orEmpty()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("储蓄目标", onBack)
        Column(
            Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            InfoBanner(
                text = "识屏决策时按此目标计算「占用比例 / 建议延迟天数」。数据存本机 agent.db。",
                style = BannerStyle.SUCCESS,
                leadingEmoji = "🐷",
            )
            LibanCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    OutlinedTextField(name, { name = it }, label = { Text("目标名称") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    MoneyInput("目标金额", target) { target = it }
                    MoneyInput("当前金额", current) { current = it }
                    Button(onClick = {
                        val targetCents = (target.trim().toDoubleOrNull() ?: return@Button)
                            .let { (it * 100).toLong() }
                        val currentCents = (current.trim().toDoubleOrNull() ?: 0.0)
                            .let { (it * 100).toLong() }
                        scope.launch(Dispatchers.IO) {
                            AgentGraph.repository.saveGoal(
                                name = name.ifBlank { "储蓄目标" },
                                targetCents = targetCents,
                                currentCents = currentCents,
                                deadlineEpochDay = goal?.deadlineEpochDay
                                    ?: (java.time.LocalDate.now().plusMonths(6).toEpochDay()),
                            )
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "✅ 目标已保存", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存目标") }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            val pct = if ((goal?.targetAmountCents ?: 1) > 0)
                ((goal?.currentAmountCents ?: 0) * 100f / goal?.targetAmountCents!!).toInt()
                else 0
            Text(
                if (goal == null) "暂无储蓄目标：识屏引擎会按月预算自动创建。"
                else "当前进度：${(goal!!.currentAmountCents / 100.0).let(::fmtPlain)} / ${(goal!!.targetAmountCents / 100.0).let(::fmtPlain)}（约 $pct%）",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
            )
        }
    }
}

@Composable
private fun MoneyInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text("$label（元）") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
}

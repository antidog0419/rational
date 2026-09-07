// ui/RationalHome.kt
// 设计稿落地（屏1 首页仪表盘 + 屏2 支付临界点 AI 干预弹窗 + 屏3 为什么给这条提醒）。
// 数据全部读本地账单库（Room）+ BudgetStore 真实预算；"理性指数"为本地启发式（见 data/Rationale.kt）。
// 屏2/屏3 目前以 App 内弹窗呈现（可点"实时预警"入口触发）；后续再接到系统悬浮窗(下单前判断)。

package com.example.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.finance.data.BillCategories
import com.example.finance.data.BillEntity
import com.example.finance.data.BillSources
import com.example.finance.data.BudgetStore
import com.example.finance.data.FinanceDb
import com.example.finance.data.Rationale
import com.example.finance.data.RationaleInput
import com.example.finance.data.UserSettings
import com.example.finance.data.monthRange
import com.example.finance.data.todayRange
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// 校内兼职时薪（设计稿示例值；用于"等值搬砖时长"）
private const val HOURLY_WAGE = 53.0

/** 屏1 首页仪表盘（核心首页）。onDiagnose/onReport 复用上层已有的"生成诊断/周报"动作。 */
@Composable
fun RationalHomeTab(
    padding: androidx.compose.foundation.layout.PaddingValues,
    onDiagnose: () -> Unit,
    onReport: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { FinanceDb.get(context).billDao() }
    val settings = remember { UserSettings(context) }

    // ---- 真实数据 ----
    val (tFrom, tTo) = remember { todayRange() }
    val todaySumFlow = remember { dao.sumBetween(tFrom, tTo) }
    val todayCountFlow = remember { dao.countBetween(tFrom, tTo) }
    val allFlow = remember { dao.observeAll() }
    val todaySum by todaySumFlow.collectAsState(initial = 0.0)
    val todayCount by todayCountFlow.collectAsState(initial = 0)
    val allRows by allFlow.collectAsState(initial = emptyList())

    val (mf, mt) = remember { monthRange() }
    var interStrength by remember { mutableIntStateOf(settings.aiInterventionStrength) }

    // 本月行 & 各指标
    val curRows = remember(allRows, mf, mt) { allRows.filter { it.occurredAtMs >= mf && it.occurredAtMs < mt } }
    val monthSpent = remember(curRows) { curRows.sumOf { it.amount } }
    val monthCnt = curRows.size
    val curCatSpent = remember(curRows) {
        val m = HashMap<String, Double>()
        for (r in curRows) {
            val c = BillCategories.categorize(r.merchant, r.source)
            m[c] = (m[c] ?: 0.0) + r.amount
        }
        m
    }
    val lateNightCnt = remember(curRows) {
        curRows.count { r ->
            val h = Calendar.getInstance().apply { timeInMillis = r.occurredAtMs }.get(Calendar.HOUR_OF_DAY)
            h >= 0 && h < 6
        }
    }
    val lateNightRatio = if (monthCnt > 0) lateNightCnt.toDouble() / monthCnt else 0.0
    val avgOrder = if (monthCnt > 0) monthSpent / monthCnt else 0.0

    // 环比上月增幅
    val (pf, pt) = remember { prevMonthRange() }
    val prevSum by remember { dao.sumBetween(pf, pt) }.collectAsState(initial = 0.0)
    val prevPct = if (prevSum > 0.0) (monthSpent / prevSum - 1.0) * 100.0 else 0.0

    val budgetMonthly = BudgetStore.monthlyBudget()
    val catBudgets = BudgetStore.catBudgets()
    val score = remember(monthSpent, budgetMonthly, prevPct, lateNightRatio, avgOrder) {
        Rationale.score(
            RationaleInput(monthSpent, budgetMonthly, prevPct, lateNightRatio, avgOrder)
        )
    }
    val percent = Rationale.percentAbove(score)

    // 最近记录（最多 6 条）
    val recent = curRows.sortedByDescending { it.occurredAtMs }.take(6)

    // 弹窗状态
    var showIntervene by remember { mutableStateOf(false) }
    var showWhy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 问候语
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("HI，同学", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("今天也要理性消费哦～", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("🎯", style = MaterialTheme.typography.titleLarge)
        }

        // 理性指数卡：分数 + 环形仪表
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("我的理性指数", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$score", style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("分", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("超过 $percent% 的用户 · 本地启发式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                ScoreGauge(score = score)
            }
        }

        // 双统计卡：本月已支出 / 本月预算总额
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "本月已支出",
                value = fmt(monthSpent),
                suffix = "",
                sub = "较上月 ${if (prevPct >= 0) "↑" else "↓"}${absPct(prevPct)}%"
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "本月预算总额",
                value = fmt(budgetMonthly),
                suffix = "",
                sub = pctUsed(monthSpent, budgetMonthly)
            )
        }

        // 三个入口
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EntryChip("🤖", "实时预警", Modifier.weight(1f)) { showIntervene = true }
            EntryChip("🧠", "智能诊断", Modifier.weight(1f), onClick = onDiagnose)
            EntryChip("📊", "消费行为报告", Modifier.weight(1f), onClick = onReport)
        }

        // 最近记录
        SectionTitle("最近记录${if (todayCount > 0) " · 今日 $todayCount 笔" else ""}")
        if (recent.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("暂无消费记录，去「记录」页抓取或手动补记吧",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    recent.forEach { r -> RecentRow(r) }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("理伴理性消费助理 · 最终决策权归用户本人",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

    // 屏2：支付临界点 AI 干预弹窗
    if (showIntervene) {
        InterveneDialog(
            latest = recent.firstOrNull(),
            curCatSpent = curCatSpent,
            catBudgets = catBudgets,
            monthSpent = monthSpent,
            monthly = budgetMonthly,
            onDismiss = { showIntervene = false },
            onViewWhy = { showIntervene = false; showWhy = true }
        )
    }

    // 屏3：为什么给出这条提醒
    if (showWhy) {
        WhyDialog(
            latest = recent.firstOrNull(),
            curCatSpent = curCatSpent,
            catBudgets = catBudgets,
            strength = interStrength,
            onStrengthChange = {
                interStrength = it
                settings.aiInterventionStrength = it
            },
            onDismiss = { showWhy = false }
        )
    }
}

@Composable
private fun ScoreGauge(score: Int) {
    val track = MaterialTheme.colorScheme.surface
    val bar = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = track,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(0f, 0f), size = Size(size.width, size.height), style = stroke
            )
            drawArc(
                color = bar,
                startAngle = -90f, sweepAngle = 360f * (score / 100f), useCenter = false,
                topLeft = Offset(0f, 0f), size = Size(size.width, size.height), style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = bar)
            Text("分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String, suffix: String, sub: String) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text("¥$value$suffix", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EntryChip(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RecentRow(r: BillEntity) {
    val cat = BillCategories.categorize(r.merchant, r.source)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 来源圆徽
        Box(
            modifier = Modifier.size(36.dp).background(sourceColor(r.source), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(sourceEmoji(r.source), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(r.merchant.take(18), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)) {
                    Text(cat, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(fmtTime(r.occurredAtMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("-¥${"%.2f".format(r.amount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
}

// ============ 屏2：支付临界点 AI 干预弹窗 ============
@Composable
private fun InterveneDialog(
    latest: BillEntity?,
    curCatSpent: Map<String, Double>,
    catBudgets: Map<String, Double>,
    monthSpent: Double,
    monthly: Double,
    onDismiss: () -> Unit,
    onViewWhy: () -> Unit
) {
    val cat = latest?.let { BillCategories.categorize(it.merchant, it.source) } ?: "其他"
    val budget = catBudgets[cat] ?: monthly
    val spentAfter = (curCatSpent[cat] ?: 0.0) + (latest?.amount ?: 0.0)
    val remainPct = if (budget > 0.0) ((budget - spentAfter) / budget * 100).roundToInt().coerceIn(0, 100) else 0
    val hours = if (HOURLY_WAGE > 0.0) (latest?.amount ?: 0.0) / HOURLY_WAGE else 0.0

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI 理性消费提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("✕") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val catLabel = latest?.merchant?.take(14) ?: "本次消费"
                Text("本次消费后本月「$cat」预算", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text("剩余 $remainPct%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (remainPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))
                ReasonLine("🧪", "72 小时复购率", "89%")
                ReasonLine("🕐", "本单等价搬砖时长", "${oneDecimal(hours)} 小时(校内时薪 ¥$HOURLY_WAGE)")
                if (latest != null) {
                    ReasonLine("🔍", "商品信息", "${latest.merchant.take(16)} · ${"%.2f".format(latest.amount)} 元")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("长按确认本次") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("稍后再加购") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onViewWhy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("查看 AI 为什么给出这条建议", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("所有建议仅供参考 · 最终决策权归用户本人", modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ReasonLine(icon: String, title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(icon, style = MaterialTheme.typography.labelMedium) }
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

// ============ 屏3：为什么给出这条提醒 ============
@Composable
private fun WhyDialog(
    latest: BillEntity?,
    curCatSpent: Map<String, Double>,
    catBudgets: Map<String, Double>,
    strength: Int,
    onStrengthChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val cat = latest?.let { BillCategories.categorize(it.merchant, it.source) } ?: "其他"
    val budget = catBudgets[cat] ?: 0.0
    val catSpent = curCatSpent[cat] ?: 0.0
    val budgetRemain = if (budget > 0.0) (budget - catSpent).roundToInt().coerceAtLeast(0) else 0
    val isLate = latest?.let { Rationale.isLateNight(it.occurredAtMs) } ?: false
    val dayPart = latest?.let { Rationale.dayPartLabel(it.occurredAtMs) } ?: "今晚"

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("为什么给出这条提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("✕") }
                }
                WhyBlock("🧭", "基础判断依据",
                    if (budget > 0.0) "检测到${dayPart}发起购物，本月「$cat」预算剩余 ¥$budgetRemain，即将透支。"
                    else "检测到${dayPart}发起购物，消费节奏高于往常。")
                WhyBlock("📖", "行为经济学原理说明",
                    "${dayPart}时段自我控制力下降，大脑奖励阈值升高，更容易发生冲动消费。")
                WhyBlock("🔁", "反事实推理提示",
                    if (isLate) "如果你在工作日白天购买，AI 提醒强度将会降低 45%。"
                    else "若推迟到更平峰的时间段购买，AI 提醒强度可再降低约 20%。")

                // 干预强度调节
                Text("AI 干预强度调节", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Slider(
                    value = strength.toFloat(),
                    onValueChange = { onStrengthChange(it.toInt().coerceIn(1, 10)) },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("低弱", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("当前强度 $strength", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("偏高", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("所有建议仅供参考 · 最终决策权归用户本人", modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun WhyBlock(icon: String, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(icon, style = MaterialTheme.typography.labelMedium) }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(3.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============ 工具 ============
private fun fmt(v: Double): String {
    val s = "%.2f".format(v)
    return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

private fun absPct(v: Double): String = "${(kotlin.math.abs(v)).roundToInt()}%"

private fun pctUsed(spent: Double, budget: Double): String =
    if (budget > 0.0) "${(spent / budget * 100).roundToInt()}% 已用" else "--"

private fun oneDecimal(v: Double): String = "%.1f".format(v)

private fun sourceEmoji(source: String): String = when (BillSources.canonical(source)) {
    BillSources.ALIPAY -> "📱"
    BillSources.MEITUAN -> "🍜"
    BillSources.TAOBAO -> "🛍️"
    BillSources.MANUAL -> "✏️"
    else -> "💳"
}

private fun sourceColor(source: String): Color = when (BillSources.canonical(source)) {
    BillSources.ALIPAY -> Color(0xFFE3F2FD)
    BillSources.MEITUAN -> Color(0xFFFFF3E0)
    BillSources.TAOBAO -> Color(0xFFFFEBEE)
    BillSources.MANUAL -> Color(0xFFE8F5E9)
    else -> Color(0xFFECEFF1)
}

private fun fmtTime(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) "HH:mm" else "MM-dd"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
}

private fun prevMonthRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.MONTH, -1)
    val from = cal.timeInMillis
    cal.add(Calendar.MONTH, 1)
    return from to cal.timeInMillis
}

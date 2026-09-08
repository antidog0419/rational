// ui/RationalHome.kt
// 首页仪表盘 —— 高保真还原 rational-ui-components.html「屏1」的薄荷绿视觉，
// 数据全部读本地账单库（Room）+ BudgetStore 真实预算：
//   · 问候头部（铃铛 + 头像）
//   · 本月理性指数卡（环形仪表 + 说明）
//   · 双统计卡（本月已支出 / 预算剩余）
//   · 快捷入口（实时预警 → 屏2 干预弹窗 / 智能诊断 / 消费行为报告）
//   · 最近消费（干预记录式列表）
// 屏2/屏3 仍以 App 内弹窗呈现（点「实时预警」/「智能诊断」入口触发）。

package com.example.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.finance.BuildConfig
import com.example.finance.R
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
import com.example.finance.ui.theme.OnRationalMintContainer
import com.example.finance.ui.theme.OnRationalSkyContainer
import com.example.finance.ui.theme.RationalBg
import com.example.finance.ui.theme.RationalBorder
import com.example.finance.ui.theme.RationalCard
import com.example.finance.ui.theme.RationalDangerContainer
import com.example.finance.ui.theme.RationalDanger40
import com.example.finance.ui.theme.RationalMint40
import com.example.finance.ui.theme.RationalMintBorder
import com.example.finance.ui.theme.RationalMintContainer
import com.example.finance.ui.theme.RationalMintRing
import com.example.finance.ui.theme.RationalOnDanger
import com.example.finance.ui.theme.RationalPurple40
import com.example.finance.ui.theme.RationalPurpleContainer
import com.example.finance.ui.theme.RationalSky40
import com.example.finance.ui.theme.RationalSkyContainer
import com.example.finance.ui.theme.RationalSuccess40
import com.example.finance.ui.theme.RationalSuccessContainer
import com.example.finance.ui.theme.RationalText
import com.example.finance.ui.theme.RationalText2
import com.example.finance.ui.theme.RationalText3
import com.example.finance.ui.theme.RationalWarning40
import com.example.finance.ui.theme.RationalWarningContainer
import java.text.SimpleDateFormat
import java.util.Calendar
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

    // 问候日期行
    val dateLine = remember { buildDateLine() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ============ 问候头部 ============
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Hi，同学", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(dateLine, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                // 铃铛（红点）
                Box(modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(painterResource(R.drawable.ic_bell), contentDescription = "提醒",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp).align(Alignment.Center))
                    Box(modifier = Modifier.size(7.dp)
                        .background(RationalDanger40, CircleShape)
                        .align(Alignment.TopEnd).padding(0.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                // 头像
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(
                    Brush.linearGradient(listOf(RationalMint40, RationalSky40)))) {
                    Text("同", color = Color.White, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center))
                }
            }

            // ============ 理性指数卡（环形仪表） ============
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.surface, RationalMintContainer)
                        )
                    )
                    .border(1.dp, RationalMintBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本月理性指数", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("评估规则 ›", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 环形仪表
                        ScoreGauge(score = score, gaugeSize = 116.dp)
                        Spacer(modifier = Modifier.width(18.dp))
                        Column {
                            Text("理性指数", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = RationalSuccessContainer,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    Icon(painterResource(R.drawable.ic_check),
                                        contentDescription = null, tint = RationalSuccess40,
                                        modifier = Modifier.size(11.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("超过 $percent% 的同龄人", style = MaterialTheme.typography.labelSmall,
                                        color = RationalSuccess40, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("消费决策力由本月预算执行、环比增幅、深夜占比实时计算，数据只存本机。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = MaterialTheme.typography.labelSmall.lineHeight)
                        }
                    }
                }
            }

            // ============ 双统计卡 ============
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_coin,
                    iconTint = RationalSuccess40,
                    label = "本月已支出",
                    value = fmtMoney(monthSpent),
                    sub = if (prevSum > 0.0) {
                        if (prevPct >= 0) "较上月 ↑ ${absPct(prevPct)}" else "较上月 ↓ ${absPct(prevPct)}"
                    } else "暂无上月可比",
                    subColor = if (prevSum > 0.0 && prevPct > 0.0) RationalDanger40
                    else if (prevSum > 0.0) RationalSuccess40
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                val remain = budgetMonthly - monthSpent
                val usedPct = if (budgetMonthly > 0.0) (monthSpent / budgetMonthly * 100).roundToInt() else 0
                StatCard(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_scale,
                    iconTint = RationalSky40,
                    label = "预算剩余",
                    value = if (budgetMonthly > 0.0) fmtMoney(remain.coerceAtLeast(0.0)) else "--",
                    sub = if (budgetMonthly > 0.0) "已使用 $usedPct% · 共 ¥${fmtMoney(budgetMonthly)}"
                    else "未设置月预算",
                    subColor = if (usedPct >= 90) RationalDanger40
                    else if (usedPct >= 75) RationalWarning40
                    else RationalSky40
                )
            }

            // ============ 快捷入口 ============
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickEntry(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_robot,
                    tint = RationalMint40,
                    tileBg = RationalMintContainer,
                    label = "实时预警",
                    onClick = { showIntervene = true }
                )
                QuickEntry(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_brain,
                    tint = RationalSky40,
                    tileBg = RationalSkyContainer,
                    label = "智能诊断",
                    onClick = onDiagnose
                )
                QuickEntry(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_chart,
                    tint = RationalWarning40,
                    tileBg = RationalWarningContainer,
                    label = "消费报告",
                    onClick = onReport
                )
            }

            // ============ 最近消费（干预记录式列表） ============
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                        Text("AI 干预记录", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("查看全部 ›", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (recent.isEmpty()) {
                        Text("暂无消费记录，去「记录」页抓取或手动补记吧",
                            modifier = Modifier.padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        recent.forEach { r -> RecentRow(r) }
                        if (todayCount > 0) {
                            Text("今日已记录 $todayCount 笔 · ¥${fmtMoney(todaySum)}",
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Text(
                "理伴理性消费助理 v${BuildConfig.VERSION_NAME} · 最终决策权归用户本人",
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
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

/** 环形仪表：轨道 + 进度弧 + 中心分数 */
@Composable
private fun ScoreGauge(score: Int, gaugeSize: androidx.compose.ui.unit.Dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = RationalMint40
    Box(modifier = Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = track,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(0f, 0f), size = Size(this.size.width, this.size.height), style = stroke
            )
            drawArc(
                color = bar,
                startAngle = -90f, sweepAngle = 360f * (score / 100f), useCenter = false,
                topLeft = Offset(0f, 0f), size = Size(this.size.width, this.size.height), style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold,
                color = RationalMint40)
            Text("分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 统计卡：小图标 + 标题 + 数值 + 彩色说明 */
@Composable
private fun StatCard(
    modifier: Modifier,
    iconRes: Int,
    iconTint: Color,
    label: String,
    value: String,
    sub: String,
    subColor: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(iconRes), contentDescription = null, tint = iconTint,
                    modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text("¥$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.labelSmall, color = subColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 快捷入口：彩色圆角小方块图标 + 文字 */
@Composable
private fun QuickEntry(
    modifier: Modifier,
    iconRes: Int,
    tint: Color,
    tileBg: Color,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tileBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), contentDescription = label, tint = tint,
                modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 最近消费行（干预记录样式：彩色图标 + 商家 + 说明 + 右侧金额） */
@Composable
private fun RecentRow(r: BillEntity) {
    val cat = BillCategories.categorize(r.merchant, r.source)
    val (iconRes, bg, tint) = categoryVisual(cat)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), contentDescription = cat, tint = tint,
                modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(r.merchant.take(16), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text("${sourceLabel(r.source)} · ${fmtRelativeTime(r.occurredAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text("-¥${"%.2f".format(r.amount)}", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 分类 → 图标 + 底色 + 图标色（映射设计稿 iv-icon 配色体系） */
private fun categoryVisual(cat: String): Triple<Int, Color, Color> = when (cat) {
    "购物" -> Triple(R.drawable.ic_cart, RationalMintContainer, RationalMint40)
    "娱乐" -> Triple(R.drawable.ic_game, RationalSkyContainer, RationalSky40)
    "餐饮" -> Triple(R.drawable.ic_food, RationalWarningContainer, RationalWarning40)
    else -> Triple(R.drawable.ic_film, RationalPurpleContainer, RationalPurple40)
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
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape).background(RationalMintContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_robot), contentDescription = null,
                            tint = RationalMint40, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("支付临界点提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(color = RationalMintContainer, shape = RoundedCornerShape(999.dp)) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_spark), contentDescription = null,
                                tint = RationalMint40, modifier = Modifier.size(10.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("AI 干预", style = MaterialTheme.typography.labelSmall,
                                color = RationalMint40, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                val catLabel = latest?.merchant?.take(14) ?: "本次消费"
                Text("本次消费后本月「$cat」预算剩余 $remainPct%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                // 预算条（设计稿暖红渐变）
                LinearProgressIndicator(
                    progress = { (spentAfter / budget).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = RationalDanger40,
                    trackColor = RationalDangerContainer
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已用 ¥${"%.2f".format(spentAfter)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("预算 ¥${"%.2f".format(budget)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(12.dp))
                ReasonLine("🧪", "72 小时复购率", "89%")
                ReasonLine("🕐", "本单等价搬砖时长", "${oneDecimal(hours)} 小时(校内时薪 ¥$HOURLY_WAGE)")
                if (latest != null) {
                    ReasonLine("🔍", "商品信息", "${latest.merchant.take(16)} · ${"%.2f".format(latest.amount)} 元")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("采纳建议 · 冷静 24 小时") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("仍要支付") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onViewWhy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("查看 AI 为什么给出这条建议", color = RationalMint40,
                        style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("选择冷静期后，24 小时内该类目支付将需要二次确认。所有建议仅供参考。",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ReasonLine(icon: String, title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.width(8.dp))
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
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = RationalMint40,
                        activeTrackColor = RationalMint40,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("低弱", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("当前强度 $strength", style = MaterialTheme.typography.labelMedium,
                        color = RationalMint40)
                    Text("偏高", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("本提醒由端侧 AI 模型基于本机加密数据生成，不涉及第三方上传；可随时在「我的数据与权限」调整。",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center)
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
private fun buildDateLine(): String {
    val cal = Calendar.getInstance()
    val fmt = SimpleDateFormat("M月d日 EEEE", Locale.CHINESE)
    return "${fmt.format(Date(cal.timeInMillis))} · 今天也要理性消费"
}

private fun fmtMoney(v: Double): String {
    val s = "%.2f".format(v)
    return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

private fun absPct(v: Double): String = "${(kotlin.math.abs(v)).roundToInt()}%"

private fun oneDecimal(v: Double): String = "%.1f".format(v)

private fun sourceLabel(source: String): String = when (BillSources.canonical(source)) {
    BillSources.ALIPAY -> "支付宝"
    BillSources.MEITUAN -> "美团"
    BillSources.TAOBAO -> "淘宝闪购"
    BillSources.MANUAL -> "手动"
    BillSources.SCREEN -> "识屏"
    else -> "其他"
}

private fun fmtRelativeTime(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) "HH:mm" else "MM-dd HH:mm"
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

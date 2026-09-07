// ui/BillCharts.kt
// #5 报表可视化：近6月支出折线 + 本月分类占比环图（纯 Canvas，不引第三方图表库）。
// 聚合辅助函数为纯函数（输入 Room 全量行），便于单测/复用。

package com.example.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finance.data.BillCategories
import com.example.finance.data.BillEntity
import java.util.Calendar

data class TrendPoint(val label: String, val sum: Double)

private val palette = listOf(
    Color(0xFF0B6B9E), Color(0xFF2A9D8F), Color(0xFFF4A261), Color(0xFFE76F51),
    Color(0xFF9C89B8), Color(0xFF70A1FF), Color(0xFFB0BEC5),
)

/** 近 6 个月（含当月）的 (M月, 支出) 升序序列 */
fun computeTrend(all: List<BillEntity>): List<TrendPoint> {
    val cal = Calendar.getInstance()
    val buckets = mutableListOf<String>()
    val cal2 = Calendar.getInstance()
    cal2.set(Calendar.DAY_OF_MONTH, 1)
    repeat(6) {
        val y = cal2.get(Calendar.YEAR)
        val m = cal2.get(Calendar.MONTH) + 1
        buckets.add(0, "%04d-%02d".format(y, m))
        cal2.add(Calendar.MONTH, -1)
    }
    val sums = buckets.associateWith { 0.0 }.toMutableMap()
    all.forEach { b ->
        val key = b.dayBucket.take(7)
        if (key in sums) sums[key] = sums[key]!! + b.amount
    }
    return buckets.map { key ->
        TrendPoint(key.substring(5).toInt().toString() + "月", sums[key] ?: 0.0)
    }
}

/** 本月各分类 (分类, 金额)，按金额降序 */
fun computeCatShare(monthRows: List<BillEntity>): List<Pair<String, Double>> =
    monthRows.groupBy { BillCategories.categorize(it.merchant, it.source) }
        .map { (cat, rows) -> cat to rows.sumOf { it.amount } }
        .sortedByDescending { it.second }

@Composable
fun TrendChartCard(modifier: Modifier = Modifier, points: List<TrendPoint>) {
    val peak = points.maxOfOrNull { it.sum } ?: 0.0
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📈 近 6 月支出趋势", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.weight(1f))
                Text(if (peak > 0) "峰值 ¥${"%.0f".format(peak)}" else "暂无数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(6.dp))
            val lineColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                val n = points.size
                val maxV = (points.maxOfOrNull { it.sum } ?: 0.0).coerceAtLeast(1.0) * 1.15
                val w = size.width
                val h = size.height
                val pad = 4.dp.toPx()
                val stepX = if (n > 1) (w - pad * 2) / (n - 1) else 0f
                fun yOf(v: Double): Float = (h - pad - (v / maxV * (h - pad * 2))).toFloat()
                // 网格：三条淡线
                listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                    drawLine(Color.Black.copy(alpha = 0.06f), Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f)
                }
                // 折线
                for (i in 1 until n) {
                    drawLine(
                        color = lineColor.copy(alpha = 0.75f),
                        start = Offset(pad + (i - 1) * stepX, yOf(points[i - 1].sum)),
                        end = Offset(pad + i * stepX, yOf(points[i].sum)),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                // 数据点
                points.forEachIndexed { i, p ->
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = Offset(pad + i * stepX, yOf(p.sum)),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                points.forEach { p ->
                    Text("${p.label}\n${if (p.sum > 0) "¥" + "%.0f".format(p.sum) else "—"}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun CatShareCard(modifier: Modifier = Modifier, share: List<Pair<String, Double>>, monthTotal: Double) {
    val total = monthTotal.coerceAtLeast(0.0)
    Card(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        var start = -90f
                        val usable = share.ifEmpty { listOf("无记录" to 0.0) }
                        usable.forEachIndexed { i, (_, v) ->
                            val frac = if (total > 0) (v / total).toFloat() else 0f
                            drawArc(
                                color = if (total > 0) palette[i % palette.size] else Color(0xFFE0E0E0),
                                startAngle = start,
                                sweepAngle = if (total > 0) frac * 360f else 360f,
                                useCenter = true,
                                topLeft = Offset(0f, 0f),
                                size = Size(size.width, size.height),
                            )
                            start += frac * 360f
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("¥${"%.0f".format(total)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("本月", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🍩 本月分类占比", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                if (share.isEmpty()) {
                    Text("本月暂无分类数据", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    share.forEachIndexed { i, (cat, v) ->
                        val pct = if (total > 0) (v / total * 100).toInt() else 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(palette[i % palette.size],
                                androidx.compose.foundation.shape.CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(cat, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("¥${"%.0f".format(v)} · $pct%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

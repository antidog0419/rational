// ui/BillsCalendar.kt
// 「记录」页日历视图：一个月一张网格，每天格子里显示当天花费；点选某天可在下方明细区只看当天。
// 与明细/筛选同一份数据口径（dayTotals 由调用方按月+来源过滤后传入）。

package com.example.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finance.data.BillEntity
import java.util.Calendar
import java.util.Locale

@Composable
fun CalendarCard(
    monthSel: Int,
    dayTotals: Map<String, Double>,
    selectedDay: String?,
    onSelectDay: (String) -> Unit,
) {
    val year = monthSel / 100
    val month = monthSel % 100
    val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 周一为行首：Calendar.SUNDAY=1 … SATURDAY=7 → (dow+5)%7 → 周一=0
    val lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val todayBucket = BillEntity.dayBucket(System.currentTimeMillis())
    val prefix = "%04d-%02d".format(Locale.US, year, month)

    val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📅 每日花费", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.weight(1f))
                Text(if (selectedDay == null) "点日期看当天明细" else "${dayNumber(selectedDay!!)} 号明细已过滤",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayNames.forEachIndexed { i, name ->
                    Text(name,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i >= 5) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 日期网格
            val cells = lead + daysInMonth
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val day = r * 7 + c - lead + 1
                        val bucket = if (day in 1..daysInMonth) "$prefix-%02d".format(Locale.US, day) else null
                        Box(
                            modifier = Modifier.weight(1f).padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bucket == null) {
                                Spacer(modifier = Modifier.height(46.dp))
                            } else {
                                DayCell(
                                    day = day,
                                    total = dayTotals[bucket] ?: 0.0,
                                    isSelected = bucket == selectedDay,
                                    isToday = bucket == todayBucket,
                                    onClick = { onSelectDay(bucket) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, total: Double, isSelected: Boolean, isToday: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary
        total > 0.0 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(bg, shape)
            .then(if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("$day",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface)
        if (total > 0.0) {
            Text(compactAmount(total),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                maxLines = 1,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                else MaterialTheme.colorScheme.primary)
        } else {
            Spacer(modifier = Modifier.height(9.dp))
        }
    }
}

/** 金额紧凑显示：<1000 保留 1 位小数（去尾零），≥1000 取整 */
private fun compactAmount(v: Double): String {
    val text = if (v >= 1000.0) {
        "%.0f".format(v)
    } else {
        "%.1f".format(v).trimEnd('0').trimEnd('.')
    }
    return if (text.length > 5) "%.0f".format(v) else text
}

private fun dayNumber(bucket: String): String = bucket.substringAfterLast('-').removePrefix("0")

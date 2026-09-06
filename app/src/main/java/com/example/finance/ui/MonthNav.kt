// ui/MonthNav.kt
// 月份切换的共享工具：编码(yyyyMM int)、加减月、范围区间、日平均计算，
// 以及首页/账单页复用的"月份切换行"（‹ 2026年9月 › + 回到本月）。
// 不用 java.time（minSdk 24 无脱糖），全部走 Calendar。

package com.example.finance.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** 月份编码：yyyyMM（如 2026 年 9 月 = 202609） */
internal fun currentMonthEncoded(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1)
}

internal fun monthParts(enc: Int): Pair<Int, Int> = (enc / 100) to (enc % 100)

internal fun monthLabel(enc: Int): String {
    val (y, m) = monthParts(enc)
    return "${y}年${m}月"
}

/** 加减 delta 个月，自动跨年进位/借位 */
internal fun monthAdd(enc: Int, delta: Int): Int {
    val (y, m) = monthParts(enc)
    val idx = y * 12 + (m - 1) + delta
    val ny = Math.floorDiv(idx, 12)
    val nm = Math.floorMod(idx, 12) + 1
    return ny * 100 + nm
}

internal fun isCurrentMonth(enc: Int): Boolean = enc == currentMonthEncoded()

/** 某月本地起止毫秒（当月 1 日 00:00 ~ 次月 1 日 00:00） */
internal fun monthRangeOfEnc(enc: Int): Pair<Long, Long> {
    val (y, m) = monthParts(enc)
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(y, m - 1, 1, 0, 0, 0)
    val from = cal.timeInMillis
    cal.add(Calendar.MONTH, 1)
    return from to cal.timeInMillis
}

/** 某月 "yyyy-MM" 前缀，用于按 dayBucket 过滤 */
internal fun monthDayPrefix(enc: Int): String {
    val (y, m) = monthParts(enc)
    return "%04d-%02d".format(y, m)
}

/** 用于"日均"：本月取今天（含），历史月取整月天数 */
internal fun monthElapsedDays(enc: Int): Int {
    val (y, m) = monthParts(enc)
    val cal = Calendar.getInstance()
    if (isCurrentMonth(enc)) return cal.get(Calendar.DAY_OF_MONTH)
    cal.clear()
    cal.set(y, m - 1, 1)
    return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
}

/**
 * 月份切换行：‹ 上个月 › 当前月 ‹ 下个月 ›（含"回到本月"）。
 * 放在 Row 内会自动撑满宽度。
 */
@Composable
internal fun MonthSwitcherRow(enc: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onChange(monthAdd(enc, -1)) }) {
            Text("‹", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = monthLabel(enc),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = { onChange(monthAdd(enc, 1)) }) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
        if (!isCurrentMonth(enc)) {
            TextButton(onClick = { onChange(currentMonthEncoded()) }) {
                Text("回到本月", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            // 占位，让"回到本月"出现/消失时不明显跳动
            Text("当前月",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

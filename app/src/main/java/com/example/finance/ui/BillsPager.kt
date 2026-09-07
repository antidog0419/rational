// ui/BillsPager.kt
// 记录页"浏览卡"：日历 / 支出趋势 / 分类占比 → 左右滑动平滑切换（点击分段标题也可跳转），默认日历页。
// 固定内容高度(按日历最高排版预留)，短页内容垂直居中，避免滑动时高度跳动。

package com.example.finance.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val PAGE_HEIGHT = 420.dp

@Composable
fun RecordPager(
    monthSel: Int,
    dayTotals: Map<String, Double>,
    selectedDay: String?,
    onSelectDay: (String) -> Unit,
    onShowAll: () -> Unit,
    trendPoints: List<TrendPoint>,
    catShare: List<Pair<String, Double>>,
    monthTotal: Double,
) {
    val pagerState = rememberPagerState(initialPage = 0) { 2 } // 0=日历(默认)，1=趋势+分类
    val scope = rememberCoroutineScope()
    val pages = listOf("📅 日历", "📈 趋势 + 分类")

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 分段标题（点击跳页）；有选中日时提供"返回整月"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            pages.forEachIndexed { index, label ->
                FilterChip(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (selectedDay != null) {
                TextButton(onClick = onShowAll) { Text("返回整月") }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(PAGE_HEIGHT),
            key = { it },
        ) { page ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (page) {
                    0 -> CalendarCard(monthSel, dayTotals, selectedDay, onSelectDay)
                    // 1: 趋势与分类上下各半
                    else -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TrendChartCard(modifier = Modifier.weight(1f), points = trendPoints)
                        CatShareCard(modifier = Modifier.weight(1f), share = catShare, monthTotal = monthTotal)
                    }
                }
            }
        }
    }
}

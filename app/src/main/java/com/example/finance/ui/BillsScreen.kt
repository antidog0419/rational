// ui/BillsScreen.kt
// 「账单」Tab —— 本地账单库（Room）驱动的完整记账本：
//   · 本月统计卡（按来源）
//   · 来源筛选（全部 / 支付宝 / 美团 / 淘宝闪购 / 手动 / 其他）
//   · 手动补记 / 单笔编辑 / 单笔删除（直接读写 Room，首页统计自动一致）
//   · 按日分组明细（含当日合计）+ 去重规则提示 + 系统日志
// 所有展示均来自数据库 Flow：与「首页」今日支出、月度统计共用同一份数据，杜绝双源不一致。

package com.example.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.data.BillDao
import com.example.finance.data.BillEntity
import com.example.finance.data.BillSources
import com.example.finance.data.FinanceDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 单日分组（dayBucket=yyyy-MM-dd） */
private data class DayGroup(val dayBucket: String, val rows: MutableList<BillEntity>)

/** 按出现顺序把（已按时间倒序的）全量账单分组为"天" */
private fun groupByDay(all: List<BillEntity>): List<DayGroup> {
    val out = ArrayList<DayGroup>()
    val index = HashMap<String, Int>()
    for (b in all) {
        val gi = index[b.dayBucket]
        if (gi == null) {
            index[b.dayBucket] = out.size
            out.add(DayGroup(b.dayBucket, mutableListOf(b)))
        } else {
            out[gi].rows.add(b)
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsTabColumn(
    padding: PaddingValues,
    logs: SnapshotStateList<String>,
    a11yEnabled: Boolean,
    onFetchAlipay: () -> Unit,
    onFetchMeituan: () -> Unit,
    onFetchTaobao: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { FinanceDb.get(context).billDao() }
    val scope = rememberCoroutineScope()

    // 全量明细 + 所选月份统计（均来自 Room Flow，抓取/补记/删除/切换月份后自动刷新）
    val all by dao.observeAll().collectAsState(initial = emptyList())
    var monthSel by rememberSaveable { mutableIntStateOf(currentMonthEncoded()) }
    val (mFrom, mTo) = remember(monthSel) { monthRangeOfEnc(monthSel) }
    val monthStats by remember(monthSel) { dao.statsBetween(mFrom, mTo) }
        .collectAsState(initial = emptyList())
    val monthTotal = monthStats.sumOf { it.total }
    val monthCount = monthStats.sumOf { it.cnt }
    val monthTag = monthLabel(monthSel)

    // 来源筛选（在所选月份内过滤）
    var filter by rememberSaveable { mutableStateOf(BillSources.ALL) }
    val dayPrefix = remember(monthSel) { monthDayPrefix(monthSel) }
    val filtered = remember(all, filter, monthSel) {
        val inMonth = all.filter { it.dayBucket.startsWith(dayPrefix) }
        if (filter == BillSources.ALL) inMonth
        else inMonth.filter { BillSources.canonical(it.source) == filter }
    }
    val dayGroups = remember(filtered) { groupByDay(filtered) }

    // 弹窗状态
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BillEntity?>(null) }
    var deleting by remember { mutableStateOf<BillEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------- 月份切换 ----------
        item(key = "month-nav") {
            Card(modifier = Modifier.fillMaxWidth()) {
                MonthSwitcherRow(monthSel) { monthSel = it }
            }
        }

        // ---------- 所选月份统计 ----------
        item(key = "month-stats") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📅 $monthTag 统计",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("共 $monthCount 笔",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("支出合计 ¥${"%.2f".format(monthTotal)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (monthStats.isEmpty()) {
                        Text(if (isCurrentMonth(monthSel))
                            "本月暂无记录。点下方「手动补记」或抓取平台账单开始记账。"
                        else
                            "该月暂无账单记录。点 ‹ › 切换月份，或「手动补记」一笔这个月的账单。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        monthStats.forEach { s ->
                            Text("${BillSources.canonical(s.source)}：¥${"%.2f".format(s.total)}（${s.cnt} 笔）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }

        // ---------- 来源筛选 + 手动补记 ----------
        item(key = "tools") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val options = listOf(BillSources.ALL) + BillSources.PRESETS
                    options.forEach { s ->
                        FilterChip(
                            selected = filter == s,
                            onClick = { filter = s },
                            label = { Text(s) }
                        )
                    }
                }
                Button(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("＋ 手动补记一笔") }
                Text("规则：同一天·同商家·同金额自动只保留一笔（防滚动重复）；" +
                        "抓取的历史账单默认记为抓取当天，可在明细中点「编辑」改时间。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- 自动抓取 ----------
        item(key = "fetch") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("自动获取账单（需无障碍已开启）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Button(
                        enabled = a11yEnabled,
                        onClick = onFetchAlipay,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📋 获取「支付宝」账单（翻页全自动）") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = a11yEnabled,
                            onClick = onFetchMeituan,
                            modifier = Modifier.weight(1f)
                        ) { Text("🍜 获取美团账单") }
                        OutlinedButton(
                            enabled = a11yEnabled,
                            onClick = onFetchTaobao,
                            modifier = Modifier.weight(1f)
                        ) { Text("🛒 获取淘宝闪购账单") }
                    }
                    Text("抓取结果会直接进入下方明细与本月统计；商家自动进入本地商家库供首页 Top3 推荐。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------- 明细列表 ----------
        item(key = "list-title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$monthTag 明细 · ${filtered.size} 笔",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text(if (filter == BillSources.ALL) "全部来源" else "筛选：$filter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📭 $monthTag 暂无账单", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(if (all.isEmpty())
                            "去支付宝/美团消费会自动实时入账；也可「手动补记」或抓取历史账单。"
                        else
                            "该月没有符合条件的账单：点 ‹ › 切换月份，或「手动补记」一笔。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        for (group in dayGroups) {
            item(key = "day-${group.dayBucket}") {
                val daySum = group.rows.sumOf { it.amount }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(dayTitle(group.dayBucket),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Text("${group.rows.size} 笔 · ¥${"%.2f".format(daySum)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(group.rows, key = { "bill-${it.id}" }) { bill ->
                BillRow(
                    bill = bill,
                    onEdit = { editing = bill },
                    onDelete = { deleting = bill }
                )
            }
        }

        // ---------- 系统日志 ----------
        item(key = "logs-title") {
            Text("系统日志",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
        item(key = "logs") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("暂无日志", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logs.take(40).forEach { line ->
                        Text(line,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }

    // ---------- 弹窗 ----------
    if (showAdd) {
        BillEditDialog(
            initial = null,
            dao = dao,
            logs = logs,
            onDismiss = { showAdd = false }
        )
    }
    editing?.let { bill ->
        BillEditDialog(
            initial = bill,
            dao = dao,
            logs = logs,
            onDismiss = { editing = null }
        )
    }
    deleting?.let { bill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这笔账单？") },
            text = {
                Text("「${bill.merchant}」 ¥${"%.2f".format(bill.amount)}（${BillSources.canonical(bill.source)} · ${bill.dayBucket}）\n删除后不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { dao.deleteById(bill.id) }
                        }.onSuccess {
                            logs.add(0, "🗑 已删除：${bill.merchant} ¥${"%.2f".format(bill.amount)}")
                        }.onFailure {
                            logs.add(0, "❌ 删除失败：${it.message}")
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

// ============ 单笔账单行 ============

@Composable
private fun BillRow(
    bill: BillEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val source = BillSources.canonical(bill.source)
    val dot = when (source) {
        BillSources.ALIPAY -> MaterialTheme.colorScheme.primary
        BillSources.MEITUAN -> MaterialTheme.colorScheme.tertiary
        BillSources.TAOBAO -> MaterialTheme.colorScheme.error
        BillSources.MANUAL -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(bill.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1)
                Text("$source · ${timeOf(bill.occurredAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (bill.note.isNotBlank()) {
                    Text("备注：${bill.note}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("-¥${"%.2f".format(bill.amount)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Column(horizontalAlignment = Alignment.End) {
                Text("编辑",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onEdit)
                        .padding(horizontal = 8.dp, vertical = 2.dp))
                Text("删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete)
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

// ============ 新增 / 编辑弹窗 ============

@Composable
private fun BillEditDialog(
    initial: BillEntity?,   // null=新增；否则编辑
    dao: BillDao,
    logs: SnapshotStateList<String>,
    onDismiss: () -> Unit
) {
    val isNew = initial == null
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }

    var merchant by remember { mutableStateOf(initial?.merchant ?: "") }
    var amount by remember {
        mutableStateOf(initial?.amount?.let { "%.2f".format(it) } ?: "")
    }
    var date by remember { mutableStateOf(dateOf(initial?.occurredAtMs ?: now)) }
    var time by remember { mutableStateOf(timeOf(initial?.occurredAtMs ?: now)) }
    var source by remember { mutableStateOf(BillSources.canonical(initial?.source) ?: BillSources.MANUAL) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf("") }

    // 可选的来源：新增=5 个规范来源；编辑=在规范基础上保留当前自定义来源
    val sourceOptions = remember(initial?.source) {
        if (initial != null && BillSources.canonical(initial.source) !in BillSources.PRESETS) {
            listOf(BillSources.canonical(initial.source)) + BillSources.PRESETS
        } else BillSources.PRESETS
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "手动补记" else "编辑账单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商家 / 说明 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("日期 yyyy-MM-dd") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = time,
                        onValueChange = { time = it },
                        label = { Text("时间 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("来源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sourceOptions.forEach { s ->
                        FilterChip(
                            selected = source == s,
                            onClick = { source = s },
                            label = { Text(s) }
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Text(error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amtRaw = amount.replace(Regex("[¥￥,， ]"), "")
                val amt = amtRaw.toDoubleOrNull()
                val mName = merchant.trim()
                val ms = parseLocalDateTime(date, time)
                when {
                    mName.isEmpty() -> error = "请填写商家 / 说明"
                    amt == null || amt <= 0.0 -> error = "金额需为大于 0 的数字"
                    amt > 999_999.0 -> error = "金额过大"
                    ms == null -> error = "日期/时间格式不对，例如 2024-07-15 / 09:05"
                    else -> {
                        scope.launch {
                            val entity = BillEntity(
                                id = initial?.id ?: 0L,
                                source = source,
                                merchant = mName,
                                amount = amt,
                                occurredAtMs = ms,
                                dayBucket = BillEntity.dayBucket(ms)
                            ).withDerived(source = source, merchant = mName, amount = amt, occurredAtMs = ms, note = note)
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    if (isNew) dao.insert(entity) else { dao.update(entity); 1L }
                                }
                            }
                            result.onSuccess { inserted ->
                                if (isNew && inserted == -1L) {
                                    logs.add(0, "⚠️ 未新增：同一天·同商家·同金额的账单已存在（如需修改请编辑原记录）")
                                } else {
                                    logs.add(0, if (isNew)
                                        "✅ 已补记：$mName ¥${"%.2f".format(amt)}（$source · $date $time）"
                                    else
                                        "✏️ 已更新：$mName ¥${"%.2f".format(amt)}（$source · $date $time）")
                                }
                                onDismiss()
                            }.onFailure {
                                logs.add(0, "❌ 保存失败：${it.message}")
                                error = "保存失败，请检查是否与已有记录冲突"
                            }
                        }
                    }
                }
            }) { Text(if (isNew) "保存" else "保存修改") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ============ 格式化工具 ============

private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val weekNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

private fun dateOf(ms: Long): String = dateFmt.format(java.util.Date(ms))
private fun timeOf(ms: Long): String = timeFmt.format(java.util.Date(ms))

/** 解析本地 "yyyy-MM-dd HH:mm" → 毫秒；不合法返回 null */
private fun parseLocalDateTime(dateStr: String, timeStr: String): Long? {
    val dm = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(dateStr.trim()) ?: return null
    val tm = Regex("""^(\d{1,2}):(\d{2})$""").find(timeStr.trim()) ?: return null
    val y = dm.groupValues[1].toIntOrNull() ?: return null
    val mo = dm.groupValues[2].toIntOrNull() ?: return null
    val d = dm.groupValues[3].toIntOrNull() ?: return null
    val h = tm.groupValues[1].toIntOrNull() ?: return null
    val mi = tm.groupValues[2].toIntOrNull() ?: return null
    if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(y, mo - 1, d, h, mi, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** 天标题：今天 / 昨天 / 7月18日 周五（跨年带年份） */
private fun dayTitle(dayBucket: String): String {
    val seg = dayBucket.split("-")
    if (seg.size != 3) return dayBucket
    val y = seg[0].toIntOrNull() ?: return dayBucket
    val mo = seg[1].toIntOrNull() ?: return dayBucket
    val d = seg[2].toIntOrNull() ?: return dayBucket

    val today = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        clear()
        set(y, mo - 1, d)
    }
    val sameDay = { a: Calendar, b: Calendar ->
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
    val weekday = weekNames[target.get(Calendar.DAY_OF_WEEK) - 1]
    val dateText = if (y == today.get(Calendar.YEAR)) "${mo}月${d}日" else "${y}年${mo}月${d}日"
    return when {
        sameDay(today, target) -> "今天 · $dateText"
        sameDay(today.apply { add(Calendar.DAY_OF_YEAR, -1) }, target) -> "昨天 · $dateText"
        else -> "$dateText $weekday"
    }
}

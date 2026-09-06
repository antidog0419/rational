// data/WeeklyReportBuilder.kt
// 每周小结的本地聚合：近 7 天 vs 前 7 天的支出对比 + 分类/来源/高频去向/最大单笔，
// 并带上当前预算上下文（BudgetPlanner），供 AI 生成周报（云端失败时端侧也能拼出统计摘要）。

package com.example.finance.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 喂给 AI 的周报输入 */
data class WeeklyReportInput(
    val fromLabel: String,
    val toLabel: String,
    val spent: Double,
    val count: Int,
    val prevSpent: Double,
    val prevCount: Int,
    val catLines: List<String>,       // "餐饮 ¥350.2（占45.2%）"
    val sourceLines: List<String>,    // "支付宝 ¥620（N笔）"
    val topMerchants: List<String>,   // "永兴饭店 ¥88.5"
    val biggestLine: String,          // "永兴饭店 ¥88.5（最大单笔）"
    val budgetContext: String         // 本月预算上下文（BudgetPlanner）
)

object WeeklyReportBuilder {

    private const val DAY_MS = 86_400_000L
    private val dateFmt = SimpleDateFormat("M月d日", Locale.getDefault())

    private fun fmt(v: Double): String {
        val s = "%.2f".format(v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /** 近 7 天（含今天）对比前 7 天。需 IO 线程调用。 */
    suspend fun build(context: Context): WeeklyReportInput {
        val appCtx = context.applicationContext
        val dao = FinanceDb.get(appCtx).billDao()
        val now = System.currentTimeMillis()
        val prevFrom = now - 14 * DAY_MS
        val prevTo = now - 7 * DAY_MS
        val curFrom = now - 7 * DAY_MS

        val curSpent = runCatching { dao.sumBetweenOnce(curFrom, now) }.getOrDefault(0.0)
        val prevSpent = runCatching { dao.sumBetweenOnce(prevFrom, prevTo) }.getOrDefault(0.0)
        val curRows = runCatching { dao.merchantAmountsBetween(curFrom, now) }.getOrDefault(emptyList())
        val prevRows = runCatching { dao.merchantAmountsBetween(prevFrom, prevTo) }.getOrDefault(emptyList())

        // 分类 + 来源 + 商家 聚合
        val catSum = HashMap<String, Double>()
        val srcSum = HashMap<String, Double>()
        val merSum = HashMap<String, Double>()
        var biggestMerchant = "—"
        var biggestAmount = 0.0
        for (r in curRows) {
            val c = BillCategories.categorize(r.merchant, r.source)
            catSum[c] = (catSum[c] ?: 0.0) + r.amount
            srcSum[r.source] = (srcSum[r.source] ?: 0.0) + r.amount
            merSum[r.merchant] = (merSum[r.merchant] ?: 0.0) + r.amount
            if (r.amount > biggestAmount) {
                biggestAmount = r.amount
                biggestMerchant = r.merchant
            }
        }

        val catLines = catSum.entries
            .sortedByDescending { it.value }
            .map { (c, v) ->
                val pct = if (curSpent > 0) v / curSpent * 100 else 0.0
                "$c ¥${fmt(v)}（占${"%.1f".format(pct)}%）"
            }
        val realSourceLines = srcSum.entries
            .sortedByDescending { it.value }
            .map { (s, v) -> "$s ¥${fmt(v)}" }
        val topMerchants = merSum.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { (m, v) -> "$m ¥${fmt(v)}" }

        val budgetContext = runCatching {
            BudgetPlanner.snapshotMonth(appCtx).aiBudgetContext()
        }.getOrDefault("本月预算：未设置/统计失败")

        return WeeklyReportInput(
            fromLabel = dateFmt.format(Date(curFrom)),
            toLabel = dateFmt.format(Date(now)),
            spent = curSpent,
            count = curRows.size,
            prevSpent = prevSpent,
            prevCount = prevRows.size,
            catLines = catLines,
            sourceLines = realSourceLines,
            topMerchants = topMerchants,
            biggestLine = if (biggestAmount > 0) "$biggestMerchant ¥${fmt(biggestAmount)}" else "—",
            budgetContext = budgetContext
        )
    }
}

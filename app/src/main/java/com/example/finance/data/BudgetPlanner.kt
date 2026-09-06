// data/BudgetPlanner.kt
// 预算"当月快照"：读本地账单库算本月总支出/分类支出，叠加 BudgetStore 的预算设置，
// 统一产出 AI 提示词用的预算上下文与悬浮窗提示文案（与 UI 预算进度同一份数据）。

package com.example.finance.data

import android.content.Context

data class MonthBudget(
    val monthly: Double,          // 本月总预算
    val monthSpent: Double,       // 本月已花（全部来源）
    val catBudget: Map<String, Double>,   // 设了预算的分类 → 额度（未设置的不在 Map）
    val catSpent: Map<String, Double>     // 本月各分类已花
) {
    val remaining: Double get() = monthly - monthSpent

    fun catSpentOf(cat: String): Double = catSpent[cat] ?: 0.0

    fun hasCatBudget(cat: String): Boolean = catBudget.containsKey(cat)

    fun catRemaining(cat: String): Double = (catBudget[cat] ?: 0.0) - catSpentOf(cat)

    /** 金额格式化：最多两位小数、去尾零 */
    private fun fmt(v: Double): String {
        val s = "%.2f".format(v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /** AI 提示词里的"当前预算状态"整段 */
    fun aiBudgetContext(): String {
        val sb = StringBuilder("本月总预算 ¥${fmt(monthly)}，已花 ¥${fmt(monthSpent)}")
        sb.append(if (remaining >= 0) "，剩余 ¥${fmt(remaining)}" else "，已超支 ¥${fmt(-remaining)}")
        if (catBudget.isNotEmpty()) {
            sb.append("。分类预算：")
            sb.append(catBudget.entries.joinToString("；") { (c, budget) ->
                val left = budget - catSpentOf(c)
                val tail = if (left >= 0) "剩 ¥${fmt(left)}" else "超 ¥${fmt(-left)}"
                "$c 预算 ¥${fmt(budget)} 已花 ¥${fmt(catSpentOf(c))} $tail"
            })
        }
        return sb.toString()
    }

    /** 悬浮窗/日志的一句话剩余预算（优先分类，其次总预算） */
    fun shortReminder(category: String): String {
        if (hasCatBudget(category)) {
            val left = catRemaining(category)
            return if (left >= 0) "「$category」预算剩 ¥${fmt(left)}"
            else "「$category」已超支 ¥${fmt(-left)}"
        }
        return if (remaining >= 0) "本月预算剩 ¥${fmt(remaining)}"
        else "本月已超支 ¥${fmt(-remaining)}"
    }
}

object BudgetPlanner {

    /** 当月预算快照（需在 IO 线程调用；内部只读 Room 与 SharedPreferences） */
    suspend fun snapshotMonth(context: Context): MonthBudget {
        val appCtx = context.applicationContext
        val (from, to) = monthRange()
        val dao = FinanceDb.get(appCtx).billDao()
        val spent = runCatching { dao.sumBetweenOnce(from, to) }.getOrDefault(0.0)
        val rows = runCatching { dao.merchantAmountsBetween(from, to) }.getOrDefault(emptyList())
        val catSpent = HashMap<String, Double>()
        for (r in rows) {
            val c = BillCategories.categorize(r.merchant, r.source)
            catSpent[c] = (catSpent[c] ?: 0.0) + r.amount
        }
        return MonthBudget(
            monthly = BudgetStore.monthlyBudget(),
            monthSpent = spent,
            catBudget = BudgetStore.catBudgets(),
            catSpent = catSpent
        )
    }
}

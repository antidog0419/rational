// data/BudgetStore.kt
// 预算设置（本机 SharedPreferences）：月度总预算 + 分类预算（餐饮/购物/…可各自设置）。
// 与商家库一致：FinanceApp.onCreate 调 init(context)。
// 演示默认值：首次打开给一组示例预算；用户在「我的→预算设置」可改，填 0 表示"该分类不限"。

package com.example.finance.data

import android.content.Context

object BudgetStore {

    private const val PREFS = "budget_store"
    private const val KEY_MONTHLY = "monthly_budget"
    private const val KEY_CATS = "cat_budgets"

    private const val DEFAULT_MONTHLY = 2000.0
    private val DEFAULT_CATS = linkedMapOf(
        "餐饮" to 800.0,
        "购物" to 400.0,
        "日用" to 300.0,
        "交通" to 200.0
    )

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun p(): android.content.SharedPreferences =
        prefs ?: throw IllegalStateException("BudgetStore 未初始化（FinanceApp.onCreate 调 init）")

    fun monthlyBudget(): Double {
        val store = prefs ?: return DEFAULT_MONTHLY
        if (!store.contains(KEY_MONTHLY)) return DEFAULT_MONTHLY
        return store.getFloat(KEY_MONTHLY, DEFAULT_MONTHLY.toFloat()).toDouble().coerceAtLeast(0.0)
    }

    fun setMonthlyBudget(value: Double) {
        p().edit().putFloat(KEY_MONTHLY, value.coerceAtLeast(0.0).toFloat()).apply()
    }

    /** 分类预算：没有单独设置的分类不在 Map 里（不限）；首次使用给示例值 */
    fun catBudgets(): Map<String, Double> {
        val store = prefs ?: return DEFAULT_CATS
        val raw = store.getString(KEY_CATS, null)
        if (raw == null) return DEFAULT_CATS
        val out = LinkedHashMap<String, Double>()
        if (raw.isNotBlank()) {
            for (pair in raw.split(";")) {
                val kv = pair.split(":", limit = 2)
                if (kv.size == 2) {
                    val v = kv[1].toDoubleOrNull()
                    if (v != null && v > 0.0) out[kv[0]] = v
                }
            }
        }
        return out
    }

    /** 设置分类预算；value<=0 表示移除该分类限制 */
    fun setCatBudget(category: String, value: Double) {
        val map = catBudgets().toMutableMap()
        if (value > 0.0) map[category] = value else map.remove(category)
        saveCat(map)
    }

    fun setCatBudgets(map: Map<String, Double>) {
        saveCat(map.filterValues { it > 0.0 })
    }

    private fun saveCat(map: Map<String, Double>) {
        val body = map.entries.joinToString(";") { "${it.key}:${it.value}" }
        p().edit().putString(KEY_CATS, body).apply()
    }

    /** 恢复演示默认预算 */
    fun resetDemo() {
        p().edit().remove(KEY_MONTHLY).remove(KEY_CATS).apply()
    }
}

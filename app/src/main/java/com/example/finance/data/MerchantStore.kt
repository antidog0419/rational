// data/MerchantStore.kt
// 本地"商家库"：抓取美团/淘宝闪购/支付宝账单时自动把商家名与次数记在本机。
// 供"AI 外卖推荐"对比历史账单，找出用户最常点/最想吃的 Top 店家（数据不出本机）。

package com.example.finance.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject

object MerchantStore {

    private const val PREFS = "merchant_store"
    private const val KEY_MERCHANTS = "merchants_json"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /** 需在 FinanceApp.onCreate 调用一次 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun p(): android.content.SharedPreferences =
        prefs ?: throw IllegalStateException("MerchantStore 未初始化（FinanceApp.onCreate 调用 init）")

    /** 记录一个商家（去重、累计次数） */
    fun record(merchant: String) {
        val name = merchant.trim().take(24)
        if (name.isEmpty() || isNoise(name)) return
        val map = countsMap().toMutableMap()
        map[name] = (map[name] ?: 0) + 1
        save(map)
    }

    fun recordAll(merchants: List<String>) {
        if (merchants.isEmpty()) return
        val map = countsMap().toMutableMap()
        for (m in merchants) {
            val name = m.trim().take(24)
            if (name.isEmpty() || isNoise(name)) continue
            map[name] = (map[name] ?: 0) + 1
        }
        save(map)
    }

    /** 商家 + 次数（LinkedHashMap 保持插入序） */
    fun countsMap(): Map<String, Int> {
        val raw = p().getString(KEY_MERCHANTS, "{}") ?: "{}"
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val out = LinkedHashMap<String, Int>()
            for ((k, v) in obj) {
                val c = v.jsonPrimitive.contentOrNull?.toIntOrNull() ?: continue
                out[k] = c
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun size(): Int = countsMap().size

    /** 清空商家库（与"清空本地账单库"联动，避免残留旧商家影响 AI Top3 推荐） */
    fun clear() {
        runCatching { p().edit().clear().apply() }
    }

    /** 按次数降序取前 n 个候选（供 AI 推荐对比） */
    fun topCandidates(n: Int = 20): List<String> =
        countsMap().entries.sortedByDescending { it.value }.take(n).map { it.key }

    private fun save(map: Map<String, Int>) {
        val body = buildJsonObject {
            for ((k, v) in map) put(k, v)
        }
        p().edit().putString(KEY_MERCHANTS, body.toString()).apply()
    }

    /** 过滤明显噪声（状态词/导航词/金额类） */
    private fun isNoise(name: String): Boolean {
        if (name.length < 2) return true
        return name.contains("支出") || name.contains("收入") || name.contains("账单") ||
                name.contains("退款") || name.contains("还款") || name.contains("待") ||
                name.contains("未知商家") || name == "释放刷新" || name == "贴纸" ||
                name.contains("全部") || name.contains("筛选") || name.contains("更多") ||
                name.contains("共") || name.contains("件") || name.matches(Regex("""\d+(\.\d+)?"""))
    }
}

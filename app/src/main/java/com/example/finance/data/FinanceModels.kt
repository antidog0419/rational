// data/FinanceModels.kt
// 结构化消费领域模型（感知层"结构化情境"的落点）。
// 之前消费信息以裸字符串在总线中传递（"[CONSUMPTION]商家|金额|来源"），
// 任何解析方都依赖字符串协议、极易漂移；这里收敛为强类型模型。

package com.example.finance.data

/** 一笔被感知到的消费记录 */
data class ConsumptionRecord(
    val source: String,        // 支付宝 / 美团 / 本地演示 / 未知
    val merchant: String,      // 商家或商品说明
    val amount: Double,        // 金额（元）
    val occurredAtMs: Long = System.currentTimeMillis(),
) {
    /** 按发生时刻生成"情境时段"标签（对应路线图：时间/生物节律情境维度） */
    val dayPartLabel: String
        get() = dayPartOf(occurredAtMs)

    companion object {
        fun dayPartOf(ms: Long): String {
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = ms }
                .get(java.util.Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..7 -> "清晨"
                in 8..10 -> "上午"
                in 11..13 -> "中午"
                in 14..16 -> "下午"
                in 17..22 -> "晚上"
                else -> "深夜"
            }
        }
    }
}

// data/Rationale.kt
// 本地"理性指数"启发式（纯函数，可单测）。
// 说明：设计稿里的"理性指数/超过xx%的用户"属于产品化指标；这里先用真实本地数据
//       计算一个可复现的 0~100 分数（预算执行、环比增幅、深夜消费占比、小额高频），
//       百分位为启发式映射。后续可替换为更严谨的模型，UI 侧只消费 score/percent。

package com.example.finance.data

import kotlin.math.max
import kotlin.math.roundToInt

data class RationaleInput(
    val monthSpent: Double,          // 本月已花
    val budgetMonthly: Double,       // 本月总预算（>0 才参与计算）
    val prevRatioChangePct: Double,  // 环比上月增幅（%），上月无记录视为 0
    val lateNightRatio: Double,      // 深夜(00:00-06:00)消费笔数占比 0..1
    val avgOrder: Double             // 本月均单金额（元），无记录为 0
)

object Rationale {

    /** 0~100 理性指数（越高越理性）。纯启发式，规则集中在几项可解释惩罚上。 */
    fun score(inp: RationaleInput): Int {
        var penalty = 0.0
        // 1) 预算执行：超支越多惩罚越重；留有结余给轻微正向。
        if (inp.budgetMonthly > 0.0) {
            val ratio = inp.monthSpent / inp.budgetMonthly
            if (ratio > 1.0) penalty += (ratio - 1.0) * 60.0
            else penalty -= (1.0 - ratio) * 5.0
        }
        // 2) 环比增幅：支出比上月大涨为不良信号。
        penalty += max(0.0, inp.prevRatioChangePct) * 0.12
        // 3) 深夜消费占比：出分占比越高越减分。
        penalty += inp.lateNightRatio.coerceIn(0.0, 1.0) * 45.0
        // 4) 小额高频：均单 < 30 元且笔数多，略减分。
        if (inp.avgOrder > 0.0 && inp.avgOrder < 30.0) penalty += 6.0
        return (100.0 - penalty).roundToInt().coerceIn(0, 100)
    }

    /** 由分数映射"超过 xx% 的用户"（启发式，仅做展示用）。 */
    fun percentAbove(score: Int): Int = ((score - 35) * 100 / 65).coerceIn(5, 99)

    /** 时段标签：用于"为什么给建议"里的基础判断依据 */
    fun dayPartLabel(ms: Long): String {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            .get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h in 6..11 -> "上午"
            h in 12..13 -> "中午"
            h in 14..17 -> "下午"
            h in 18..21 -> "晚上"
            else -> "深夜"
        }
    }

    /** 是否深夜购物（22:00-06:00），用于行为经济学提示 */
    fun isLateNight(ms: Long): Boolean {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            .get(java.util.Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 6
    }
}

package com.example.finance.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JudgeAmount 纯规则单测：真机结算页样本（2026-09-07） */
class JudgeAmountTest {

    private fun row(text: String, top: Int = 0, bottom: Int = 40, cy: Int = 20) =
        JudgeAmount.Row(text, top, bottom, cy)

    @Test
    fun `美团真机-券前与共减红包同行拆分行`() {
        // 页面底部（dump 实测）：券前 + 拆分 ¥22.99 + 共减¥5 + 5元红包 + 极速支付(无金额)
        val rows = listOf(
            row("本单使用月付立减¥0.08", 2172, 2216, 2194),
            row("券前", 2285, 2327, 2306),
            row("¥22.99", 2258, 2333, 2295),
            row("共减¥5", 2278, 2322, 2300),
            row("点击使用5元红包", 2333, 2373, 2353),
            row("极速支付", 2288, 2344, 2316),
        )
        // 券前不是费用行 → 其同排拆分 ¥22.99 应保留；共减/红包/立减(自身含金额)行排除
        assertEquals(22.99, JudgeAmount.pick(rows)!!, 1e-9)
    }

    @Test
    fun `存在应付行时优先应付`() {
        val rows = listOf(
            row("应付 ¥29.88", 2100, 2140, 2120),
            row("¥22.99", 2160, 2230, 2195),
            row("共减¥5", 2200, 2240, 2220),
        )
        assertEquals(29.88, JudgeAmount.pick(rows)!!, 1e-9)
    }

    @Test
    fun `纯文字费用标签紧贴的拆分金额节点不误取_正常行距金额保留`() {
        val rows = listOf(
            row("共减", 2200, 2230, 2215),       // 纯标签，无数字
            row("¥1.8", 2230, 2260, 2245),        // 与标签紧贴(0px) → 拆分节点，排除
            row("¥22.99", 2300, 2360, 2330),      // 距标签 40px(正常行距) → 保留
        )
        assertEquals(22.99, JudgeAmount.pick(rows)!!, 1e-9)
    }

    @Test
    fun `全是费用行或空返回null`() {
        assertNull(JudgeAmount.pick(listOf(row("共减¥1.8"), row("红包¥5"))))
        assertNull(JudgeAmount.pick(emptyList()))
    }
}

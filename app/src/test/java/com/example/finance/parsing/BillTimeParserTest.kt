package com.example.finance.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * BillTimeParser 单测：样本来自真机支付宝/美团结账单页（DEV_STATUS / calib 记录），
 * 所有用例固定注入 nowMs，与系统时钟无关，任何时区/时刻跑都确定。
 */
class BillTimeParserTest {

    /** 本机默认时区下构造 y-m-d h:mi:00.000 */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private val now20260906_1800 = at(2026, 9, 6, 18, 0)

    // ---------- 今天 / 昨天 ----------

    @Test
    fun `今天 HH_mm 解析为当日时刻`() {
        assertEquals(at(2026, 9, 6, 14, 42), BillTimeParser.parse("今天 14:42", now20260906_1800))
    }

    @Test
    fun `昨天 HH_mm 解析为前一日时刻`() {
        assertEquals(at(2026, 9, 5, 21, 26), BillTimeParser.parse("昨天 21:26", now20260906_1800))
    }

    @Test
    fun `裸今天缺省正午12点`() {
        assertEquals(at(2026, 9, 6, 12, 0), BillTimeParser.parse("今天", now20260906_1800))
    }

    @Test
    fun `未来时刻视为无效_10分钟宽容内放行`() {
        assertNull(BillTimeParser.parse("今天 19:30", now20260906_1800))          // 超出 10 分钟宽容
        assertEquals(at(2026, 9, 6, 18, 5), BillTimeParser.parse("今天 18:05", now20260906_1800)) // 宽容窗内
        assertNull(BillTimeParser.parse("今天 14:42", at(2026, 9, 6, 10, 0)))     // 上午看到下午的时间
        assertEquals(at(2026, 9, 6, 10, 5), BillTimeParser.parse("今天 10:05", at(2026, 9, 6, 10, 0)))
    }

    @Test
    fun `非法时分返回null`() {
        assertNull(BillTimeParser.parse("昨天 25:00", now20260906_1800))
        assertNull(BillTimeParser.parse("昨天 08:99", now20260906_1800))
    }

    // ---------- 完整日期 ----------

    @Test
    fun `完整日期各种分隔符`() {
        assertEquals(at(2026, 8, 31, 12, 30), BillTimeParser.parse("2026-08-31 12:30", now20260906_1800))
        assertEquals(at(2026, 8, 31, 20, 15), BillTimeParser.parse("2026年8月31日 20:15", now20260906_1800))
        assertEquals(at(2026, 8, 3, 12, 0), BillTimeParser.parse("2026/8/3", now20260906_1800))
    }

    @Test
    fun `完整日期未来或非法返回null_闰年规则`() {
        assertNull(BillTimeParser.parse("2026-09-07 10:00", now20260906_1800))   // 明天 → 未来
        assertEquals(at(2024, 7, 15, 12, 30), BillTimeParser.parse("2024-07-15 12:30", now20260906_1800)) // 历史年份合法
        assertEquals(at(2024, 2, 29, 12, 30), BillTimeParser.parse("2024-02-29 12:30", now20260906_1800)) // 闰年 2/29
        assertNull(BillTimeParser.parse("2026-02-29 12:30", now20260906_1800))   // 平年 2/29
    }

    // ---------- 无年份日期（回看/跨年） ----------

    @Test
    fun `MM-dd 取今年最近一次不晚于今天`() {
        assertEquals(at(2026, 9, 4, 22, 14), BillTimeParser.parse("09-04 22:14", now20260906_1800))
        assertEquals(at(2026, 9, 4, 12, 0), BillTimeParser.parse("09-04", now20260906_1800))
    }

    @Test
    fun `MM-dd 跨年自动前推一年`() {
        // 2026-01-10 看到 12-25 08:00 → 2026-12-25 在未来 → 回推为 2025-12-25
        assertEquals(at(2025, 12, 25, 8, 0), BillTimeParser.parse("12-25 08:00", at(2026, 1, 10, 12, 0)))
    }

    @Test
    fun `M月d日 解析与跨年回推`() {
        assertEquals(at(2026, 9, 4, 22, 14), BillTimeParser.parse("9月4日 22:14", now20260906_1800))
        assertEquals(at(2026, 6, 18, 12, 0), BillTimeParser.parse("6月18日", now20260906_1800))
        // 2026-05-01 时 6月18日 尚未到来 → 回推 2025-06-18
        assertEquals(at(2025, 6, 18, 12, 0), BillTimeParser.parse("6月18日", at(2026, 5, 1, 12, 0)))
    }

    @Test
    fun `M月d日 支持星期后缀_带括号不支持`() {
        assertEquals(at(2026, 8, 3, 9, 30), BillTimeParser.parse("8月3日 周六 09:30", now20260906_1800))
        assertNull(BillTimeParser.parse("8月3日(周六) 09:30", now20260906_1800))
    }

    @Test
    fun `MM-dd 非法月份与未来时刻`() {
        assertNull(BillTimeParser.parse("13-01 10:00", now20260906_1800))
        assertNull(BillTimeParser.parse("00-10 10:00", now20260906_1800))
        // 引擎原行为（保留对齐）：无年份日期按"最近一次不晚于 now+宽容"回看，今年今天的时刻
        // 若尚未到点会回退到一年前的同一时刻，而不是判 null（历史页抓取时均为已发生时刻，
        // 该路径实际很少触发；行为与抽取前逐字一致，不做"顺手修复"以免脱离真机校准）。
        assertEquals(at(2025, 9, 6, 18, 30), BillTimeParser.parse("09-06 18:30", now20260906_1800))
        assertEquals(at(2026, 9, 6, 17, 59), BillTimeParser.parse("09-06 17:59", now20260906_1800))
    }

    // ---------- 空/边界 ----------

    @Test
    fun `空串_空白_超长返回null`() {
        assertNull(BillTimeParser.parse(null, now20260906_1800))
        assertNull(BillTimeParser.parse("", now20260906_1800))
        assertNull(BillTimeParser.parse("   ", now20260906_1800))
        assertNull(BillTimeParser.parse("x".repeat(41), now20260906_1800))
    }

    // ---------- extractRowTime（支付宝合并行，行尾时间） ----------

    @Test
    fun `行尾时间提取_支付宝合并文本行`() {
        val row = "沙县小吃，-10.90元，，餐饮美食，，今天 14:42"
        assertEquals(at(2026, 9, 6, 14, 42), BillTimeParser.extractRowTime(row, now20260906_1800))
    }

    @Test
    fun `取最后一个时间短语`() {
        val row = "奶茶 09-04 22:14 尾缀 2026-08-31 12:30"
        assertEquals(at(2026, 8, 31, 12, 30), BillTimeParser.extractRowTime(row, now20260906_1800))
    }

    @Test
    fun `无时间短语返回null`() {
        assertNull(BillTimeParser.extractRowTime("没有任何时间", now20260906_1800))
        assertNull(BillTimeParser.extractRowTime("", now20260906_1800))
    }

    // ---------- parseFlexible（容忍前缀/混排） ----------

    @Test
    fun `整串直接解析`() {
        assertEquals(at(2026, 9, 2, 10, 54), BillTimeParser.parseFlexible("2026-09-02 10:54", now20260906_1800))
        assertEquals(at(2026, 9, 4, 22, 14), BillTimeParser.parseFlexible("09-04 22:14", now20260906_1800))
    }

    @Test
    fun `美团下单前缀行解析`() {
        // 美团结算/订单行内真实样式："下单：2026-09-02 10:54"
        assertEquals(at(2026, 9, 2, 10, 54), BillTimeParser.parseFlexible("下单：2026-09-02 10:54", now20260906_1800))
    }

    @Test
    fun `混排文本扫行内时间短语`() {
        assertEquals(at(2026, 9, 4, 22, 14), BillTimeParser.parseFlexible("余额 09-04 22:14 保留", now20260906_1800))
        assertNull(BillTimeParser.parseFlexible("", now20260906_1800))
    }
}

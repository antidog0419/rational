package com.example.finance.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BillAmountText 单测：金额正则与美团 ¥ 拆分节点合并（¥ / 9 / .9 → 9.9）。
 * 样本来自真机美团结订单列表（DEV_STATUS / e2e 日志）。
 */
class BillAmountTextTest {

    // ---------- mergeYuanTokens ----------

    @Test
    fun `整数加小数两段`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥", "9", ".9"), 0)
        assertEquals(9.9, amount!!, 1e-9)
        assertEquals(3, next)
    }

    @Test
    fun `只有小数段补0`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("￥", ".9"), 0)
        assertEquals(0.9, amount!!, 1e-9)
        assertEquals(2, next)
    }

    @Test
    fun `只有整数段`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥", "9"), 0)
        assertEquals(9.0, amount!!, 1e-9)
        assertEquals(2, next)
    }

    @Test
    fun `孤立货币符组不出金额`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥"), 0)
        assertNull(amount)
        assertEquals(0, next)
    }

    @Test
    fun `消费后返回正确游标_后续文本不吞`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥", "9", ".9", "文本"), 0)
        assertEquals(9.9, amount!!, 1e-9)
        assertEquals(3, next)
    }

    @Test
    fun `金额为0视为组不出`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥", "0", ".0"), 0)
        assertNull(amount)
        assertEquals(0, next)
    }

    @Test
    fun `整数超4位不匹配`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥", "12345", ".99"), 0)
        assertNull(amount)
        assertEquals(0, next)
    }

    @Test
    fun `非零起点位置`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("文本", "¥", "7", ".5"), 1)
        assertEquals(7.5, amount!!, 1e-9)
        assertEquals(4, next)
    }

    @Test
    fun `无整数段后续原样继续`() {
        val (amount, next) = BillAmountText.mergeYuanTokens(listOf("¥", ".9", "10"), 0)
        assertEquals(0.9, amount!!, 1e-9)
        assertEquals(2, next)
    }

    // ---------- 正则 ----------

    @Test
    fun `AMOUNT_LINE_REGEX_独立金额行`() {
        assertEquals("12.00", BillAmountText.AMOUNT_LINE_REGEX.find("¥12.00")!!.groupValues[1])
        assertEquals("12.00", BillAmountText.AMOUNT_LINE_REGEX.find("-12.00")!!.groupValues[1])
        assertEquals("88.50", BillAmountText.AMOUNT_LINE_REGEX.find("+¥88.50")!!.groupValues[1])
        assertFalse(BillAmountText.AMOUNT_LINE_REGEX.containsMatchIn("12.3"))     // 一位小数
        assertFalse(BillAmountText.AMOUNT_LINE_REGEX.containsMatchIn("9.99元"))   // 带单位尾巴
    }

    @Test
    fun `PRICE_TOKEN_REGEX_纯价格token`() {
        assertEquals("9.9", BillAmountText.PRICE_TOKEN_REGEX.find("¥9.9")!!.groupValues[1])
        assertEquals("88", BillAmountText.PRICE_TOKEN_REGEX.find("￥88")!!.groupValues[1])
        assertFalse(BillAmountText.PRICE_TOKEN_REGEX.containsMatchIn("-¥5"))
    }

    @Test
    fun `ROW_INLINE_YUAN_REGEX_支付宝模式1整行`() {
        val m = BillAmountText.ROW_INLINE_YUAN_REGEX.find("沙县小吃，-10.90元，，餐饮美食")!!
        assertEquals("沙县小吃", m.groupValues[1])
        assertEquals("-", m.groupValues[2])
        assertEquals("10.90", m.groupValues[3])
    }
}

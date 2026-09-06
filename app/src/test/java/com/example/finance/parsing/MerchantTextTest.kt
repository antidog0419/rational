package com.example.finance.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MerchantText 单测：淘宝店名噪声过滤、店名归一化、账单行噪声过滤、就近商家查找。
 * 样本来自真机淘宝闪购订单卡 / 支付宝账单行（DEV_STATUS 校准记录）。
 */
class MerchantTextTest {

    // ---------- isTbBrandNoise ----------

    @Test
    fun `淘宝状态词与平台词是噪声`() {
        assertTrue(MerchantText.isTbBrandNoise(""))
        assertTrue(MerchantText.isTbBrandNoise("已完成"))
        assertTrue(MerchantText.isTbBrandNoise("已送达"))
        assertTrue(MerchantText.isTbBrandNoise("删除订单"))
        assertTrue(MerchantText.isTbBrandNoise("订单详情"))
        assertTrue(MerchantText.isTbBrandNoise("实付 ¥7.96"))
        assertTrue(MerchantText.isTbBrandNoise("¥7.96"))
    }

    @Test
    fun `真实店名不是噪声`() {
        assertFalse(MerchantText.isTbBrandNoise("沙县小吃"))
        assertFalse(MerchantText.isTbBrandNoise("大馅手工水饺（商中美食城第6档口）"))
    }

    @Test
    fun `过长文本是噪声`() {
        // 28 字 > 26 字阈值，且不含平台词 → 因超长判噪声
        assertTrue(MerchantText.isTbBrandNoise("这是一家名字长得超过二十六个字符根本不存在的超级店铺名称"))
        // 恰好 26 字且不含平台词 → 不算噪声（与原实现一致，26 是上限而非排除值）
        assertFalse(MerchantText.isTbBrandNoise("这是一家名字长得超过二十六个字符根本不存在的店铺名称"))
    }

    @Test
    fun `状态词表包含真机校准词`() {
        val words = MerchantText.TB_STATUS_WORDS
        assertTrue(words.contains("已完成"))
        assertTrue(words.contains("待评价"))
        assertTrue(words.contains("待付款"))
    }

    // ---------- normMerchant ----------

    @Test
    fun `去掉括号分店并去分隔符`() {
        assertEquals("大馅手工水饺", MerchantText.normMerchant("大馅手工水饺（商中美食城第6档口）"))
        assertEquals("三米粥铺", MerchantText.normMerchant("三米粥铺（海大店）"))
        assertEquals("瑞幸咖啡", MerchantText.normMerchant("瑞幸咖啡(湖光岩店)"))
    }

    @Test
    fun `平台后缀词剥离`() {
        assertEquals("沙县小吃", MerchantText.normMerchant("沙县小吃·外卖订单"))
        assertEquals("蜜雪冰城", MerchantText.normMerchant(" 蜜雪冰城 "))
    }

    @Test
    fun `英文品牌小写化`() {
        assertEquals("kfc", MerchantText.normMerchant("KFC(海洋大学店)"))
    }

    // ---------- isNoiseText（支付宝账单行噪声） ----------

    @Test
    fun `日期_分类_导航类文本是噪声`() {
        assertTrue(MerchantText.isNoiseText("今天 14:42"))
        assertTrue(MerchantText.isNoiseText("12:30"))
        assertTrue(MerchantText.isNoiseText("2026-09-06"))
        assertTrue(MerchantText.isNoiseText("总计 108.5"))
        assertTrue(MerchantText.isNoiseText("9月账单"))
        assertTrue(MerchantText.isNoiseText("收支分析"))
        assertTrue(MerchantText.isNoiseText("余额不足提醒"))
        assertTrue(MerchantText.isNoiseText("这是一段特别特别长超过二十四个字符的商家说明文字示例"))
    }

    @Test
    fun `商家名不是噪声`() {
        assertFalse(MerchantText.isNoiseText("沙县小吃"))
        assertFalse(MerchantText.isNoiseText("大馅手工水饺"))
        assertFalse(MerchantText.isNoiseText("餐饮美食"))
        assertFalse(MerchantText.isNoiseText("美团跑腿"))
    }

    // ---------- findMerchantNear ----------

    @Test
    fun `向上找最近的商家文本`() {
        val texts = listOf("支付成功", "沙县小吃", "¥12.50")
        assertEquals("沙县小吃", MerchantText.findMerchantNear(texts, 2))
    }

    @Test
    fun `跳过噪声找到商家`() {
        val texts = listOf("收入", "沙县小吃", "2026-09-06", "-12.30元")
        assertEquals("沙县小吃", MerchantText.findMerchantNear(texts, 3))
    }

    @Test
    fun `噪声超过四个返回null`() {
        val texts = listOf("收入", "支出", "退款", "更多", "12.30")
        assertNull(MerchantText.findMerchantNear(texts, 4))
    }
}

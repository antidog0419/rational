package com.example.finance.ui

import com.example.finance.data.BillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** #5 报表聚合纯函数单测 */
class BillsChartsTest {

    private fun bill(merchant: String, amount: Double, source: String, ms: Long): BillEntity =
        BillEntity(
            source = source, merchant = merchant, amount = amount,
            occurredAtMs = ms, dayBucket = BillEntity.dayBucket(ms),
            timeBucket = BillEntity.timeBucketOf(ms),
        )

    @Test
    fun `近6月趋势固定6个点且当月合计正确`() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -3) // 3 个月前
        val oldMs = cal.timeInMillis
        val rows = listOf(
            bill("沙县小吃", 10.0, "美团", now),
            bill("瑞幸咖啡", 20.5, "手动", now),
            bill("历史外卖", 99.0, "美团", oldMs),
        )
        val trend = computeTrend(rows)
        assertEquals(6, trend.size)
        val last = trend.last()
        val monthLabel = (Calendar.getInstance().get(Calendar.MONTH) + 1).toString() + "月"
        assertEquals(monthLabel, last.label)
        assertEquals(30.5, last.sum, 1e-9)
    }

    @Test
    fun `分类占比按金额降序`() {
        val now = System.currentTimeMillis()
        val rows = listOf(
            bill("沙县小吃", 40.0, "美团", now),   // 餐饮
            bill("火车票", 100.0, "支付宝", now),  // 交通
            bill("奶茶", 15.0, "美团", now),        // 餐饮
        )
        val share = computeCatShare(rows)
        assertTrue(share.isNotEmpty())
        assertEquals("交通", share.first().first) // 100 > 55
        assertEquals(55.0, share.first { it.first == "餐饮" }.second, 1e-9)
    }
}

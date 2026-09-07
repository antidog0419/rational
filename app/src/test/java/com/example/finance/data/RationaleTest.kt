package com.example.finance.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rationale 单测：理性指数启发式（预算执行/环比/深夜占比/小额高频），
 * 保证分数在 0..100、方向正确（更理性→更高分）。
 */
class RationaleTest {

    private fun input(
        spent: Double = 500.0,
        budget: Double = 2000.0,
        ratioChangePct: Double = 0.0,
        lateNightRatio: Double = 0.0,
        avgOrder: Double = 50.0
    ) = RationaleInput(spent, budget, ratioChangePct, lateNightRatio, avgOrder)

    @Test
    fun `预算内比超支得分更高`() {
        val under = Rationale.score(input(spent = 1000.0, budget = 2000.0))
        val over = Rationale.score(input(spent = 2600.0, budget = 2000.0))
        assertTrue("预算内应高于超支", under > over)
    }

    @Test
    fun `分数范围0到100`() {
        val low = Rationale.score(input(spent = 10000.0, budget = 1000.0, lateNightRatio = 1.0, ratioChangePct = 400.0))
        val high = Rationale.score(input(spent = 200.0, budget = 5000.0, lateNightRatio = 0.0, ratioChangePct = -20.0))
        assertTrue(low in 0..100)
        assertTrue(high in 0..100)
    }

    @Test
    fun `深夜消费占比越高分数越低`() {
        val noLate = Rationale.score(input(lateNightRatio = 0.0))
        val allLate = Rationale.score(input(lateNightRatio = 1.0))
        assertTrue("深夜占比高应更低分", noLate > allLate)
    }

    @Test
    fun `百分位映射有界`() {
        for (s in listOf(0, 35, 50, 78, 100)) {
            val p = Rationale.percentAbove(s)
            assertTrue("$s -> $p", p in 5..99)
        }
    }

    @Test
    fun `时段标签与深夜判定`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        assertEquals("深夜", Rationale.dayPartLabel(cal.timeInMillis))
        assertTrue(Rationale.isLateNight(cal.timeInMillis))
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
        assertEquals("中午", Rationale.dayPartLabel(cal.timeInMillis))
        assertTrue(!Rationale.isLateNight(cal.timeInMillis))
    }
}

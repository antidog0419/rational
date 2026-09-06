package com.example.finance.parsing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * BillKeys 单测：引擎内去重键（固定 Locale.US 防小数逗号区域破坏去重）与日志时间格式。
 */
class BillKeysTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun `去重键含两位小数与发生日`() {
        assertEquals(
            "沙县小吃|12.50|2026-09-06",
            BillKeys.seenKey("沙县小吃", 12.5, at(2026, 9, 6, 14, 42))
        )
    }

    @Test
    fun `整数金额补两位小数`() {
        assertEquals(
            "蜜雪冰城|12.00|2026-09-06",
            BillKeys.seenKey("蜜雪冰城", 12.0, at(2026, 9, 6, 14, 42))
        )
    }

    @Test
    fun `无支付时间记unknown日`() {
        assertEquals("沙县小吃|12.50|unknown", BillKeys.seenKey("沙县小吃", 12.5, null))
    }

    @Test
    fun `金额格式与语言环境无关`() {
        // 即使 JVM 默认区域用逗号小数，key 也必须是小点分隔（去重一致性）
        val key = BillKeys.seenKey("沙县小吃", 12.5, at(2026, 9, 6, 14, 42))
        assertEquals("沙县小吃|12.50|2026-09-06", key)
    }

    @Test
    fun `日志格式`() {
        assertEquals("--", BillKeys.fmtForLog(null))
        assertEquals("2026-09-06 14:42", BillKeys.fmtForLog(at(2026, 9, 6, 14, 42)))
    }
}

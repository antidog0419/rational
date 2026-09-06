package com.example.finance.parsing

import java.util.Calendar

/**
 * 账单「支付时间」文本解析器（纯函数，无 Android 依赖，可单测）。
 *
 * 原实现位于 FinanceAccessibilityService.kt 内部，2026-09-07 抽取至此；
 * 行为与真机校准版完全一致（逐行搬运，仅把"当前时刻"改为参数注入以便测试）。
 *
 * 支持格式：
 *   今天 / 昨天 [HH:mm]
 *   yyyy年M月d日 / yyyy-MM-dd / yyyy/M/d [+ HH:mm]
 *   M月d日[周X|星期X] [HH:mm]（无年份）
 *   MM-dd [HH:mm]（无年份，支付宝账单行尾常见）
 *
 * 规则：明显未来时间视为无效（宽容 [FUTURE_TOLERANCE_MS] 分钟）；
 *      无年份日期从今年起最多回看 [YEAR_LOOKBACK] 年，取最近一次不晚于 nowMs 的
 *      日期（跨年自动前推）。所有时间均为本机默认时区。
 */
object BillTimeParser {

    /** 未来时间宽容窗口：10 分钟（避免"今天 xx:xx"刚好未到时分被误判为未来） */
    const val FUTURE_TOLERANCE_MS = 10 * 60_000L

    /** 无年份日期最多回看的年数（curY、curY-1、curY-2） */
    const val YEAR_LOOKBACK = 2

    /** 解析整串时间文本 → 本地毫秒；解析不到返回 null。nowMs 为"当前时刻"，默认取系统时钟。 */
    fun parse(raw: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val t = (raw ?: "").trim()
        if (t.isEmpty() || t.length > 40) return null
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val curY = now.get(Calendar.YEAR)

        fun daysInMonth(y: Int, mo: Int): Int {
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, mo - 1, 1)
            return c.getActualMaximum(Calendar.DAY_OF_MONTH)
        }

        fun build(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun ok(y: Int, mo: Int, d: Int, h: Int, mi: Int): Boolean =
            y in 2000..curY && mo in 1..12 && d in 1..daysInMonth(y, mo) && h in 0..23 && mi in 0..59

        val nowLimit = nowMs + FUTURE_TOLERANCE_MS

        // 1) 今天 / 昨天 [+ HH:mm]（缺省 12:00）
        Regex("""^(今天|昨天)(?:\s*(\d{1,2}):(\d{2}))?$""").find(t)?.let { m ->
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            if (m.groupValues[1] == "昨天") cal.add(Calendar.DAY_OF_YEAR, -1)
            val h = m.groupValues[2].toIntOrNull() ?: 12
            val mi = m.groupValues[3].toIntOrNull() ?: 0
            if (h !in 0..23 || mi !in 0..59) return null
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, mi)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val ms = cal.timeInMillis
            return if (ms <= nowLimit) ms else null
        }

        // 2) yyyy年M月d日 / yyyy-MM-dd / yyyy/M/d [+ HH:mm]
        Regex("""^(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?(?:\s*(\d{1,2}):(\d{2}))?$""").find(t)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            val d = m.groupValues[3].toIntOrNull() ?: return null
            val h = m.groupValues[4].toIntOrNull() ?: 12
            val mi = m.groupValues[5].toIntOrNull() ?: 0
            if (!ok(y, mo, d, h, mi)) return null
            val ms = build(y, mo, d, h, mi)
            return if (ms <= nowLimit) ms else null
        }

        // 3) M月d日[周X|星期X] [+ HH:mm] —— 无年份：从今年往前找最近一次 ≤ 今天
        Regex("""^(\d{1,2})月(\d{1,2})日(?:\s*(?:周[一二三四五六日天]|星期[一二三四五六日天]))?(?:\s*(\d{1,2}):(\d{2}))?$""")
            .find(t)?.let { m ->
                val mo = m.groupValues[1].toIntOrNull() ?: return null
                val d = m.groupValues[2].toIntOrNull() ?: return null
                val h = m.groupValues[3].toIntOrNull() ?: 12
                val mi = m.groupValues[4].toIntOrNull() ?: 0
                if (mo !in 1..12 || d !in 1..daysInMonth(curY, mo) || h !in 0..23 || mi !in 0..59) return null
                for (back in 0..YEAR_LOOKBACK) {
                    val ms = build(curY - back, mo, d, h, mi)
                    if (ms <= nowLimit) return ms
                }
                return null
            }

        // 4) MM-dd [HH:mm]（如 09-04 22:14）—— 无年份：取最近一次 ≤ 今天，跨年自动前推
        Regex("""^(\d{2})-(\d{2})(?:\s*(\d{1,2}):(\d{2}))?$""").find(t)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull() ?: return null
            val d = m.groupValues[2].toIntOrNull() ?: return null
            val h = m.groupValues[3].toIntOrNull() ?: 12
            val mi = m.groupValues[4].toIntOrNull() ?: 0
            if (mo !in 1..12 || d !in 1..daysInMonth(curY, mo) || h !in 0..23 || mi !in 0..59) return null
            for (back in 0..YEAR_LOOKBACK) {
                val ms = build(curY - back, mo, d, h, mi)
                if (ms <= nowLimit) return ms
            }
            return null
        }
        return null
    }

    /**
     * 支付宝账单行是"合并文本节点"：整行 = 商家，-10.90元，，分类，，支付时间
     * （今天 14:42 / 昨天 21:26 / 09-04 22:14 / 2024-07-15 12:30 …）。
     * 扫整行取【最后一个】时间短语，交 [parse] 解析。
     */
    fun extractRowTime(row: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val matcher = Regex(
            """今天\s*\d{1,2}:\d{2}|昨天\s*\d{1,2}:\d{2}|""" +
                """\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?(?:\s*\d{1,2}:\d{2})?|""" +
                """\d{1,2}月\d{1,2}日(?:\s*\d{1,2}:\d{2})?|\d{2}-\d{2}(?:\s*\d{1,2}:\d{2})?"""
        ).findAll(row).lastOrNull()?.value ?: return null
        return parse(matcher, nowMs)
    }

    /** 容忍带前缀/混排的文本（"下单：2026-09-02 10:54"、"09-04 22:14" 等）：整串失败则扫行内时间短语 */
    fun parseFlexible(raw: String?, nowMs: Long = System.currentTimeMillis()): Long? =
        parse(raw, nowMs) ?: extractRowTime(raw ?: "", nowMs)
}

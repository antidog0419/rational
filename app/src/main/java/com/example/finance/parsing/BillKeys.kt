package com.example.finance.parsing

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 引擎内去重键 / 日志时间格式（纯函数，可单测）。
 *
 * 原实现位于 FinanceAccessibilityService.kt 内部（seenKeyFor / fmtTimeForLog），
 * 2026-09-07 抽取至此。去重键刻意固定 Locale.US：金额格式化若跟随系统语言，
 * 某些区域设置（如小数逗号）会让同一笔消费产生两种 key，破坏引擎内去重。
 */
object BillKeys {

    /**
     * 引擎内去重键：来源无关，`商家|金额(两位小数)|发生日(yyyy-MM-dd)`；
     * timeMs 为 null（未识别支付时间）时日段记为 "unknown"。
     */
    fun seenKey(merchant: String, amount: Double, timeMs: Long?): String {
        val day = timeMs?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it))
        } ?: "unknown"
        return "$merchant|" + String.format(Locale.US, "%.2f", amount) + "|$day"
    }

    /** 诊断日志用：毫秒 → "yyyy-MM-dd HH:mm"；null → "--" */
    fun fmtForLog(ms: Long?): String =
        ms?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "--"
}

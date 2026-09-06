// service/RealtimeGate.kt
// 跨通道去重闸门：页面监听 与 通知监听 共享同一窗口（默认 6 秒内同来源+同金额视为同一次支付）。
// 页面通道先到先得（商家名更准确）；通知通道延迟提交，若窗口内页面已入账则自动跳过。

package com.example.finance.service

object RealtimeGate {

    private const val WINDOW_MS = 6_000L
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 尝试登记一笔实时支付。
     * @return true=首次（应入账）；false=窗口内已登记过（重复，应跳过）
     */
    fun acquire(source: String, amount: Double): Boolean {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > WINDOW_MS }
        val key = "$source|${"%.2f".format(amount)}"
        return recent.putIfAbsent(key, now) == null
    }
}

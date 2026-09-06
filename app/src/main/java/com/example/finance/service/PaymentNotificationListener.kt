// service/PaymentNotificationListener.kt
// 支付通知监听：从通知栏实时抓取"转账/支付成功"通知 → 自动入账（无需停留在付款页）。
// 需用户在系统设置中为理伴开启"通知使用权(Notification access)"，并在系统设置允许显示通知。
// 同时兼容实时页面监听，两者各自去重，偶尔同一条双触发会由上层全局去重覆盖。

package com.example.finance.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.finance.ai.AIService
import com.example.finance.data.AccessibilityEventRepository
import com.example.finance.data.BudgetPlanner
import com.example.finance.data.ConsumptionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PaymentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "👁️ 通知监听已连接（可实时抓取支付通知）")
        AccessibilityEventRepository.postMessage("👁️ 通知监听已开启：支付/转账通知将自动入账")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "通知监听已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val pkg = sbn.packageName
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val source = sourceName(pkg) ?: return
            // 只处理已支持来源的通知（支付宝/美团/淘宝闪购）；其它应用通知一律忽略
            Log.d(TAG, "notif-rcv pkg=$pkg title=$title")
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val joined = "$title $text $big"

            // 只关心"支出类"通知；到账/收款成功是收入，不记
            val incomeHit = joined.contains("到账") || joined.contains("收款成功") ||
                    joined.contains("入账") || joined.contains("收入") || joined.contains("收到")
            val expenseHit = joined.contains("已转出") || joined.contains("转账成功") ||
                    joined.contains("支付成功") || joined.contains("付款成功") ||
                    joined.contains("支出") || joined.contains("消费") ||
                    joined.contains("付款") || joined.contains("微信支付凭证") ||
                    joined.contains("交易提醒") || joined.contains("转账")
            if (!expenseHit || incomeHit) {
                if (source != "测试通知") {
                    Log.d(TAG, "notif-skip $source expenseHit=$expenseHit incomeHit=$incomeHit title=[$title] text=[$text] big=[$big]")
                }
                return
            }

            val amount = parseAmount(joined) ?: run {
                Log.d(TAG, "notif-nomoney $source title=[$title] text=[$text] big=[$big]")
                return
            }
            val merchant = parseMerchant(title, joined, source).ifBlank { "$source 消费" }
            val key = "$merchant|$amount|$source"
            // 通知通道延迟 1.5s 提交：给页面通道优先权（其商家名更准确）；
            // 窗口内页面已入账（同来源同金额）则本次跳过，避免双记与泛化商家覆盖。
            CoroutineScope(Dispatchers.IO).launch {
                delay(1500)
                if (!RealtimeGate.acquire(source, amount)) {
                    Log.d(TAG, "⏭ 通知[$source] ¥$amount 与页面通道重复，跳过（merchant=$merchant）")
                    return@launch
                }
                if (!Recent.add(key)) return@launch // 同 key 近期重复
                Log.d(TAG, "✅ 通知[$source]: $merchant - ¥$amount  (title=$title)")
                AccessibilityEventRepository.postConsumption(
                    ConsumptionRecord(source = source, merchant = merchant, amount = amount)
                )
                val snap = BudgetPlanner.snapshotMonth(this@PaymentNotificationListener)
                AIService.processConsumptionEvent("$merchant ¥$amount", snap.aiBudgetContext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通知失败: ${e.message}")
        }
    }

    private fun parseAmount(text: String): Double? {
        // 1) ¥/￥ 直接跟数字："支出¥0.01"
        Regex("""[¥￥]\s?([0-9]+(?:\.[0-9]{1,2})?)""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.takeIf { a -> a > 0.0 }
        }
        // 2) "你有一笔0.01元的支出"：笔…元
        Regex("""笔\s*([0-9]+(?:\.[0-9]{1,2})?)\s*元""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.takeIf { a -> a > 0.0 }
        }
        // 3) 关键词在数字前
        Regex("""(?:已转出|转出|支付|付款|支出|消费|金额|成功)[¥￥]?\s*([0-9]+(?:\.[0-9]{1,2})?)""")
            .find(text)?.let {
                return it.groupValues[1].toDoubleOrNull()?.takeIf { a -> a > 0.0 }
            }
        // 4) 已通过"支出语义"过滤的兜底：取第一个两位小数金额
        Regex("""([0-9]+\.[0-9]{1,2})""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.takeIf { a -> a > 0.0 }
        }
        return null
    }

    private fun parseMerchant(title: String, joined: String, source: String): String {
        // 常见句式："向 小明 转账"/"给 张三 的转账"/"微信支付-某某店"
        listOf("转账给", "转给", "向", "给", "付款给").forEach { prefix ->
            joined.substringAfter(prefix, "").trim().take(20).takeIf { it.isNotBlank() }?.let {
                val name = it.split(" ", "、", "，", ",").firstOrNull { cand -> cand.length in 2..20 } ?: it
                return name.trim()
            }
        }
        if (joined.contains("微信支付凭证")) {
            joined.substringAfter("凭证", "").substringBefore("，").trim().take(20)
                .takeIf { it.isNotBlank() }?.let { return it }
        }
        // 标题是泛化提醒词时不给商家，用来源兜底
        val genericTitles = listOf("交易提醒", "支付提醒", "支付成功通知", "付款通知")
        if (title.isBlank() || genericTitles.any { title.contains(it) }) return "$source 消费"
        return title.take(20)
    }

    private fun sourceName(pkg: String): String? = when (pkg) {
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.sankuai.meituan" -> "美团"
        "com.taobao.taobao" -> "淘宝闪购"
        "com.example.finance" -> "测试通知" // 允许发一条假通知做自测
        else -> null
    }

    /** 短窗口去重（60 秒内同 key 只记一次） */
    private object Recent {
        private const val TTL_MS = 60_000L
        private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()
        fun add(key: String): Boolean {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now - it.value > TTL_MS }
            return map.putIfAbsent(key, now) == null
        }
    }

    companion object {
        const val TAG = "PaymentNotif"
    }
}

// debug/DebugReceiver.kt
// 测试期自动化钩子：adb shell am broadcast 即可驱动核心流程，无需 UI 点击。
//   模拟一笔消费：   adb shell am broadcast -a com.example.finance.TEST_SIMULATE \
//                        -n com.example.finance/.debug.DebugReceiver --es merchant 沙县小吃 --ef amount 35.5
//   触发一条 AI 建议：adb shell am broadcast -a com.example.finance.TEST_ADVICE \
//                        -n com.example.finance/.debug.DebugReceiver --es text "外卖 ¥35"
// 说明：仅用于本地调试与自动化验收；正式发布前应删除本组件。

package com.example.finance.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.finance.ai.AIService
import com.example.finance.data.AccessibilityEventRepository
import com.example.finance.data.BudgetPlanner
import com.example.finance.data.ConsumptionRecord
import com.example.finance.data.UserSettings
import com.example.finance.network.ModelAPIClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SIMULATE -> {
                val merchant = intent.getStringExtra("merchant") ?: "模拟支出"
                val amount = intent.getDoubleExtra("amount", 28.5)
                AccessibilityEventRepository.postConsumption(
                    ConsumptionRecord(source = "本地演示", merchant = merchant, amount = amount)
                )
                Log.d(TAG, "TEST_SIMULATE 已投递: $merchant ¥$amount")
                // 模拟"实时支付后"的 AI 建议链路（预算上下文取本机真实设置）
                CoroutineScope(Dispatchers.IO).launch {
                    val snap = BudgetPlanner.snapshotMonth(context)
                    AIService.processConsumptionEvent("$merchant ¥$amount", snap.aiBudgetContext())
                }
            }

            ACTION_ADVICE -> {
                val text = intent.getStringExtra("text") ?: "模拟外卖 ¥35"
                CoroutineScope(Dispatchers.IO).launch {
                    val snap = BudgetPlanner.snapshotMonth(context)
                    AIService.processConsumptionEvent(text, snap.aiBudgetContext())
                }
                Log.d(TAG, "TEST_ADVICE 已触发: $text")
            }

            ACTION_FETCH_BILLS -> {
                // 与 App 内按钮一致：先落盘排队标记再投递；服务未连接时不丢，连上后自动执行
                UserSettings(context).pendingBillFetch = true
                AccessibilityEventRepository.postAlipayBillFetchRequest()
                Log.d(TAG, "TEST_FETCH_BILLS 已请求（含排队标记）")
            }

            ACTION_FETCH_MEITUAN -> {
                AccessibilityEventRepository.postMeituanBillFetchRequest()
                Log.d(TAG, "TEST_MEITUAN 已请求抓取美团账单")
            }

            ACTION_FETCH_TAOBAO -> {
                AccessibilityEventRepository.postTaobaoBillFetchRequest()
                Log.d(TAG, "TEST_TAOBAO 已请求抓取淘宝闪购账单")
            }

            ACTION_STOP_FETCH -> {
                AccessibilityEventRepository.postStopBillFetchRequest()
                Log.d(TAG, "TEST_STOP_FETCH 已请求停止当前抓取")
            }

            ACTION_DUMP_SCREEN -> {
                AccessibilityEventRepository.postDumpScreenRequest()
                Log.d(TAG, "TEST_DUMP_SCREEN 已请求 dump 当前窗口")
            }

            ACTION_TEST_NOTIFY -> {
                // 自测：从本应用发一条"伪转账成功通知"，验证通知监听链路
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channel = android.app.NotificationChannel(
                        "test_pay", "测试支付", android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    nm.createNotificationChannel(channel)
                    val notif = android.app.Notification.Builder(context, "test_pay")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("转账成功")
                        .setContentText("你已向 测试收款方 转账0.01元")
                        .setAutoCancel(true)
                        .build()
                    nm.notify(9527, notif)
                    Log.d(TAG, "TEST_NOTIFY 已发出假通知（若通知监听已开，会看到 PaymentNotif 入账）")
                } catch (e: Exception) {
                    Log.e(TAG, "发通知失败: ${e.message}")
                }
            }

            ACTION_WEEKLY -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val input = com.example.finance.data.WeeklyReportBuilder.build(context)
                    if (input.count == 0) {
                        Log.d(TAG, "TEST_WEEKLY 近7天暂无账单")
                    } else {
                        AIService.generateWeeklyReport(input)
                        Log.d(TAG, "TEST_WEEKLY 已生成周报（${input.count} 笔）")
                    }
                }
            }

            ACTION_EXPORT_CSV -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = com.example.finance.data.FinanceDb.get(context)
                    val rows = db.billDao().all()
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    android.util.Log.i("FinanceCsv", "== 账单共 ${rows.size} 条 ==")
                    for (r in rows.sortedByDescending { it.occurredAtMs }) {
                        android.util.Log.i(
                            "FinanceCsv",
                            "${r.source}|${r.merchant.replace("|", " ")}|${"%.2f".format(r.amount)}|${fmt.format(java.util.Date(r.occurredAtMs))}"
                        )
                    }
                    Log.d(TAG, "TEST_EXPORT_CSV 已输出 ${rows.size} 行(FinanceCsv)")
                }
            }

            ACTION_CLEAR_BILLS -> {
                AccessibilityEventRepository.clearAllBills()
                Log.d(TAG, "TEST_CLEAR_BILLS 已清空本地账单库")
            }

            ACTION_AGENT_STATUS -> {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        com.example.finance.agent.AgentGraph.syncAll(context)
                        val repo = com.example.finance.agent.AgentGraph.repository
                        val profile = repo.getProfile()
                        val goal = repo.getGoal()
                        android.util.Log.d(
                            "FinanceAgent",
                            "STATUS profile=月预算¥${profile.monthlyBudgetCents / 100.0} 已花¥${profile.currentSpentCents / 100.0} " +
                                "goal=${goal?.name ?: "无"} 目标¥${goal?.targetAmountCents?.div(100.0) ?: 0}"
                        )
                    }.onFailure {
                        android.util.Log.e("FinanceAgent", "STATUS 失败: ${it.message}")
                    }
                }
                Log.d(TAG, "TEST_AGENT_STATUS 已触发")
            }

            ACTION_AGENT_TEST -> {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        com.example.finance.agent.AgentGraph.syncAll(context) // 同步预算口径+DeepSeek 后再决策
                        val scene = com.example.finance.scene.SceneContext(
                            sceneType = "manual_test",
                            product = com.example.finance.scene.Product(
                                name = "索尼 WH-1000XM5 无线降噪耳机",
                                category = "electronics",
                                brand = "索尼",
                                model = "WH-1000XM5",
                            ),
                            price = com.example.finance.scene.PriceInfo(currentCents = 2_999_00),
                            signals = com.example.finance.scene.SceneSignals(discount = true, limitedTime = true),
                            confidence = 0.9,
                            productConfidence = 0.9,
                            priceConfidence = 0.9,
                        )
                        val (_, decision) = com.example.finance.agent.AgentGraph.orchestrator.analyzeScene(scene) {
                            android.util.Log.d("FinanceAgent", "state=$it")
                        }
                        android.util.Log.d(
                            "FinanceAgent",
                            "决策=${decision.riskLevel.name}/${decision.recommendation.name} title=${decision.display.title} | ${decision.display.summary} | factors=${decision.factors} | points=${decision.display.keyPoints}"
                        )
                    }.onFailure {
                        android.util.Log.e("FinanceAgent", "TEST 失败: ${it.message}")
                    }
                }
                Log.d(TAG, "TEST_AGENT_TEST 已触发（离线技能决策）")
            }

            ACTION_AGENT_RECORD -> {
                if (intent.getBooleanExtra("cleanup", false)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            com.example.finance.data.FinanceDb.get(context)
                                .billDao().deleteBySource(com.example.finance.data.BillSources.SCREEN)
                            android.util.Log.d("FinanceAgent", "已清空「识屏」来源测试账单")
                        }.onFailure { android.util.Log.e("FinanceAgent", "清理失败: ${it.message}") }
                    }
                    Log.d(TAG, "TEST_AGENT_RECORD cleanup 已触发")
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        val ctx = context
                        runCatching {
                            com.example.finance.agent.AgentGraph.syncAll(ctx)
                            // 记两笔相同的验证：第二次应因"同来源商家金额+同分钟"去重返回 -1
                            val first = com.example.finance.data.BillWriter.add(
                                ctx, "识屏测试商品A", 66.0,
                                com.example.finance.data.BillSources.SCREEN, "验证"
                            )
                            val second = com.example.finance.data.BillWriter.add(
                                ctx, "识屏测试商品A", 66.0,
                                com.example.finance.data.BillSources.SCREEN, "验证"
                            )
                            android.util.Log.d("FinanceAgent", "记一笔 first=$first second=$second (第二笔应为 -1=去重)")
                        }.onFailure {
                            android.util.Log.e("FinanceAgent", "记一笔失败: ${it.message}")
                        }
                    }
                    Log.d(TAG, "TEST_AGENT_RECORD 已触发")
                }
            }

            ACTION_CONFIGURE -> {
                val key = intent.getStringExtra("apiKey").orEmpty()
                val model = intent.getStringExtra("model").orEmpty()
                val baseUrl = intent.getStringExtra("baseUrl").orEmpty()
                val settings = UserSettings(context)
                if (key.isNotBlank()) settings.deepseekApiKey = key
                if (model.isNotBlank()) settings.deepseekModel = model
                if (baseUrl.isNotBlank()) settings.deepseekBaseUrl = baseUrl
                ModelAPIClient.updateConfig(
                    apiKey = key.ifBlank { null },
                    model = model.ifBlank { null },
                    baseUrl = baseUrl.ifBlank { null }
                )
                Log.d(
                    TAG,
                    "TEST_CONFIGURE 完成: key=${key.take(6)}***  model=${model.ifBlank { "默认" }}"
                )
            }
        }
    }

    companion object {
        const val TAG = "FinanceTest"
        const val ACTION_SIMULATE = "com.example.finance.TEST_SIMULATE"
        const val ACTION_ADVICE = "com.example.finance.TEST_ADVICE"
        const val ACTION_CONFIGURE = "com.example.finance.TEST_CONFIGURE"
        const val ACTION_FETCH_BILLS = "com.example.finance.TEST_FETCH_BILLS"
        const val ACTION_FETCH_MEITUAN = "com.example.finance.TEST_FETCH_MEITUAN"
        const val ACTION_FETCH_TAOBAO = "com.example.finance.TEST_FETCH_TAOBAO"
        const val ACTION_STOP_FETCH = "com.example.finance.TEST_STOP_FETCH"
        const val ACTION_DUMP_SCREEN = "com.example.finance.TEST_DUMP_SCREEN"
        const val ACTION_TEST_NOTIFY = "com.example.finance.TEST_NOTIFY"
        const val ACTION_WEEKLY = "com.example.finance.TEST_WEEKLY"
        const val ACTION_EXPORT_CSV = "com.example.finance.TEST_EXPORT_CSV"
        const val ACTION_CLEAR_BILLS = "com.example.finance.TEST_CLEAR_BILLS"
        const val ACTION_AGENT_STATUS = "com.example.finance.TEST_AGENT_STATUS"
        const val ACTION_AGENT_TEST = "com.example.finance.TEST_AGENT_TEST"
        const val ACTION_AGENT_RECORD = "com.example.finance.TEST_AGENT_RECORD"
    }
}

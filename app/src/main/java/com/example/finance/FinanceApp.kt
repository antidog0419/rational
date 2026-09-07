package com.example.finance

import android.app.Application
import android.util.Log
import com.example.finance.agent.AgentGraph
import com.example.finance.data.AccessibilityEventRepository
import com.example.finance.data.BudgetStore
import com.example.finance.data.FinanceDb
import com.example.finance.data.MerchantStore
import com.example.finance.data.UserSettings
import com.example.finance.network.ModelAPIClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把未捕获异常写入 crash.log（应用外部目录），便于测试期定位闪退。
 * 文件位置：adb shell run-as com.example.finance ls files/ 同级 external files，
 * 或直接:  adb logcat -b crash
 */
class FinanceApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 注入 DeepSeek 运行时配置（App 设置页持久化的值优先，空则回落到 BuildConfig 默认）
        val settings = UserSettings(this)
        settings.migrateKeyStorageIfNeeded() // 旧明文 Key → Keystore 加密迁移
        ModelAPIClient.updateConfig(
            apiKey = settings.deepseekApiKey,
            model = settings.deepseekModel,
            baseUrl = settings.deepseekBaseUrl
        )
        // 本地商家库初始化（账单抓取时自动积累商家）
        MerchantStore.init(this)
        // 预算设置初始化（月预算 / 分类预算）
        BudgetStore.init(this)
        // 本地账单库初始化（持久化所有入账记录）并绑定到事件仓库
        val db = FinanceDb.init(this)
        AccessibilityEventRepository.attachDb(db)

        // 识屏智能体（移植自 sult_liban）：初始化 DI + 真实数据同步（预算/已花/目标 + DeepSeek）
        AgentGraph.init(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { AgentGraph.syncAll(this@FinanceApp) }
                .onFailure { Log.e("FinanceApp", "识屏智能体初始化失败", it) }
        }

        // 一次性来源归一化（老库里的 "支付宝账单/美团账单/本地演示" → 规范名），IO 后台执行
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                db.billDao().migrateSourceAlipayBill()
                db.billDao().migrateSourceMeituanBill()
                db.billDao().migrateSourceDemo()
            }.onFailure {
                Log.e("FinanceApp", "来源归一化失败", it)
            }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "crash.log")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText(
                    "\n===== $stamp | thread=${thread.name} =====\n$sw\n"
                )
            } catch (ignored: Exception) {
                // 写入失败也不能再抛异常
            }
            Log.e("FinanceCrash", "未捕获异常 on ${thread.name}", throwable)
            // 转交原默认处理器，保持系统原生崩溃行为（弹窗并结束进程）
            previous?.uncaughtException(thread, throwable)
        }
    }
}

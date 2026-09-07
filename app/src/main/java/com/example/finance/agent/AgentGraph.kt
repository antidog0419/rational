// agent/AgentGraph.kt
// 识屏智能体 DI（移植自 sult_liban/LibanApplication.AppGraph，MIT）：
// 独立 Room 库(agent.db)+ 配置(DataStore)+ MLKit OCR + OkHttp + AgentOrchestrator。
// finance.db 是全 App 账单唯一权威：画像/目标首次用真实数据创建，此后每次 syncAll
// 都把"本月已花"刷新为 finance.db 当月实际合计，保证识屏决策预算口径永远最新。

package com.example.finance.agent

import android.content.Context
import com.example.finance.config.ConfigRepository
import com.example.finance.data.AppDao
import com.example.finance.data.AppDatabase
import com.example.finance.data.AppRepository
import com.example.finance.data.BudgetStore
import com.example.finance.data.FinanceDb
import com.example.finance.data.UserSettings
import com.example.finance.data.monthRange
import com.example.finance.ocr.MlKitChineseOcrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object AgentGraph {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    lateinit var repository: AppRepository
        private set
    lateinit var configRepository: ConfigRepository
        private set
    lateinit var orchestrator: AgentOrchestrator
        private set
    private lateinit var dao: AppDao

    /** MLKit 中文 OCR（复用同一识别器实例；供识屏管线与"下单判断截图取价"共用） */
    val ocrProvider = MlKitChineseOcrProvider()

    @Volatile
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val appCtx = context.applicationContext
            val db = AppDatabase.create(appCtx)
            dao = db.appDao()
            repository = AppRepository(db.appDao(), json)
            configRepository = ConfigRepository(appCtx)
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            orchestrator = AgentOrchestrator(
                repository = repository,
                configRepository = configRepository,
                ocrProvider = ocrProvider,
                httpClient = client,
                json = json,
            )
            ready = true
        }
    }

    /**
     * 一键同步（识屏分析/记一笔前调用，幂等且廉价）：
     *  ① 画像不存在时用 BudgetStore + FinanceDb 真实数据创建（月预算、默认储蓄目标）；
     *  ② 每次都把画像"本月已花"刷新为 finance.db 当月实际合计；
     *  ③ 同步 DeepSeek 配置（场景提取/估价，默认 deepseek-v4-flash）。
     */
    suspend fun syncAll(context: Context) = withContext(Dispatchers.IO) {
        init(context)
        syncProfileSpent(context)
        syncLlmFromFinance(context)
    }

    private suspend fun syncProfileSpent(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val monthlyCents = (BudgetStore.monthlyBudget() * 100).toLong().coerceAtLeast(0)
        val (from, to) = monthRange()
        val spentCents = (runCatching { FinanceDb.get(ctx).billDao().sumBetweenOnce(from, to) }
            .getOrDefault(0.0) * 100).toLong().coerceAtLeast(0)
        val existing = dao.getProfile()
        if (existing == null) {
            repository.saveProfile(monthlyCents, spentCents)
            repository.saveGoal(
                name = "我的小目标",
                targetCents = (monthlyCents * 6).coerceAtLeast(1),
                currentCents = 0,
                deadlineEpochDay = LocalDate.now().plusMonths(6).toEpochDay(),
            )
        } else {
            repository.saveProfile(existing.monthlyBudgetCents, spentCents)
        }
    }

    /**
     * 统一用 DeepSeek：把「我的 → DeepSeek 配置」同步为识屏 LLM（场景提取 + 模型估价）。
     * 识别/估价不再依赖智谱搜索 Key（搜索分支保留代码，未配置即跳过）。
     */
    suspend fun syncLlmFromFinance(context: Context) = withContext(Dispatchers.IO) {
        init(context)
        val s = UserSettings(context.applicationContext)
        val key = s.deepseekApiKey
        if (key.isNotBlank()) {
            val base = s.deepseekBaseUrl.ifBlank { "https://api.deepseek.com" }
            configRepository.saveLlm(
                base.trimEnd('/') + "/v1/chat/completions",
                key,
                s.deepseekModel.ifBlank { "deepseek-v4-flash" },
            )
        }
    }
}

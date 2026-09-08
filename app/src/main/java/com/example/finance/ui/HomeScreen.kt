// ui/HomeScreen.kt
// 底部导航三页：首页（概览+AI Top3 推荐）/ 账单（自动获取+明细+日志）/ 我的（无障碍+DeepSeek 设置+隐私）。
// AI 外卖推荐：基于本地商家库（账单抓取自动积累）对比历史消费，输出"最想吃 Top3"。

package com.example.finance.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.finance.BuildConfig
import com.example.finance.R
import com.example.finance.ai.AIAdvice
import com.example.finance.ai.AIService
import com.example.finance.data.AccessibilityEventRepository
import com.example.finance.data.BillCategories
import com.example.finance.data.BillEntity
import com.example.finance.data.BillSources
import com.example.finance.data.BudgetStore
import com.example.finance.data.DemoProfile
import com.example.finance.data.FinanceDb
import com.example.finance.data.MerchantStore
import com.example.finance.data.UserSettings
import com.example.finance.data.WeeklyReportBuilder
import com.example.finance.data.todayRange
import com.example.finance.network.ModelAPIClient
import com.example.finance.utils.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import com.example.finance.agent.AgentGraph
import com.example.finance.ui.components.BottomBarItem
import com.example.finance.ui.components.InterventionSheet
import com.example.finance.ui.components.LibanBottomBar
import com.example.finance.ui.components.PageHeader
import com.example.finance.ui.components.SectionCard
import com.example.finance.ui.mock.InterventionDetail
import com.example.finance.ui.mock.InterventionRow
import com.example.finance.ui.screens.CommunityScreen
import com.example.finance.ui.screens.DataPrivacyScreen
import com.example.finance.ui.screens.ExplainabilityScreen
import com.example.finance.ui.screens.MembershipScreen
import com.example.finance.ui.screens.ProfileScreen
import com.example.finance.ui.screens.BudgetRealScreen
import com.example.finance.ui.screens.GoalRealScreen
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors
import com.example.finance.data.monthRange

/** liban 二级页(全屏覆盖底栏) */
private enum class LibanStackPage { EXPLAINABILITY, DATA_PRIVACY, MEMBERSHIP, BUDGET, GOAL, SETTINGS, CONSULT }

/** 底部 5 Tab(索引与 LibanBottomBar 一致:2 为中央 + 按钮) */
private enum class MainTab(val label: String) {
    HOME("首页"), COMMUNITY("社区"), CENTER("+"), RECORD("记录"), MINE("我的")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val settings = remember { UserSettings(context) }

    // ---------- 状态 ----------
    // 账单数据一律以 Room 本地账单库为唯一事实源（首页/账单页各自订阅数据库 Flow），
    // 不再维护并行内存列表（旧实现：内存 300 条回填 + 实时追加，与库内容易不一致）。
    val logs = remember { mutableStateListOf<String>() }
    val aiAdvice = remember { mutableStateListOf<AIAdvice>() }
    var currentNeeds by remember { mutableStateOf("") }
    var isRecommendLoading by remember { mutableStateOf(false) }
    var isWeeklyLoading by remember { mutableStateOf(false) }
    var autoExecute by remember { mutableStateOf(settings.autoExecuteAiSearch) }
    var judgeEnabled by remember { mutableStateOf(settings.judgeBeforeOrderEnabled) }
    var topPicks by remember { mutableStateOf<List<String>>(emptyList()) }
    var merchantCount by remember { mutableStateOf(MerchantStore.size()) }
    var exportPath by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // ---------- DeepSeek 云端配置状态 ----------
    var apiKeyInput by remember { mutableStateOf(settings.deepseekApiKey.ifEmpty { BuildConfig.DEEPSEEK_API_KEY }) }
    var modelInput by remember { mutableStateOf(settings.deepseekModel.ifEmpty { BuildConfig.DEEPSEEK_MODEL }) }
    var dsStatus by remember {
        mutableStateOf(if (ModelAPIClient.isConfigured()) "已配置（模型：${BuildConfig.DEEPSEEK_MODEL}）" else "未配置")
    }

    // ---------- 无障碍服务状态（全局门控） ----------
    var a11yEnabled by remember { mutableStateOf(isFinanceAccessibilityEnabled(context)) }

    // ---------- 数据流 ----------
    // 0) 实时/抓取入账事件：入库已由仓库完成（Room 去重）；这里只做日志 + 商家库积累，
    //    展示一律走数据库 Flow（Room 变更会自动推送，无需手动回填）
    LaunchedEffect(Unit) {
        AccessibilityEventRepository.records.collectLatest { record ->
            MerchantStore.record(record.merchant)
            merchantCount = MerchantStore.size()
            logs.add(0, "💳 感知到消费：${record.dayPartLabel}【${record.source}】${record.merchant} ¥${record.amount}")
            android.util.Log.d("FinanceUI", "感知记录: ${record.source}|${record.merchant}|${record.amount}|${record.dayPartLabel}")
        }
    }
    LaunchedEffect(Unit) {
        AccessibilityEventRepository.events.collectLatest { event ->
            if (event.startsWith(AccessibilityEventRepository.PREFIX_AI_RECOMMENDATION)) {
                val name = event.removePrefix(AccessibilityEventRepository.PREFIX_AI_RECOMMENDATION)
                logs.add(0, "🤖 执行指令已发出：在美团搜索「$name」")
            } else {
                logs.add(0, event)
            }
        }
    }
    LaunchedEffect(Unit) {
        AIService.adviceFlow.collect { advice ->
            aiAdvice.add(0, advice)
            logs.add(0, "🧠 收到建议（来源：${advice.source}）")
            android.util.Log.d("FinanceUI", "AI建议[${advice.source}]: ${advice.advice}")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = isFinanceAccessibilityEnabled(context)
                android.util.Log.d("FinanceUI", "onResume 无障碍检测=$a11yEnabled")
                merchantCount = MerchantStore.size()
                if (settings.pendingBillFetch && a11yEnabled) {
                    settings.pendingBillFetch = false
                    AccessibilityEventRepository.postAlipayBillFetchRequest()
                    logs.add(0, "📋 无障碍已开启，返回本应用：自动开始抓取账单…")
                    android.util.Log.d("FinanceUI", "onResume 消费排队请求，自动开始抓取")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---------- 执行动作：把选中的 Top 店家交给无障碍服务自动去美团搜索 ----------
    fun executePicked(name: String) {
        AccessibilityEventRepository.postAIRecommendedRestaurant(name)
        AppLauncher.launchMeituan(context)
        logs.add(0, "🔍 正在打开美团并自动搜索「$name」")
        android.util.Log.d("FinanceUI", "执行推荐店家: $name")
    }

    // ---------- 每周小结：本地聚合 → AI 周报 ----------
    fun generateWeekly() {
        if (isWeeklyLoading) return
        isWeeklyLoading = true
        logs.add(0, "📅 正在生成近 7 天小结…")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = WeeklyReportBuilder.build(context)
                if (input.count == 0) {
                    withContext(Dispatchers.Main) {
                        logs.add(0, "ℹ️ 近 7 天暂无账单：先消费/抓取一次账单，再来生成周报")
                    }
                } else {
                    AIService.generateWeeklyReport(input)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logs.add(0, "❌ 周报生成失败: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) { isWeeklyLoading = false }
            }
        }
    }

    // ================= liban 5-Tab 外壳（移植 liban-main ui） =================
    // 布局：首页 / 社区 / +(中央,拦截为支付干预弹窗) / 记录 / 我的
    //   · 首页 = RationalHomeTab（真实数据仪表盘：金额/理性指数/预算全读 FinanceDb+BudgetStore）
    //   · 社区 = liban CommunityScreen（演示数据）
    //   · 记录 = BillsTabColumn（真实账单区：抓取×3/手动补记/编辑/删除/去重提示）+ 页尾识屏决策&AIA建议
    //   · 我的 = liban ProfileScreen 行入口 → 真实二级页（预算/储蓄目标/系统设置/识屏助手/数据权限/会员）
    val c = libanColors()
    var stackPage by remember { mutableStateOf<LibanStackPage?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    BackHandler(enabled = stackPage != null) { stackPage = null }

    // 记录页页尾真实数据（识屏决策来自 agent.db；AI 建议来自 adviceFlow 会话列表）
    val agentDecisions by AgentGraph.repository.decisions.collectAsState(initial = emptyList())
    val footerForBills: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (aiAdvice.isNotEmpty()) {
                SectionCard(title = "💡 AI 建议历史") {
                    aiAdvice.take(5).forEach { a ->
                        Row(Modifier.padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
                            Text("•", style = MaterialTheme.typography.bodySmall, color = libanColors().textTertiary)
                            Text(a.advice,
                                Modifier.padding(start = Spacing.xs).weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = libanColors().textPrimary)
                            Text(a.source, style = MaterialTheme.typography.labelSmall,
                                color = if (a.source == AIAdvice.CLOUD_SOURCE) libanColors().primaryDeep else libanColors().textTertiary)
                        }
                    }
                }
            }
            if (agentDecisions.isNotEmpty()) {
                SectionCard(title = "🧭 识屏决策历史", actionText = "去识屏助手", onAction = { stackPage = LibanStackPage.CONSULT }) {
                    agentDecisions.take(6).forEach { d ->
                        Row(Modifier.padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Text("🛍️", style = MaterialTheme.typography.titleSmall)
                            Column(Modifier.weight(1f).padding(start = Spacing.s)) {
                                Text("${d.productName}  ¥${d.priceCents / 100.0}",
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${d.recommendation} · ${d.riskLevel} · ${date(d.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall, color = libanColors().textSecondary)
                            }
                        }
                    }
                }
            }
            if (aiAdvice.isEmpty() && agentDecisions.isEmpty()) {
                Text("暂无识屏决策与 AI 建议：消费/抓取账单或去「识屏助手」分析后会出现。",
                    style = MaterialTheme.typography.labelSmall, color = libanColors().textSecondary)
            }
        }
    }

    Scaffold(
        containerColor = c.background,
        bottomBar = {
            if (stackPage == null) {
                LibanBottomBar(
                    items = listOf(
                        BottomBarItem(MainTab.HOME.label, Icons.Default.Home),
                        BottomBarItem(MainTab.COMMUNITY.label, Icons.Default.Face),
                        BottomBarItem(MainTab.CENTER.label, Icons.Default.Add, isCenter = true),
                        BottomBarItem(MainTab.RECORD.label, Icons.Default.List),
                        BottomBarItem(MainTab.MINE.label, Icons.Default.Person),
                    ),
                    selectedIndex = selectedTab,
                    onSelect = { index ->
                        if (index == MainTab.CENTER.ordinal) {
                            showSheet = true          // 中央 +：支付干预弹窗（真实预算口径）
                        } else {
                            selectedTab = index
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                MainTab.HOME.ordinal -> RationalHomeTab(padding, onDiagnose = ::generateWeekly, onReport = ::generateWeekly)
                MainTab.COMMUNITY.ordinal -> CommunityScreen()
                MainTab.RECORD.ordinal -> BillsTabColumn(
                    padding, logs, a11yEnabled,
                    onFetchAlipay = {
                        settings.pendingBillFetch = false
                        AccessibilityEventRepository.postAlipayBillFetchRequest()
                        logs.add(0, "📋 已请求：自动打开支付宝 → 进入账单 → 翻页抓取")
                    },
                    onFetchMeituan = {
                        AccessibilityEventRepository.postMeituanBillFetchRequest()
                        logs.add(0, "📋 已请求：自动打开美团 → 尝试进入账单/明细页并翻页抓取")
                    },
                    onFetchTaobao = {
                        AccessibilityEventRepository.postTaobaoBillFetchRequest()
                        logs.add(0, "📋 已请求：自动打开淘宝闪购 → 尝试进入订单/账单页并翻页抓取")
                    },
                    footer = footerForBills,
                )
                MainTab.MINE.ordinal -> ProfileScreen(
                    onOpenDataPrivacy = { stackPage = LibanStackPage.DATA_PRIVACY },
                    onOpenMembership = { stackPage = LibanStackPage.MEMBERSHIP },
                    onOpenBudget = { stackPage = LibanStackPage.BUDGET },
                    onOpenGoal = { stackPage = LibanStackPage.GOAL },
                    onOpenSettings = { stackPage = LibanStackPage.SETTINGS },
                    onOpenConsult = { stackPage = LibanStackPage.CONSULT },
                )
                else -> Unit // CENTER 不可达：中央按钮被拦截为弹窗
            }
        }
    }

    // 二级页（全屏覆盖底栏）
    stackPage?.let { page ->
        Surface(Modifier.fillMaxSize(), color = c.background) {
            when (page) {
                LibanStackPage.EXPLAINABILITY -> ExplainabilityScreen(onBack = { stackPage = null })
                LibanStackPage.DATA_PRIVACY -> DataPrivacyScreen(onBack = { stackPage = null })
                LibanStackPage.MEMBERSHIP -> MembershipScreen(onBack = { stackPage = null })
                LibanStackPage.BUDGET -> BudgetRealScreen(onBack = { stackPage = null })
                LibanStackPage.GOAL -> GoalRealScreen(onBack = { stackPage = null })
                LibanStackPage.SETTINGS -> Column(Modifier.fillMaxSize()) {
                    // 系统与账单设置：沿用原「我的」真实内容（无障碍/DeepSeek/预算/抓取/CSV/清空/来源清理）
                    PageHeader("系统与账单设置", onBack = { stackPage = null })
                    Box(Modifier.weight(1f)) {
                        MineTabColumn(
                            padding = PaddingValues(0.dp),
                        settings = settings,
                        a11yEnabled = a11yEnabled,
                        autoExecute = autoExecute,
                        judgeEnabled = judgeEnabled,
                        apiKeyInput = apiKeyInput,
                        modelInput = modelInput,
                        dsStatus = dsStatus,
                        exportPath = exportPath,
                        onOpenA11y = {
                            logs.add(0, "👉 正在打开系统无障碍设置…请开启 Finance 服务后返回")
                            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        },
                        onApiKeyChange = { apiKeyInput = it },
                        onModelChange = { modelInput = it },
                        onSaveDeepSeek = {
                            settings.saveDeepSeek(apiKeyInput, modelInput, "")
                            ModelAPIClient.updateConfig(apiKeyInput, modelInput, null)
                            dsStatus = if (ModelAPIClient.isConfigured()) {
                                "已配置（模型：${modelInput.ifBlank { BuildConfig.DEEPSEEK_MODEL }}）"
                            } else "未配置"
                            logs.add(0, if (ModelAPIClient.isConfigured()) {
                                "⚙️ DeepSeek 已配置，模型：${modelInput.ifBlank { BuildConfig.DEEPSEEK_MODEL }}"
                            } else "⚠️ API Key 为空：AI 将使用端侧规则")
                        },
                        onAutoExecuteChange = {
                            autoExecute = it
                            settings.autoExecuteAiSearch = it
                            logs.add(0, if (it) "⚙️ 已开启自动执行（推荐后直接打开美团）" else "⚙️ 已关闭自动执行（人工确认）")
                        },
                        onJudgeEnabledChange = {
                            judgeEnabled = it
                            settings.judgeBeforeOrderEnabled = it
                            logs.add(0, if (it) "⚙️ 已开启下单前判断（结算页守卫）" else "⚙️ 已关闭下单前判断")
                        },
                        onClearAll = {
                            MerchantStore.clear()
                            merchantCount = 0
                            aiAdvice.clear()
                            logs.clear()
                            logs.add(0, "🧹 已清空本机记录（商家库 / 日志 / 建议）")
                        },
                        onExport = {
                            CoroutineScope(Dispatchers.IO).launch {
                                val db = FinanceDb.get(context)
                                val rows = db.billDao().all()
                                val path = writeBillsCsv(context, rows)
                                withContext(Dispatchers.Main) {
                                    exportPath = path
                                    logs.add(0, "📤 已导出账单 CSV：$path")
                                }
                            }
                        },
                        onClearDb = {
                            CoroutineScope(Dispatchers.IO).launch {
                                FinanceDb.get(context).billDao().clearAll()
                                withContext(Dispatchers.Main) { logs.add(0, "🗑️ 已清空本地账单库") }
                            }
                        },
                        onCleanSource = { src ->
                            CoroutineScope(Dispatchers.IO).launch {
                                FinanceDb.get(context).billDao().deleteBySource(src)
                                withContext(Dispatchers.Main) { logs.add(0, "🧹 已清空来源=「$src」的全部账单") }
                            }
                        },
                    )
                    }
                }
                LibanStackPage.CONSULT -> Column(Modifier.fillMaxSize()) {
                    // AI 咨询中心（真实：识屏助手/每周小结/Top3 —— 原「咨询 Tab」全部功能）
                    PageHeader("AI 咨询中心", onBack = { stackPage = null })
                    Box(Modifier.weight(1f)) {
                    ConsultTab(
                        padding = PaddingValues(0.dp),
                        aiAdvice = aiAdvice,
                        currentNeeds = currentNeeds,
                        isRecommendLoading = isRecommendLoading,
                        isWeeklyLoading = isWeeklyLoading,
                        a11yEnabled = a11yEnabled,
                        autoExecute = autoExecute,
                        topPicks = topPicks,
                        merchantCount = merchantCount,
                        onNeedsChange = { currentNeeds = it },
                        onGenerateWeekly = ::generateWeekly,
                        onRecommendTop3 = {
                            isRecommendLoading = true
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val candidates = MerchantStore.topCandidates(20)
                                    val picks = if (candidates.isEmpty()) {
                                        listOf(DemoProfile.favoriteRestaurants.firstOrNull().orEmpty())
                                            .filter { it.isNotBlank() }
                                    } else {
                                        AIService.recommendTop3(currentNeeds, candidates)
                                            .ifEmpty { candidates.take(3) }
                                    }
                                    withContext(Dispatchers.Main) {
                                        topPicks = picks
                                        if (autoExecute && picks.isNotEmpty()) executePicked(picks.first())
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { logs.add(0, "❌ AI 推荐失败: ${e.message}") }
                                } finally {
                                    withContext(Dispatchers.Main) { isRecommendLoading = false }
                                }
                            }
                        },
                        onPick = ::executePicked,
                    )
                    }
                }
            }
        }
    }

    // 支付临界点干预弹窗（屏2；预算剩余 = 真实月预算口径）
    if (showSheet) {
        val monthly = BudgetStore.monthlyBudget()
        val dao = FinanceDb.get(context).billDao()
        val (mf, mt) = monthRange()
        val monthSpentState by dao.sumBetween(mf, mt).collectAsState(initial = 0.0)
        val remainingPct = if (monthly > 0) {
            ((monthly - monthSpentState) / monthly * 100).toInt().coerceIn(0, 100)
        } else 23
        InterventionSheet(
            data = InterventionDetail(
                remainingPercent = remainingPct,
                usedAmount = "¥%,.0f".format(monthSpentState),
                budgetAmount = "¥%,.0f".format(monthly),
                rows = listOf(
                    InterventionRow("本次支付", "¥329.00 · 电商平台"),
                    InterventionRow("支付后本月总计", "¥%,.0f".format(monthSpentState + 329.0)),
                    InterventionRow("本月预算", "¥%,.0f".format(monthly)),
                    InterventionRow("支付后超支概率", "89% · 触发预警"),
                ),
            ),
            onDismiss = { showSheet = false },
            onAdopt = { showSheet = false },
            onPayAnyway = { showSheet = false },
            onWhy = {
                showSheet = false
                stackPage = LibanStackPage.EXPLAINABILITY
            },
        )
    }
}


// ============ 咨询 Tab（AI 建议 / 每周小结 / Top3 推荐） ============
@Composable
private fun ConsultTab(
    padding: androidx.compose.foundation.layout.PaddingValues,
    aiAdvice: SnapshotStateList<AIAdvice>,
    currentNeeds: String,
    isRecommendLoading: Boolean,
    isWeeklyLoading: Boolean,
    a11yEnabled: Boolean,
    autoExecute: Boolean,
    topPicks: List<String>,
    merchantCount: Int,
    onNeedsChange: (String) -> Unit,
    onGenerateWeekly: () -> Unit,
    onRecommendTop3: () -> Unit,
    onPick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部标题由外壳 PageHeader 呈现（liban 风格二级页），此处不再重复
        // 识屏助手（AI 购物决策：录屏/OCR/手动分析/最近决策）
        AgentPanel()

        // AI 建议
        aiAdvice.firstOrNull()?.let { advice ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💡", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(advice.advice,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("推理来源：${advice.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } ?: Card(modifier = Modifier.fillMaxWidth()) {
            Text("暂无建议：消费一笔或生成一次小结后，这里会给出 AI 解读。",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 每周 AI 小结
        SectionTitle("每周 AI 小结 · 近 7 天")
        Button(onClick = onGenerateWeekly, enabled = !isWeeklyLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (isWeeklyLoading) "⏳ 生成中…（约几秒）" else "📅 生成本周小结")
        }
        Text("内容：总支出/日均、分类与渠道分布、高频去向、与上个 7 天环比、预算执行与下周建议。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // AI 外卖推荐
        SectionTitle("AI 外卖推荐 · 最想吃 Top3")
        TextField(
            enabled = a11yEnabled,
            value = currentNeeds,
            onValueChange = onNeedsChange,
            label = { Text("输入当前需求（例如：想吃辣的、预算30元内）") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onRecommendTop3,
            modifier = Modifier.fillMaxWidth(),
            enabled = a11yEnabled && !isRecommendLoading && currentNeeds.isNotEmpty()
        ) { Text(if (isRecommendLoading) "思考中..." else "从本地账单对比推荐 Top3") }

        if (topPicks.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("💡 结合你的历史账单，现在最想吃：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    topPicks.take(3).forEachIndexed { idx, name ->
                        OutlinedButton(onClick = { onPick(name) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${idx + 1}. $name  → 去美团搜索")
                        }
                    }
                    if (autoExecute) {
                        Text("已开启自动执行：将直接打开美团搜索第 1 名",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🏪 本地商家库：$merchantCount 家",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("抓取美团/淘宝/支付宝后自动积累",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============ 我的 Tab ============
@Composable
private fun MineTabColumn(
    padding: androidx.compose.foundation.layout.PaddingValues,
    settings: UserSettings,
    a11yEnabled: Boolean,
    autoExecute: Boolean,
    judgeEnabled: Boolean,
    apiKeyInput: String,
    modelInput: String,
    dsStatus: String,
    exportPath: String,
    onOpenA11y: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSaveDeepSeek: () -> Unit,
    onAutoExecuteChange: (Boolean) -> Unit,
    onJudgeEnabledChange: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
    onClearDb: () -> Unit,
    onCleanSource: (String) -> Unit
) {
    var cleanSourceDialog by remember { mutableStateOf(false) }
    var confirmClean by remember { mutableStateOf<String?>(null) }

    // 预算设置输入
    var monthlyInput by remember { mutableStateOf(fmtPlain(BudgetStore.monthlyBudget())) }
    var catInputs by remember { mutableStateOf(HashMap(BudgetStore.catBudgets())) }
    var fetchLimitInput by remember { mutableStateOf(settings.fetchBillLimit.toString()) }
    val mineCtx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 无障碍门控
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (a11yEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (a11yEnabled) "✅ 无障碍服务：已开启" else "🔒 无障碍服务：未开启",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (a11yEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
                Text(
                    text = if (a11yEnabled) {
                        "所有功能已解锁：去「账单」页抓取平台账单，「首页」可对比历史账单推荐 Top3。"
                    } else {
                        "请开启 Finance 无障碍服务，开启后其余功能才会解锁（返回本应用后自动生效）。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a11yEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
                if (!a11yEnabled) {
                    Button(onClick = onOpenA11y, modifier = Modifier.fillMaxWidth()) {
                        Text("开启无障碍服务")
                    }
                }
            }
        }

        // 无障碍体检（#7：事件/窗口树诊断 + 修复引导）
        A11yDiagnoseCard(a11yEnabled = a11yEnabled, onOpenA11y = onOpenA11y)

        // 悬浮窗样式（顶部灵动胶囊）
        val islandOn = remember { mutableStateOf(settings.islandEnabled) }
        val islandSec = remember { mutableStateOf(settings.islandSeconds) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("顶部灵动胶囊提醒", style = MaterialTheme.typography.bodyMedium)
                        Text("消费/下单判断/AI 点评/抓取完成 → 顶部胶囊滑入淡出；关 = 传统侧边黄框",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = islandOn.value,
                        onCheckedChange = {
                            islandOn.value = it
                            settings.islandEnabled = it
                        }
                    )
                }
                if (islandOn.value) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3, 5, 8).forEach { s ->
                            FilterChip(
                                selected = islandSec.value == s,
                                onClick = {
                                    islandSec.value = s
                                    settings.islandSeconds = s
                                },
                                label = { Text("停留 ${s} 秒") }
                            )
                        }
                    }
                }
            }
        }

        // 执行层开关
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI 推荐后自动打开美团", style = MaterialTheme.typography.bodyMedium)
                    Text("默认关闭：结果先给你看，由你决定是否执行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    enabled = a11yEnabled,
                    checked = autoExecute,
                    onCheckedChange = onAutoExecuteChange
                )
            }
        }

        // 下单前判断开关（结算页守卫悬浮窗，默认开）
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("下单前 AI 判断（结算页守卫）", style = MaterialTheme.typography.bodyMedium)
                    Text("美团/淘宝结算页自动弹出「合适/谨慎/不建议」（含剩余预算）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    enabled = a11yEnabled,
                    checked = judgeEnabled,
                    onCheckedChange = onJudgeEnabledChange
                )
            }
        }

        // DeepSeek 云设置
        SectionTitle("DeepSeek 云设置")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key（sk-…）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = onModelChange,
                        label = { Text("模型（如 deepseek-chat）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onSaveDeepSeek) { Text("保存") }
                }
                Text(
                    text = "状态：$dsStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 预算设置
        SectionTitle("预算设置")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = monthlyInput,
                    onValueChange = { monthlyInput = it },
                    label = { Text("本月总预算（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("分类预算（留空 = 该分类不限）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BillCategories.PRESETS.forEach { c ->
                    val cur = catInputs[c]
                    OutlinedTextField(
                        value = cur?.let(::fmtPlain) ?: "",
                        onValueChange = { v ->
                            catInputs = HashMap(catInputs).apply {
                                if (v.isBlank()) remove(c)
                                else {
                                    val num = v.toDoubleOrNull()
                                    if (num != null && num > 0.0) put(c, num)
                                }
                            }
                        },
                        label = { Text("$c（元）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = {
                        val mv = monthlyInput.trim().toDoubleOrNull()
                        if (mv == null || mv < 0.0) {
                            android.widget.Toast.makeText(
                                mineCtx, "月预算需为 ≥ 0 的数字", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            BudgetStore.setMonthlyBudget(mv)
                            BudgetStore.setCatBudgets(catInputs)
                            android.widget.Toast.makeText(
                                mineCtx, "✅ 预算已保存", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存预算") }
                TextButton(onClick = {
                    BudgetStore.resetDemo()
                    monthlyInput = fmtPlain(BudgetStore.monthlyBudget())
                    catInputs = HashMap(BudgetStore.catBudgets())
                    android.widget.Toast.makeText(
                        mineCtx, "已恢复演示预算", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text("恢复演示预算", style = MaterialTheme.typography.labelMedium)
                }
                Text("月/分类预算用于：首页预算进度条、实时 AI 点评、悬浮窗消费提醒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }

        // 抓取设置
        SectionTitle("抓取设置")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = fetchLimitInput,
                    onValueChange = { fetchLimitInput = it },
                    label = { Text("单次抓取条数上限（10~500）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val v = fetchLimitInput.trim().toIntOrNull()
                        if (v == null || v !in 10..500) {
                            android.widget.Toast.makeText(
                                mineCtx, "请输入 10~500 之间的整数", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            settings.fetchBillLimit = v
                            fetchLimitInput = settings.fetchBillLimit.toString()
                            android.widget.Toast.makeText(
                                mineCtx, "✅ 已保存：单次最多抓取 $v 条", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存抓取条数") }
                Text("支付宝 / 美团 / 淘宝闪购 共用；条数越多滚动屏数越多、耗时越久，匹配支付宝推时间也更全。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }

        // 数据与隐私
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🔒 数据仅存本机，不上传",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClearAll) {
                Text("清空本机记录", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 账单数据
        SectionTitle("账单数据")
        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth()
        ) { Text("📤 导出全部账单 CSV") }
        if (exportPath.isNotBlank()) {
            Text(
                text = "已导出：\n$exportPath",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { shareCsvFile(mineCtx, exportPath) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("分享 CSV（系统分享到微信/网盘…）") }
        }
        TextButton(onClick = onClearDb) {
            Text("清空本地账单库", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { cleanSourceDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🧹 清理某来源的全部账单（误抓整批时用）") }
        Text("按来源单独清空，不影响其它数据；误抓整批可先清掉再重抓。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 开发者
        Text("理伴 v${BuildConfig.VERSION_NAME} · 仅供演示",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // ---------- 按来源清理弹窗 ----------
    if (cleanSourceDialog) {
        AlertDialog(
            onDismissRequest = { cleanSourceDialog = false },
            title = { Text("清理某来源的全部账单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BillSources.PRESETS.forEach { s ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(s, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                confirmClean = s
                                cleanSourceDialog = false
                            }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Text("删除所选来源的全部账单，不可恢复。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { cleanSourceDialog = false }) { Text("取消") }
            }
        )
    }
    confirmClean?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmClean = null },
            title = { Text("确认清空「$s」？") },
            text = { Text("将删除来源为「$s」的全部账单，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val src = s
                    confirmClean = null
                    onCleanSource(src)
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClean = null }) { Text("取消") }
            }
        )
    }
}

/** 检测 Finance 无障碍服务是否已开启。
 * 兼容系统里的两种写法：短名 "com.example.finance/.service.X"（settings put 写入）与
 * 全限定 "com.example.finance/com.example.finance.service.X"（小米等系统在设置页开关后写入），
 * 统一用 ComponentName 归一化后比较。 */
// ============ #7 无障碍体检（诊断 + 修复引导） ============
@Composable
private fun A11yDiagnoseCard(a11yEnabled: Boolean, onOpenA11y: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { UserSettings(context) }
    val overlayOk = Settings.canDrawOverlays(context)
    val listeners = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    )?.contains("PaymentNotificationListener") == true
    val lastEvent = settings.a11yLastEventAt
    val now = System.currentTimeMillis()
    val eventFlowing = a11yEnabled && lastEvent > 0 && now - lastEvent < 3 * 60_000L
    val windowOk = settings.a11yWindowOk

    @Composable
    fun row(label: String, ok: Boolean, detail: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (ok) "✅" else "⚠️", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Text("$label：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🔧 无障碍体检（自动诊断）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            row("服务", a11yEnabled, if (a11yEnabled) "已开启" else "未开启")
            row("事件流动", eventFlowing,
                if (eventFlowing) "正常（最近 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastEvent))}）"
                else if (lastEvent == 0L) "尚无事件（刚开启?）" else "已停滞（疑似假 Bound/冻结）")
            row("窗口读取", windowOk, if (windowOk) "正常" else "异常（rootInActiveWindow 为空）")
            row("悬浮窗", overlayOk, if (overlayOk) "已授权" else "未授权")
            row("通知监听", listeners, if (listeners) "已授权" else "未授权")
            if (a11yEnabled && (!windowOk || !eventFlowing)) {
                Text("可能命中 MIUI「崩溃名单/冻结」：请在系统设置里把本服务 关闭→重新开启 一次；仍无效请重启手机（完整仪式见 DEV_STATUS 坑1/坑1b）。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenA11y, modifier = Modifier.weight(1f)) { Text("去无障碍设置") }
                TextButton(onClick = {
                    val diag = buildString {
                        append("理伴无障碍诊断 ")
                        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)))
                        append("\n服务=").append(a11yEnabled).append(" 事件流动=").append(eventFlowing)
                        append(" 窗口读取=").append(windowOk).append(" 悬浮窗=").append(overlayOk).append(" 通知监听=").append(listeners)
                    }
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("诊断", diag))
                    android.widget.Toast.makeText(context, "诊断已复制，可直接粘贴发给开发者", android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("复制诊断") }
            }
        }
    }
}


private fun shareCsvFile(context: Context, path: String) {
    try {
        val file = java.io.File(path)
        if (!file.exists()) {
            android.widget.Toast.makeText(context, "CSV 不存在，请先导出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享账单 CSV"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "分享失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun isFinanceAccessibilityEnabled(context: Context): Boolean {
    return runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.split(':').any { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@any false
            val c = android.content.ComponentName.unflattenFromString(entry)
            c != null && c.packageName == "com.example.finance" &&
                    c.className == "com.example.finance.service.FinanceAccessibilityService"
        }
    }.getOrDefault(false)
}

/** 区块标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

/** 本月剩余天数（含今天），预算"日均可用"用 */
private fun daysLeftThisMonth(): Int {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val total = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    return (total - today + 1).coerceAtLeast(1)
}

/** 输入框用的金额显示：整数不带小数，否则保留 2 位 */
private fun fmtPlain(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else "%.2f".format(v).trimEnd('0').trimEnd('.')

/** 把本地账单库导出为 CSV，返回文件绝对路径 */
private fun writeBillsCsv(context: Context, rows: List<BillEntity>): String {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = java.io.File(dir, "bills-${System.currentTimeMillis()}.csv")
    val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    val sb = StringBuilder("来源,商家,金额(元),日期,时间,备注\n")
    for (r in rows) {
        val d = java.util.Date(r.occurredAtMs)
        sb.append(r.source).append(',')
            .append(r.merchant.replace(",", " ")).append(',')
            .append("%.2f".format(r.amount)).append(',')
            .append(dateFmt.format(d)).append(',')
            .append(timeFmt.format(d)).append(',')
            .append(r.note.replace(",", " ")).append('\n')
    }
    file.writeText(sb.toString(), Charsets.UTF_8)
    return file.absolutePath
}

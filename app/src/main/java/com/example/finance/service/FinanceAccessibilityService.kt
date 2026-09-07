package com.example.finance.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.finance.data.AccessibilityEventRepository
import com.example.finance.data.BillCategories
import com.example.finance.data.BillEntity
import com.example.finance.data.BudgetPlanner
import com.example.finance.data.ConsumptionRecord
import com.example.finance.data.FinanceDb
import com.example.finance.data.UserSettings
import com.example.finance.ai.AIService
import com.example.finance.parsing.BillAmountText
import com.example.finance.parsing.BillKeys
import com.example.finance.parsing.BillTimeParser
import com.example.finance.parsing.MerchantText
import com.example.finance.utils.FloatingWindowManager // 新增导入
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FinanceAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "FinanceAccessibility"

        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
        const val PKG_MEITUAN = "com.sankuai.meituan"
        const val PKG_TAOBAO = "com.taobao.taobao"
        const val PKG_ELE = "me.ele"

 /** 强下CTA：只在结确认/收银页出现的词（商品详情/信息店铺菜单页不会有），命中即判
 * 含美团结算页实测按钮「极速支付」（点它即提支付，该页无"提交订单"字样）
 * 注意：「去结算」不在强词——它同时出现在【店铺选菜页底栏】和购物车栏，需配合合计+非菜单页判定*/
        val ORDER_CTA_STRONG = arrayOf(
"确认订单", "提交订单", "确认下单", "确认支付", "确认付款", "确认并支付", "支付订单",
"去支付", "立即支付", "极速支付"
        )

 /** 弱下单词：需合计/应付/地址"等结算信息同时出现才可信
 * 商品详情CTA（立即购马上购买/一键购立即下单…）与购物车/菜单页底
 * 「去结算」也放这里——详情页没有合计 不成立；菜单页有合计但带 feed 特征 被拦截*/
        val ORDER_CTA_WEAK = arrayOf(
"支付", "下单", "购买", "结算", "买单", "付款", "去结算",
"立即购买", "马上购买", "立即下单", "一键购买", "去买单"
        )

 /** 合计/应付/结算信息标签（含美团结算金额区文案：券前/共减*/
        val SUM_LABELS = arrayOf(
"合计", "应付", "需付", "实付", "小计", "总价", "共需", "共减", "券前", "收货", "地址"
        )

 /** 账单抓取：最多滚动页数（每屏6~10 条） */
        const val MAX_BILL_SCREENS = 15

 /** 下单前判断：text/desc 各最多采集的 token 数（结算页底CTA 不能被顶部条目挤掉；15000*/
        const val JUDGE_TOKEN_CAP = 300

 /** 行内金额样式¥12.00 / ¥12.00 / -12.00 / +¥88.50（规则本体见 parsing/BillAmountText.kt） */
        private val BILL_AMOUNT_REGEX = BillAmountText.AMOUNT_LINE_REGEX
    }

    private val processedRecords = mutableSetOf<String>()
    private var lastProcessedTime = 0L
    private val THROTTLE_MS = 2000L
    private var a11yNonPayLog = 0L // 非付款页日志抽样计数（降噪）

 // 下单AI 判断（预支付守卫）状
    private var lastJudgeAt = 0L
    private var lastJudgeKey = ""
    private var judgeMissLogAt = 0L // "接近但未调试日志采样点（20s 一条，防刷屏）

    // 灵动胶囊联动：最近一次"实时消费提醒"时刻；其后的 AI 点评自动上胶囊
    private var lastRealtimeRemindAt = 0L
    private var adviceIslandJob: Job? = null

    private var eventListenerJob: Job? = null

 // 淘宝账单时间推断（跨屏共享状态）：用支付本地已入账单(金额+店名)匹配真实支付时间
 // 匹配不到时用"最近一个匹配锚作为上下文推断
 // 淘宝账单时间推断（跨屏共享状态）：用支付本地已入账单(金额+店名)匹配真实支付时间
    // 匹配不到时，仅用"同一家店"最近匹配过的时间做上下文推断（不同店不串日期）
    private var tbIndexBills: List<BillEntity> = emptyList()
    private var tbMatchedByCore: MutableMap<String, Long> = HashMap()
    private var tbFetchNowMs: Long = 0L

 /** 单次抓取条数上限（在 App「我的→抓取设置」里可调，默认为 100*/
    private var billLimit: Int = 100
    private var billFetchJob: Job? = null
    private var billFetchWatchdog: Job? = null
    private var fetchLastTick = 0L // 抓取心跳时间戳：看门狗据此判断引擎是否停
    private var isBillFetching = false
    private lateinit var floatingWindowManager: FloatingWindowManager // 改为非空类型

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")
        UserSettings(this).a11yServiceConnected = true // UI 门控信号
 // 初始化悬浮窗管理
        floatingWindowManager = FloatingWindowManager(this)
        startEventListener()
        postUILog("♿ 无障碍服务已连接，可开始抓取账单")
        startWindowHealthProbe() // #7 无障碍健康自检（诊断用）
        startAdviceIslandListener() // AI 点评 → 灵动胶囊
 // 处理"点了抓取但服务当时未连接"的待办请求（避免点了没反应）
 // 注意：只有在【本应用处于前台】时才立即执—若此刻在系统设置页，
 // 后台拉起支付宝会被小鸿蒙拦截，此时保留标记，等用户回到本应用onResume 触发
        val settings = UserSettings(this)
        if (settings.pendingBillFetch) {
            if (isOwnAppInForeground()) {
                settings.pendingBillFetch = false
                postUILog("📋 检测到之前排队的抓取请求，自动开始…")
                startAlipayBillFetch()
            } else {
                postUILog("⏳ 检测到排队抓取请求：回到本应用后将自动开始")
            }
        }
    }

 /** 当前活跃窗口是否就是我们自己App（前台拉起其它应用才不会被系统拦截） */
    private fun isOwnAppInForeground(): Boolean {
        return runCatching { rootInActiveWindow?.packageName == packageName }.getOrDefault(false)
    }

    /** 实时消费提醒后 15s 内到达的 AI 点评 → 顶部灵动胶囊（其余时刻不打扰） */
    private fun startAdviceIslandListener() {
        adviceIslandJob = CoroutineScope(Dispatchers.IO).launch {
            AIService.adviceFlow.collect { advice ->
                val now = System.currentTimeMillis()
                if (now - lastRealtimeRemindAt <= 15_000L) {
                    withContext(Dispatchers.Main) {
                        runCatching {
                            floatingWindowManager.showStatus("🤖 AI 点评：${advice.advice}")
                        }
                    }
                }
            }
        }
    }

    private fun startWindowHealthProbe() {        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (pm.isInteractive) {
                        val ok = runCatching { rootInActiveWindow != null }.getOrDefault(false)
                        UserSettings(this@FinanceAccessibilityService).a11yWindowOk = ok
                    }
                } catch (_: Exception) {
                }
                h.postDelayed(this, 20_000L)
            }
        }
        h.postDelayed(runnable, 5_000L)
    }

    private fun startEventListener() {
        eventListenerJob = CoroutineScope(Dispatchers.IO).launch {
            AccessibilityEventRepository.events.collect { event ->
                when {
                    event.startsWith(AccessibilityEventRepository.PREFIX_AI_RECOMMENDATION) -> {
                        val restaurantName =
                            event.substring(AccessibilityEventRepository.PREFIX_AI_RECOMMENDATION.length).trim()
                        Log.d(TAG, "🎯 收到AI推荐餐厅: '$restaurantName'")
                        performAIRecommendedSearch(restaurantName)
                    }

                    event == AccessibilityEventRepository.PREFIX_FETCH_ALIPAY_BILLS -> {
                        Log.d(TAG, "📋 收到抓取支付宝账单指令")
                        startAlipayBillFetch()
                    }

                    event == AccessibilityEventRepository.PREFIX_FETCH_MEITUAN_BILLS -> {
                        Log.d(TAG, "📋 收到抓取美团账单指令")
                        startExternalBillFetch(
                            source = "美团账单",
                            pkg = PKG_MEITUAN,
                            primaryLabels = listOf("我的"),
                            // 我的订单优先：美团「我的」页有整条「我的订单」入口；点入即到订单列表（含全部/待付款页签）
                            billLabels = listOf("我的订单", "全部订单", "订单")
                        )
                    }

                    event == AccessibilityEventRepository.PREFIX_FETCH_TAOBAO_BILLS -> {
                        Log.d(TAG, "📋 收到抓取淘宝闪购账单指令")
                        startExternalBillFetch(
                            source = "淘宝闪购",
                            pkg = PKG_TAOBAO,
 // 首页底部 Tab content-desc「我的淘宝」→ 文字/desc 点击优先
                            primaryLabels = listOf("我的淘宝"),
 // 进入订单列表：先点「我的订单」整条入最，失败再逐个试状态快捷卡
                            billLabels = listOf("我的订单", "待付款", "待发货", "待收货"),
                            postLabels = listOf("闪购"),
                            bottomTabTap = 0.90f to 0.969f // 坐标兜底（desc 点击失败时）
                        )
                    }

                    event == AccessibilityEventRepository.PREFIX_STOP_BILL_FETCH -> {
                        Log.d(TAG, "🛑 收到停止抓取指令")
                        requestStopFetch()
                    }

                    event == AccessibilityEventRepository.PREFIX_DUMP_SCREEN -> {
                        Log.d(TAG, "🖼 收到 dump 屏幕指令")
                        dumpActiveScreen()
                    }
                }
            }
        }
    }

 /** 向主界面推送一条提示（经事件总线展示在系统日志区*/
    private fun postUILog(msg: String) {
        AccessibilityEventRepository.postMessage(msg)
        Log.d(TAG, msg)
    }

    // ======================
 // 📋 支付宝历史账单自动抓
 // 流程：确保支付宝在前进入「账单」页 逐屏解析"金额+商家"并滚动，直到无新记录
 // 说明：同一 商家+金额 只记一次（去重，演示期简化，避免滚动重复）
    // ======================

    private fun startAlipayBillFetch() {
 // 无论从哪个入口触发，都先消费"排队标记"，避免重遗留触发
        UserSettings(this).pendingBillFetch = false
        if (isBillFetching) {
            postUILog("⏳ 抓取账单进行中，请稍候")
            return
        }
        isBillFetching = true
        billLimit = UserSettings(this).fetchBillLimit
        billFetchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                postUILog("📋 开始抓取支付宝历史账单…")
                beginFetchUI("① 正在打开支付宝…（点悬浮窗可停止）")
                fetchLastTick = System.currentTimeMillis()
                startFetchWatchdog()
                if (!openAlipay()) {
                    postUILog("⚠️ 未检测到支付宝，请先安装并登录支付宝")
                    return@launch
                }
                postUILog("🚀 已打开支付宝，等待页面加载…")
                updateFetchUI("① 已打开支付宝，等待页面…")
 // 前台确认：超时不再放弃，改为继续尝试进入账单（避打开后没反应"
                if (!waitForAlipayForeground(15)) {
                    postUILog("⏳ 支付宝前台确认超时，仍继续尝试进入账单页…")
                }
                delay(1200)

                if (!navigateToBillPage()) {
                    postUILog("❌ 未能进入「账单」页（可能未登录/页面结构变化）。已打印当前屏幕文本供排查。")
                    debugPrintCurrentScreen()
                    return@launch
                }
                postUILog("✅ 已进入账单页，开始逐屏识别并滚动…")
                updateFetchUI("② 已进入账单页，逐屏解析中…")

                val seen = HashSet<String>()
                var totalAdded = 0
                val agg = ExternalAgg()
                var noNewStreak = 0
                var firstScreenChecked = false
                var platformLeft = false
                for (screen in 1..MAX_BILL_SCREENS) {
                    fetchLastTick = System.currentTimeMillis()
                    delay(1400)
 // 用户切回理伴/切去其它应用：立即停止，绝不解析、点击非目标窗口（防卡死/假账
                    if (!ensureStillOnPlatform(PKG_ALIPAY)) {
                        platformLeft = true
                        break
                    }
                    val newCount = parseOneScreen(seen, agg, isFirst = !firstScreenChecked)
                    firstScreenChecked = true
                    totalAdded += newCount
                    postUILog("📄 第 $screen 屏：新识别 $newCount 笔，累计识别 ${seen.size} 条")
                    if (seen.size >= billLimit) {
                        postUILog("⏹ 已达 $billLimit 条上限，停止")
                        break
                    }
                    if (screen % 3 == 0 || newCount > 0) {
                        updateFetchUI("解析中：第 $screen 屏 · 已识别 ${seen.size} 条…（可停止）")
                    }
                    if (newCount == 0) {
                        noNewStreak++
                        if (noNewStreak >= 2) {
                            postUILog("⏹ 连续两屏没有新记录，停止滚动")
                            break
                        }
                    } else {
                        noNewStreak = 0
                    }
                    if (screen < MAX_BILL_SCREENS) {
                        if (!swipeUp()) {
                            postUILog("⏹ 手势失败（设备不支持或页面无法滚动）")
                            break
                        }
                        delay(1800)
                    }
                }
                if (platformLeft) {
                    postUILog("⏹ 抓取已停止（本次已处理 ${seen.size} 条）")
                    endFetchUI("⏹ 已停止抓取支付宝")
                } else {
                    postUILog("🎉 抓取完成：共识别 $totalAdded 笔（去重后 ${seen.size} 条），已写入消费记录")
                    endFetchUI("✅ 抓取完成：共 $totalAdded 笔")
                    // 完成后让 AI 汇总点评一次（与外部引擎一致）
                    if (seen.isNotEmpty()) {
                        val top = agg.topMerchants(3)
                        AIService.analyzeBillBatch("支付宝账单", seen.size, agg.sum, top)
                        postUILog("🧠 已请 AI 汇总点评「支付宝账单」")
                    } else {
                        postUILog("⚠️ 本次未解析到新记录（可能是页面格式差异，日志已保留屏幕文本）")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
 // 用户点悬浮窗"停止抓取"导致的取消：不当作异常上
                Log.d(TAG, "⏹ 支付宝抓取已取消（用户停止）")
                postUILog("⏹ 已停止抓取支付宝账单")
            } catch (e: Exception) {
                Log.e(TAG, "账单抓取异常", e)
                postUILog("❌ 账单抓取中断：${e.message}")
                endFetchUI("❌ 抓取中断：${e.message}")
            } finally {
                isBillFetching = false
                endFetchUI(null) // 兜底：任何退出路径都收起控制悬浮
            }
        }
    }

    /**
     * 抓取一屏：优先让视觉模型判断并读取明细行（支付宝列表文字常对无障碍隐藏）；
 * 视觉不可用时退回文本配对。返回本屏新增笔数
     */
    /**
     * 抓取一屏：优先用文字行解析（支付宝账单明细页文字可见、一行式格式）；
 * 文字解析不到时再用视觉模型看截图读行。返回本屏新增笔数
     */
    private suspend fun parseOneScreen(seen: MutableSet<String>, agg: ExternalAgg, isFirst: Boolean): Int {
        val root = activeRoot()
        if (root != null && isProfilePage(root)) {
            Log.d(TAG, "🚫 [支付宝] 当前屏是「我的/个人中心」页而非账单列表，跳过解析")
            return 0
        }
        val textAdded = parseBillScreenText(seen, agg)
        if (textAdded > 0) return textAdded

 // 视觉读屏兜底（页面文字被隐藏时，如花呗页
        val b64 = captureScreenBase64() ?: return 0
        val read = AIService.readBillScreen(b64)
        if (isFirst && !read.isBillList) {
 // 首屏既无文字行、视觉也判定非明细列中止
            postUILog("❌ 当前页面不是账单明细列表，停止抓取（可能点错入口）")
            billFetchJob?.cancel()
            return 0
        }
        if (read.isBillList) {
            var added = 0
            for (e in read.entries) {
                if (e.type.contains("收")) continue // 只记录支出
                    val timeMs = parseTimeFlexible(e.time)
                val key = seenKeyFor(e.merchant, e.amount, timeMs)
                if (seen.size < billLimit && seen.add(key)) {
                    AccessibilityEventRepository.postConsumption(
                        recordAt("支付宝账单", e.merchant, e.amount, timeMs)
                    )
                    agg.sum += e.amount
                    val mn = e.merchant.trim().take(24)
                    if (mn.isNotEmpty()) {
                        agg.merchants[mn] = agg.merchants.getOrDefault(mn, 0.0) + e.amount
                    }
                    added++
                }
            }
            return added
        }
        return 0
    }

    private fun stopAlipayBillFetch() {
        billFetchJob?.cancel()
        billFetchJob = null
        billFetchWatchdog?.cancel()
        billFetchWatchdog = null
        isBillFetching = false
    }

 /** 抓取停滞看门狗：引擎各环节每轮刷fetchLastTick；超90s 无进展自动停止（防页网络/无障碍卡死） */
    private fun startFetchWatchdog() {
        billFetchWatchdog?.cancel()
        billFetchWatchdog = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(10_000)
                if (!isBillFetching) return@launch
                if (System.currentTimeMillis() - fetchLastTick > 90_000L) {
                    postUILog("⏰ 抓取停滞超 90s（疑似页面卡死/网络挂起），自动停止")
                    requestStopFetch()
                    return@launch
                }
            }
        }
    }

 /** 「我个人中心」页特征（设客服，或会员/成长权益等≥3）：这类页面不是订单列表，禁止当列表解析 */
    private fun isProfilePage(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectSnapshotLimited(root, texts, 120)
        val joined = texts.joinToString(" ")
        if (joined.contains("设置") && joined.contains("客服")) return true
        return listOf("会员中心", "成长值", "权益", "签到", "我的资产").count { joined.contains(it) } >= 3
    }

    // ============== 多平台账单抓取引擎（美团 / 淘宝闪购等） ==============

    private fun startExternalBillFetch(
        source: String,
        pkg: String,
        primaryLabels: List<String>,
        billLabels: List<String>,
        postLabels: List<String> = emptyList(),
        bottomTabTap: Pair<Float, Float>? = null
    ) {
        if (isBillFetching) {
            postUILog("⏳ 已有抓取任务进行中，请稍候")
            return
        }
        isBillFetching = true
        billLimit = UserSettings(this).fetchBillLimit
        billFetchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                postUILog("📋 开始抓取「$source」账单…")
                beginFetchUI("① 正在打开$source…（点悬浮窗可停止）")
                fetchLastTick = System.currentTimeMillis()
                startFetchWatchdog()
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) {
                    postUILog("⚠️ 未安装「$source」应用，请先安装并登录")
                    return@launch
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
                postUILog("🚀 已打开「$source」，尝试进入账单页…")
                updateFetchUI("① 已打开$source，等待前台…")

 // —等待目标到前台：期间自动点掉系统「跳转应用许可」弹窗；超时即中止，绝不盲点 —
                var fg = false
                val waitStart = System.currentTimeMillis()
                for (i in 1..30) {
                    delay(900)
                    val root = activeRoot()
                    val pk = root?.packageName?.toString()
                    if (pk == pkg) {
                        fg = true
                        break
                    }
                    if (root != null && dismissJumpDialogIfAny(root)) {
                        postUILog("✅ 已处理系统跳转许可弹窗，继续等待「$source」…")
                        delay(1400)
                        continue
                    }
                    if (root != null && i % 4 == 0) {
                        Log.d(TAG, "⏳ 等待「$source」前台 第${i}轮 pkg=$pk texts=${snapshotTexts(root, 12)}")
                    }
                }
                val waitSec = (System.currentTimeMillis() - waitStart) / 1000
                if (!fg) {
                    postUILog("❌ 打开「$source」${waitSec} 秒后仍不在前台（可能被系统拦截/弹窗未处理），转储屏幕供排查")
                    debugPrintCurrentScreen()
                    return@launch
                }
                postUILog("✅ 「$source」已到前台（${waitSec}s），等待页面稳定…")
                delay(2500)

 // 淘宝冷启动常停在活动/领券等二级页：先逐级返回到主界面（有底部导航），再导
                if (pkg == PKG_TAOBAO) {
                    postUILog("🧭 检查/回到淘宝主界面（活动页将自动逐级返回）…")
                    if (!ensureTaobaoHome()) {
                        postUILog("❌ 检测不到淘宝主界面底部导航，中止本次抓取")
                        debugPrintCurrentScreen()
                        return@launch
                    }
                    postUILog("✅ 已在淘宝主界面，开始导航到订单…")
                    delay(1200)
                }

 // —逐入口导航到账单/明细列表 —
                            var foundBills = false
                val steps = ArrayList<String>().apply {
                    addAll(primaryLabels)
                    addAll(billLabels)
                    addAll(postLabels)
                }
                var stepIndex = 0
                var bottomTabDone = false
                var lastFailStep = -1
                var lastLogStep = -1
                val visionTried = HashSet<Int>()
                val navStart = System.currentTimeMillis()
                for (attempt in 1..12) {
                    delay(900)
                    fetchLastTick = System.currentTimeMillis()
                    val root = activeRoot()
                    if (root == null) {
                        Log.d(TAG, "🧭 第${attempt}轮 root 为空（页面加载中），稍候重试")
                        continue
                    }
                    val pk = root.packageName?.toString()
                    if (pk == packageName) {
                        postUILog("⏹ 已回到理伴应用，停止抓取")
                        return@launch
                    }
                    if (pk != pkg) {
 // 导航途中离开目标应用（又弹跳转许/ 回到桌面 / 拉起其它 App
                        if (dismissJumpDialogIfAny(root)) {
                            postUILog("✅ 导航途中已处理系统跳转许可弹窗")
                            delay(1400)
                            continue
                        }
                        Log.d(TAG, "🧭 第${attempt}轮 已不在$pkg(pkg=$pk) texts=${snapshotTexts(root, 10)}")
                        continue
                    }
                    if (dismissDialogIfAny(root)) {
                        delay(1600)
                        continue
                    }
                    if (attempt == 1 || stepIndex != lastLogStep) {
                        lastLogStep = stepIndex
                        Log.d(TAG, "🧭 第${attempt}轮 step=$stepIndex/${steps.size} texts=${snapshotTexts(root, 14)}")
                    }
 // 已在列表（含用户手动停在列表页的首轮 / 刚点完某个订单入口到达列表）进入解析
 // 阈primary+bill-1：点过订单入口后屏幕一出现列表就停止，避免继续去点
 // 后面的泛词（订单"）而误触顶部搜索栏
                    val looksList = if (pkg == PKG_TAOBAO) {
                        isTaobaoOrderPage(root) // 淘宝严格订单页特征，避免我的淘宝"个人页当列表
                    } else {
 // 「我个人中心」页(设置+客服/会员中心即使待付¥ 图标也不算订单列
                        (isOrderListRows(root) || isBillRowsPage(root)) && !isProfilePage(root)
                    }
                    // 已点过主入口(我的/我的淘宝)后，一旦出现列表就停止——不再逐个去点订单入口备用词，
 // 避免在列表页继续待付闪购"等误触其它页
                    val navDone = stepIndex == 0 || stepIndex >= primaryLabels.size
                    if (looksList && navDone) {
                        foundBills = true
                        break
                    }
 // 淘宝防呆：点完「我的淘宝」却仍停在信息流(被其它误判当列表")立即中止，绝不误
                    if (pkg == PKG_TAOBAO && bottomTabDone && stepIndex == 0 &&
                        looksList && !isTaobaoOrderPage(root)
                    ) {
                        postUILog("❌ 点完「我的淘宝」后仍停在信息流（非订单页），中止本次抓取")
                        debugPrintCurrentScreen()
                        return@launch
                    }
                    if (stepIndex >= steps.size) {
                        foundBills = true // 步骤走完，交由解析环节用视觉/文本判定
                        break
                    }
 // 0 步：底部导航坐标兜底（如淘宝右下「我的淘宝」文字常对无障碍隐藏）
 // 坐标点击不占steps 里的文字步骤；若首页加载慢导致首次未生效，第 3/6 轮重试一次
                    val needBottomTab = bottomTabTap != null && stepIndex == 0 &&
                            (!bottomTabDone || (lastFailStep == 0 && attempt % 3 == 0))
                    if (needBottomTab) {
                        bottomTabDone = true
 // 优先：按 content-desc 在底部区域点击（淘宝首页底部「我的淘宝」Tab desc 暴露
                        if (pkg == PKG_TAOBAO && steps.isNotEmpty() &&
                            tryClickDescBottom(root, steps[0])
                        ) {
                            postUILog("✅ desc 点底部「${steps[0]}」")
                            stepIndex = primaryLabels.size // 已进入个人页：下一步直接点「我的订单
                            delay(2600)
                            continue
                        }
                        val w = resources.displayMetrics.widthPixels
                        val h = resources.displayMetrics.heightPixels
                        val cx = bottomTabTap.first * w
                        val cy = bottomTabTap.second * h
                        postUILog("📍 坐标点底部导航(${cx.toInt()},${cy.toInt()})")
                        if (gestureClick(cx, cy)) {
                            delay(2600)
                            continue
                        }
                    }
 // 淘宝专用：点「我的订单」整bar 并校验真的进入订单页（不成功绝不继续点）
                    if (pkg == PKG_TAOBAO && stepIndex == primaryLabels.size &&
                        steps.getOrNull(stepIndex) == "我的订单"
                    ) {
                        if (clickTaobaoOrderBarVerified(root)) {
                            stepIndex++
                            delay(2600)
                            continue
                        } else {
                            postUILog("❌ 点「我的订单」未进入订单页，中止本次抓取")
                            return@launch
                        }
                    }
                    val label = steps[stepIndex]
                    if (tryClickText(root, label)) {
                        postUILog("✅ 已点「$label」")
                        stepIndex++
                        delay(2300)
                        continue
                    }
 // desc 精确点击（很多按钮只content-desc，文字匹配不到）
                    if (tryClickDescTarget(root, label)) {
                        postUILog("✅ desc 点「$label」")
                        stepIndex++
                        delay(2300)
                        continue
                    }
                    if (!visionTried.contains(stepIndex)) {
                        visionTried.add(stepIndex)
                        if (clickByVision(label)) {
                            postUILog("👁️ 视觉点「$label」")
                            stepIndex++
                            delay(2600)
                            continue
                        }
                    }
                    // 找不到该入口：同一入口最多再重试一轮（页面加载慢），然后换下一入口
                    if (stepIndex == lastFailStep) {
                        postUILog("⚠️ 连续两轮找不到「$label」，尝试后续入口")
                        lastFailStep = -1
                        stepIndex++
                    } else {
                        lastFailStep = stepIndex
                        Log.d(TAG, "🧭 未找到「$label」，下轮重试…")
                    }
                }
                if (!foundBills) {
                    val navSec = (System.currentTimeMillis() - navStart) / 1000
                    postUILog("❌ ${navSec}s 未能进入「$source」账单/明细页（页面结构可能不同或未登录），已打印屏幕文本供排查")
                    debugPrintCurrentScreen()
                    return@launch
                }
                postUILog("✅ 已进入「$source」账单页，逐屏解析中…")
                updateFetchUI("② $source 账单解析中…")

 // 淘宝：预载本地账支付宝等)用于"金额+店名"匹配真实支付时间
                if (pkg == PKG_TAOBAO) {
                    tbIndexBills =
                        runCatching { FinanceDb.get(this@FinanceAccessibilityService).billDao().all() }
                            .getOrDefault(emptyList())
                    tbFetchNowMs = System.currentTimeMillis()
                    tbMatchedByCore.clear()
                    Log.d(TAG, "📚 淘宝时间匹配：已载入 ${tbIndexBills.size} 条本地账单")
                }

                val seen = HashSet<String>()
                val agg = ExternalAgg()
                var noNewStreak = 0
                var platformLeft = false
                for (screen in 1..MAX_BILL_SCREENS) {
                    fetchLastTick = System.currentTimeMillis()
                    delay(1300)
 // 用户切回理伴/切去其它应用：立即停止，绝不解析、点击非目标窗口（防卡死/假账
                    if (!ensureStillOnPlatform(pkg)) {
                        platformLeft = true
                        break
                    }
                    // 淘宝：首屏进入解析前 dump 一次订单列表结构（调试/校准 desc 解析用）
                    if (pkg == PKG_TAOBAO && screen == 1) dumpActiveScreen()
                    val added = parseExternalScreen(source, seen, agg)
                    postUILog("📄 第 $screen 屏：新识别 $added 笔，累计识别 ${seen.size} 条")
                    if (seen.size >= billLimit) {
                        postUILog("⏹ 已达 $billLimit 条上限，停止")
                        break
                    }
                    if (screen % 3 == 0 || added > 0) {
                        updateFetchUI("$source 解析中：第 $screen 屏 · 已识别 ${seen.size} 条…（可停止）")
                    }
                    if (added == 0) {
                        noNewStreak++
                        if (noNewStreak >= 2) {
                            postUILog("⏹ 连续两屏无新记录，停止")
                            break
                        }
                    } else {
                        noNewStreak = 0
                    }
                    if (screen < MAX_BILL_SCREENS) {
                        if (!swipeUp()) {
                            postUILog("⏹ 手势失败，停止滚动")
                            break
                        }
                        delay(1800)
                    }
                }
                if (platformLeft) {
                    postUILog("⏹ 抓取已停止（「$source」已处理 ${seen.size} 条）")
                    endFetchUI("⏹ 已停止抓取$source")
                } else {
                    postUILog("🎉 「$source」抓取完成：共 ${seen.size} 条，合计约 ¥${"%.2f".format(agg.sum)}")
                    endFetchUI("✅ $source 抓取完成 ${seen.size} 笔")

 // 完成后让 AI 汇总点评一
                    if (seen.isNotEmpty()) {
                        val top = agg.topMerchants(3)
                        AIService.analyzeBillBatch(source, seen.size, agg.sum, top)
                        postUILog("🧠 已请 AI 汇总点评「$source」账单")
                    } else {
                        postUILog("⚠️ 本次未解析到新记录（可能是页面格式差异，日志已保留屏幕文本）")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
 // 用户点悬浮窗"停止抓取"导致的取消：不当作异常上
                Log.d(TAG, "⏹「$source」抓取已取消（用户停止）")
                postUILog("⏹ 已停止抓取「$source」账单")
            } catch (e: Exception) {
                Log.e(TAG, "$source 账单抓取异常", e)
                postUILog("❌「$source」抓取中断：${e.message}")
                endFetchUI("❌ $source 抓取中断")
            } finally {
                isBillFetching = false
                endFetchUI(null) // 兜底：任何退出路径都收起控制悬浮
            }
        }
    }

    /** 外部账单解析的聚合器 */
    private class ExternalAgg {
        var sum = 0.0
        val merchants = HashMap<String, Double>()

        fun topMerchants(n: Int): String =
            merchants.entries.sortedByDescending { it.value }
        .take(n).joinToString("、") { "${it.key}(¥${"%.2f".format(it.value)})" }
    }

 /** 是否订单/明细列表"样式：出现订单状态词 + ¥ 符号节点（美团订单列表等；desc 也算状态词*/
    private fun isOrderListRows(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectSnapshotLimited(root, texts, 120)
        val hasStatus = texts.any { it.contains("已完成") || it.contains("待评价") || it.contains("待付款") }
        val hasYuan = texts.any { it.trim() == "¥" || it.trim() == "￥" }
        return hasStatus && hasYuan
    }

    /**
 * 美团订单列表专用解析：金额被拆成 ["¥", "9", ".9"] 三个节点（.9），
 * 商家取最近一已完状态前的店名
     */
    private fun parseOrderListStyle(
        source: String,
        texts: List<String>,
        seen: MutableSet<String>,
        agg: ExternalAgg
    ): Int {
        val values = texts.map { it.trim() }
        var added = 0
        var timeCursorMs: Long? = null
        var i = 0
        while (i < values.size) {
            val t = values[i]
            // 纯日期时间作为其后各笔账单的支付时间锚点
            parseTimeFlexible(t)?.let { timeCursorMs = it }
            if (t == "¥" || t == "￥") {
                // 组金额：¥ [整数] [.小数]（拆分节点合并规则见 parsing/BillAmountText.mergeYuanTokens）
                val (amount, next) = BillAmountText.mergeYuanTokens(values, i)
                if (amount != null) {
                    // 商家：从 ¥ 往前找最近一条"已完成/待评价/待付款"状态之前的店名
                    var merchant = "未知商家"
                    var k = i - 1
                    while (k >= 0) {
                        val tk = values[k]
                        if (tk.contains("已完成") || tk.contains("待评价") || tk.contains("待付款")) {
                            if (k - 1 >= 0 && values[k - 1].length in 2..30) {
                                merchant = values[k - 1]
                            }
                            break
                        }
                        k--
                    }
                    val key = seenKeyFor(merchant, amount, timeCursorMs)
                    if (merchant == "未知商家" || isNoiseText(merchant)) continue // desc/噪声兜底：认不出商家不入
                    if (seen.size < billLimit && seen.add(key)) {
                        AccessibilityEventRepository.postConsumption(
                            recordAt(source, merchant, amount, timeCursorMs)
                        )
                        agg.sum += amount
                        agg.merchants[merchant] = agg.merchants.getOrDefault(merchant, 0.0) + amount
                        added++
                    }
                    i = next
                    continue
                }
            }
            i++
        }
        return added
    }

 // ============== 淘宝订单卡解析（desc 店名/状+ 真实 text 实付金额==============

    /** 淘宝状态词表（规则本体见 parsing/MerchantText.kt） */
    private val tbStatusWords: List<String> = MerchantText.TB_STATUS_WORDS

    /** 店名噪声过滤（规则本体见 parsing/MerchantText.isTbBrandNoise） */
    private fun isTbBrandNoise(desc: String): Boolean = MerchantText.isTbBrandNoise(desc)

    /** 归一化店名：规则本体见 parsing/MerchantText.normMerchant */
    private fun normMerchant(raw: String): String = MerchantText.normMerchant(raw)

    /**
 * 解析淘宝订单卡并入账
 * 结构（已真机校准）：每单=店名头行(desc, + 状desc, + 商品desc) + 共N实付+ 真实text金额
 * 页面无日时间金额+店名"匹配支付本地账单；无匹配则沿用最近匹配锚上下文推，仍无则抓取时刻
     */
    private fun parseTaobaoScreen(source: String, seen: MutableSet<String>, agg: ExternalAgg): Int {
        val root = activeRoot() ?: return 0
        data class Node(val y: Int, val x0: Int, val x1: Int, val s: String)
        val statuses = ArrayList<Node>()
        val brands = ArrayList<Node>()
        val payLabels = ArrayList<Node>() // "实付 标签
        val prices = ArrayList<Node>()    // 真实 TextView ¥金额（任何位置）
        var scanned = 0
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 2500) return
            val r = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(r) }
            if (r.isEmpty || r.top > r.bottom || r.bottom < 0 || r.top > r.bottom + 200) return
            val cy = r.centerY()
            val t = node.text?.toString()?.trim() ?: ""
            val d = node.contentDescription?.toString()?.trim() ?: ""
            val x0 = r.left
            val x1 = r.right
            val priceM = BillAmountText.PRICE_TOKEN_REGEX.find(t)
            if (priceM != null) {
                prices.add(Node(cy, x0, x1, priceM.groupValues[1]))
                return
            }
            if (d.contains("实付款")) {
 // 实付款标签行（金额只认它与同行右侧的 ¥ 文本
                payLabels.add(Node(cy, x0, x1, "实付款"))
                return
            }
            if (d.isNotEmpty()) {
                if (tbStatusWords.any { d.contains(it) } && r.centerX() > 780) {
                    statuses.add(Node(cy, x0, x1, d))
                    return
                }
                if (x0 in 150..790 && !isTbBrandNoise(d)) {
                    brands.add(Node(cy, x0, x1, d))
                    return
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try {
            scan(root)
        } catch (t: Throwable) {
            Log.w(TAG, "淘宝卡扫描异常: ${t.message}")
        }

 // 订单金额 = 与「实付款」标签同y±90)且在其右或同x)的最近真¥ 文本
        val amounts = ArrayList<Node>()
        val usedPrice = HashSet<Int>()
        for (pl in payLabels) {
            var bestIdx = -1
            var bestDy = Int.MAX_VALUE
            for ((idx, p) in prices.withIndex()) {
                if (usedPrice.contains(idx)) continue
                if (kotlin.math.abs(p.y - pl.y) > 90) continue
                if (p.x1 < pl.x0 - 15) continue // 必须在标签右侧（或略左容差）
                val dy = kotlin.math.abs(p.y - pl.y)
                if (dy < bestDy) {
                    bestDy = dy
                    bestIdx = idx
                }
            }
            if (bestIdx >= 0) {
                usedPrice.add(bestIdx)
                val p = prices[bestIdx]
                amounts.add(Node(pl.y, p.x0, p.x1, p.s))
            }
        }
        if (amounts.isEmpty()) {
 // 有金状态但没找实付标签（页面布局变化）：标记已处理，避免其它解析通道把逐项价当订单
            if (prices.isNotEmpty() && statuses.isNotEmpty()) {
                Log.w(TAG, "淘宝卡解析：有 ¥金额(${prices.size})但未找到「实付款」标签，跳过防误判")
                return 1
            }
            return 0
        }

        amounts.sortBy { it.y }
        var processed = 0
        for (a in amounts) {
 // 找该单的状态行（金额上方最近一个、距400px
            val st = statuses.filter { it.y < a.y - 150 && a.y - it.y < 1400 }
                .maxByOrNull { it.y } ?: continue
 // 状态同一横带(±80px)左侧的店desc
            val names = brands.filter { kotlin.math.abs(it.y - st.y) < 80 }
                .sortedBy { it.x0 }
            val merchant = names.firstOrNull()?.s?.take(24) ?: continue
            if (merchant.length < 2) continue
            val amt = a.s.toDoubleOrNull() ?: continue
            val res = resolveTbTime(merchant, amt)
            val key = seenKeyFor(merchant, amt, res.timeMs)
            if (seen.size < billLimit && seen.add(key)) {
                processed++
                if (res.matched) {
 // 与支付宝/本地账单金额+店名相同 该笔已入过账，跳过防重复计算
                    Log.d(TAG, "⏭ 淘宝去重: $merchant ¥${"%.2f".format(amt)} 已在支付宝/本地账（${fmtTimeForLog(res.timeMs)}），跳过")
                    continue
                }
 // 列表无日期且匹配不到支付不伪造日期，跳过并留日志待办（抓全支付宝后再来可命中
                Log.d(TAG, "⏭ 淘宝未匹配·无日期·暂不入账: $merchant ¥${"%.2f".format(amt)}（列表不显示日期，且支付时间在本地账单无对应记录）")
            }
        }
        Log.d(TAG, "淘宝卡解析: 金额${amounts.size} 状态${statuses.size} 店名候选${brands.size} 个, 处理 $processed（均按匹配结果跳过/暂不入账）")
        // 只要处理过本屏（含去重跳过）就说明已是订单页，避免其它解析通道重复入账
        return processed
    }

 /** 淘宝时间解析结果：matched=true 表示已匹配到支付本地账单的真实支付时间（该笔应跳过，防重复） */
    private data class TbResolved(val timeMs: Long, val matched: Boolean)

 /** 金额+店名 匹配支付本地账单求真实支付时间；无匹配→最近锚上下 抓取时刻 */
    private fun resolveTbTime(merchant: String, amount: Double): TbResolved {
        val core = normMerchant(merchant)
        if (core.length >= 2) {
            var best: BillEntity? = null
            for (b in tbIndexBills) {
                if (kotlin.math.abs(b.amount - amount) >= 0.005) continue
                val bc = normMerchant(b.merchant)
                if (bc.length >= 2 && (bc.contains(core) || core.contains(bc))) {
                    if (best == null || b.occurredAtMs > best.occurredAtMs) best = b
                }
            }
            if (best != null) {
                tbMatchedByCore[core] = best.occurredAtMs
                Log.d(TAG, "🕒 淘宝时间匹配=单笔: $merchant ¥${"%.2f".format(amount)} = ${best.merchant} @${fmtTimeForLog(best.occurredAtMs)}（与支付宝单笔相同，跳过防重复）")
                return TbResolved(best.occurredAtMs, true)
            }

 // 组合匹配：该订单实付 = 同店同天多笔金额之和（如拆成 ¥7.96+¥2.94
 // key = 归一化店名|当天；只累加不超过订单总额的明
            var sumBestKey: String? = null
            var sumBestTotal = 0.0
            var sumBestMs = 0L
            val daySum = HashMap<String, Double>()
            val dayMaxMs = HashMap<String, Long>()
            for (b in tbIndexBills) {
                if (b.amount > amount + 0.01) continue
                val bc = normMerchant(b.merchant)
                if (bc.length < 2) continue
                if (bc.contains(core) || core.contains(bc)) {
                    val k = "$bc|${b.dayBucket}"
                    daySum[k] = (daySum[k] ?: 0.0) + b.amount
                    val prev = dayMaxMs[k] ?: 0L
                    if (b.occurredAtMs > prev) dayMaxMs[k] = b.occurredAtMs
                }
            }
            for ((k, sum) in daySum) {
                if (kotlin.math.abs(sum - amount) < 0.01) {
                    val ms = dayMaxMs[k] ?: 0L
                    if (ms > sumBestMs) {
                        sumBestMs = ms
                        sumBestTotal = sum
                        sumBestKey = k
                    }
                }
            }
            if (sumBestKey != null) {
                tbMatchedByCore[core] = sumBestMs
                Log.d(TAG, "🕒 淘宝时间匹配=多笔之和: $merchant ¥${"%.2f".format(amount)} = 同店同天合计 ¥${"%.2f".format(sumBestTotal)} @${fmtTimeForLog(sumBestMs)}（该天已入账，跳过防重复）")
                return TbResolved(sumBestMs, true)
            }
        }
        val sameStoreAnchor = tbMatchedByCore[core]
        val guess = sameStoreAnchor ?: tbFetchNowMs
        Log.d(TAG, "🕒 淘宝时间推断: $merchant ¥${"%.2f".format(amount)} 无匹配: ${if (sameStoreAnchor != null) "同店锚点" else "抓取时刻"} (${fmtTimeForLog(guess)})")
        return TbResolved(guess, false)
    }

    /**
 * 点「我的订单」区用户截图:标题+ 待付待发待收待评图标并校验进入订单列表
 * 优先点图标行（更靠下、更稳），失败再点「我的订单」标题条；两种都失败即中止
 * 点击点取目标上方1/3，避开可能盖在下面的浮层
     */
    private suspend fun clickTaobaoOrderBarVerified(root: AccessibilityNodeInfo): Boolean {
        runCatching { dismissDialogIfAny(root) }
        delay(500)
        var scanned = 0
        var iconRect: android.graphics.Rect? = null
        var headerRect: android.graphics.Rect? = null
        fun consider(r: android.graphics.Rect?, kind: Int) {
            if (r == null || r.isEmpty || r.centerY() !in 600..1500) return
            if (kind == 0 && r.width() in 120..320 && iconRect == null) iconRect = android.graphics.Rect(r)
            if (kind == 1 && r.width() >= 300 && headerRect == null) headerRect = android.graphics.Rect(r)
        }
        fun find(node: AccessibilityNodeInfo) {
            if (scanned++ > 2500) return
            val d = node.contentDescription?.toString() ?: ""
            fun clickableRect(): android.graphics.Rect? {
                var p: AccessibilityNodeInfo? = node
                var depth = 0
                while (p != null && depth < 6) {
                    if (p.isClickable) {
                        val r = android.graphics.Rect()
                        runCatching { p.getBoundsInScreen(r) }
                        return r
                    }
                    p = p.parent
                    depth++
                }
                return null
            }
            if (d.startsWith("待付款") || d.startsWith("待评价")) {
                consider(clickableRect(), 0)
            }
            if (d.contains("我的订单") && !d.contains("更多")) {
                consider(clickableRect(), 1)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }
        }
        runCatching { find(root) }

        suspend fun attempt(rect: android.graphics.Rect, desc: String): String {
            val y = (rect.top + rect.height() * 0.33f).toInt()
            Log.d(TAG, "👉 点「desc」@(${rect.centerX()},$y) [${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
            if (!gestureClick(rect.centerX().toFloat(), y.toFloat())) return "none"
            delay(2600)
            val r1 = activeRoot()
            if (r1?.let { isTaobaoOrderPage(it) } == true) return "page"
            delay(1800)
            val r2 = activeRoot()
            if (r2?.let { isTaobaoOrderPage(it) } == true) return "page"
            if (r2?.let { isTaobaoOrderCenter(it) } == true) return "center"
            Log.d(TAG, "⚠️ 点「desc」后既非订单行页也非订单中心")
            return "none"
        }

        suspend fun tryOrderEntry(rect: android.graphics.Rect, desc: String): Boolean {
            val s = attempt(rect, desc)
            if (s == "page") return true
 // 到订单中或未确认)时，仍坚定去点一次「闪购」页
            if (clickTbFlashTabConfirm()) return true
            return s == "center"
        }

        if (iconRect != null && tryOrderEntry(iconRect!!, "我的订单-图标")) return true
        if (headerRect != null && tryOrderEntry(headerRect!!, "我的订单-标题")) return true
        Log.w(TAG, "❌ 未成功进入淘宝订单中心")
        return false
    }

 /** 在订单中心点顶部「闪购」页签，确认进入闪购(外卖)订单*/
    private suspend fun clickTbFlashTabConfirm(): Boolean {
        val root = activeRoot() ?: return false
        var best: android.graphics.Rect? = null
        var bestArea = Long.MAX_VALUE
        var scanned = 0
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 1200) return
            val d = node.contentDescription?.toString() ?: ""
            if (d.contains("闪购")) {
                var p: AccessibilityNodeInfo? = node
                var depth = 0
                while (p != null && depth < 6) {
                    if (p.isClickable) {
                        val r = android.graphics.Rect()
                        runCatching { p.getBoundsInScreen(r) }
                        if (!r.isEmpty && r.centerY() < resources.displayMetrics.heightPixels * 0.5f) {
                            val a = r.width().toLong() * r.height()
                            if (a < bestArea) { bestArea = a; best = android.graphics.Rect(r) }
                        }
                        break
                    }
                    p = p.parent
                    depth++
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try { scan(root) } catch (t: Throwable) { /* ignore */ }
        if (best == null) {
 // desc 扫不到时用校准坐标兜底（闪购页签中心 (0.486w, 0.117h)
            val cx = resources.displayMetrics.widthPixels * 0.486f
            val cy = resources.displayMetrics.heightPixels * 0.117f
            Log.d(TAG, "👉 闪购页签坐标兜底 @(${cx.toInt()},${cy.toInt()})")
            gestureClick(cx, cy)
            delay(2600)
        } else {
            val r = best!!
            val y = (r.top + r.height() * 0.5f).toInt()
            Log.d(TAG, "👉 点「闪购」页签 @(${r.centerX()},$y) [${r.left},${r.top}][${r.right},${r.bottom}]")
            if (!gestureClick(r.centerX().toFloat(), y.toFloat())) return false
            delay(2600)
        }
        val after = activeRoot()
        val ok = after?.let { isTaobaoOrderPage(it) } == true ||
                after?.let { a -> hasFlashSelected(a) } == true
        Log.d(TAG, if (ok) "✅ 已进入闪购订单页" else "⚠️ 点「闪购」后未确认，仍尝试解析")
        return true
    }

    /** 是否已选中闪购页签 */
    private fun hasFlashSelected(root: AccessibilityNodeInfo): Boolean {
        var scanned = 0
        var found = false
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 900 || found) return
            val d = node.contentDescription?.toString() ?: ""
            if (d.contains("闪购已选中") || d.contains("闪购,已选中")) {
                found = true
                return
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try { scan(root) } catch (t: Throwable) { return false }
        return found
    }

 /** 淘宝"订单中心"页判进入后还没选闪：顶部有 搜索订单/筛选·管全部订单·飞猪 等特*/
    private fun isTaobaoOrderCenter(root: AccessibilityNodeInfo): Boolean {
        var hasSearch = false
        var hasFilterManage = false
        var hasTabs = false
        var scanned = 0
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 900) return
            val d = node.contentDescription?.toString() ?: ""
            if (d.contains("搜索订单")) hasSearch = true
            if ((d.contains("筛选") && d.contains("管理"))) hasFilterManage = true
            if (d.contains("全部订单") || d.contains("飞猪") || d.contains("闪购")) hasTabs = true
            if (hasSearch || (hasFilterManage && hasTabs)) return
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try { scan(root) } catch (t: Throwable) { return false }
        return hasSearch || (hasFilterManage && hasTabs)
    }

 /** 淘宝订单列表页判定：真实 text 里出¥金额(一两位小数均可) + desc 有「实付款/共x件+ 有订单状*/
    private fun isTaobaoOrderPage(root: AccessibilityNodeInfo): Boolean {
        var realYuan = 0
        var payDesc = 0
        var statusDesc = 0
        var scanned = 0
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 800) return
            val t = node.text?.toString()?.trim() ?: ""
            val d = node.contentDescription?.toString()?.trim() ?: ""
            if (t.isNotEmpty() && Regex("""^[¥￥]\s*\d+(?:\.\d{1,2})?$""").matches(t)) realYuan++
            if (d == "实付款" || d.contains("实付款")) payDesc++
            if (tbStatusWords.any { d.contains(it) }) statusDesc++
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try {
            scan(root)
        } catch (t: Throwable) {
            return false
        }
        return realYuan >= 1 && payDesc >= 1 && statusDesc >= 1
    }

 /** 解析一屏外部平台账单：文字优先（行下单：yyyy-MM-dd HH:mm"等时间最可靠），视觉读屏兜底 */
    private suspend fun parseExternalScreen(
        source: String,
        seen: MutableSet<String>,
        agg: ExternalAgg
    ): Int {
        val root = activeRoot()
        if (root != null && looksLikeShoppingFeed(root)) {
            Log.d(TAG, "🛒 [$source] 当前屏为商品/信息流而非账单列表，跳过解析")
            return 0
        }
        if (root != null && isProfilePage(root)) {
            Log.d(TAG, "🚫 [$source] 当前屏是「我的/个人中心」页而非订单列表，跳过解析（防止把个人页当账单/空跑）")
            return 0
        }
        val texts = mutableListOf<String>()
        if (root != null) collectAllText(root, texts)

 // 0) 淘宝专用：订单卡 desc 解析（店状态在 desc、实付金额是真实 text；时匹配支付宝或上下文推断）
        if (source == "淘宝闪购") {
            val tbAdded = parseTaobaoScreen(source, seen, agg)
            if (tbAdded > 0) {
                Log.d(TAG, "🧾 淘宝卡解析新增 $tbAdded 笔")
                return tbAdded
            }
        }

 // 1) 美团订单样式（金额拆分为 ¥ / 9 / .9，行内常下单026-09-02 10:54"
        val orderAdded = parseOrderListStyle(source, texts, seen, agg)
        if (orderAdded > 0) {
            Log.d(TAG, "🧾 文字解析[$source] 订单样式新增 $orderAdded 笔")
            return orderAdded
        }

 // 2) 独立金额文本12.50 / ¥12.50 / 12.50），就近找商
        var added = 0
        var timeCursorMs: Long? = null
        for ((i, raw) in texts.withIndex()) {
            val t = raw.trim()
            parseTimeFlexible(t)?.let { timeCursorMs = it } // 日期锚点
            if (t.startsWith("+")) continue // 收入行跳
            val amount = BILL_AMOUNT_REGEX.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (amount <= 0.0) continue
            val merchant = findMerchantNear(texts, i) ?: continue
            if (merchant == "未知商家" || isNoiseText(merchant)) continue // 认不出商家不入账
            val key = seenKeyFor(merchant, amount, timeCursorMs)
            if (seen.size < billLimit && seen.add(key)) {
                AccessibilityEventRepository.postConsumption(
                    recordAt(source, merchant, amount, timeCursorMs)
                )
                agg.sum += amount
                agg.merchants[merchant] = agg.merchants.getOrDefault(merchant, 0.0) + amount
                added++
            }
        }
        if (added > 0) {
            Log.d(TAG, "🧾 文字解析[$source] 独立金额新增 $added 笔")
            return added
        }

 // 3) 文字都读不到时，视觉读屏兜底（美淘宝订单行结构多变场景）
        val b64 = captureScreenBase64()
        if (b64 != null) {
            val read = AIService.readBillScreen(b64)
            if (read.isBillList && read.entries.isNotEmpty()) {
                for (e in read.entries) {
                    if (e.type.contains("收")) continue
                    val merchant = e.merchant.trim()
                    if (merchant.isEmpty() || merchant == "未知商家") continue
                    val timeMs = parseTimeFlexible(e.time)
                    val key = seenKeyFor(merchant, e.amount, timeMs)
                    if (seen.size < billLimit && seen.add(key)) {
                        AccessibilityEventRepository.postConsumption(
                            recordAt(source, merchant.take(24), e.amount, timeMs)
                        )
                        agg.sum += e.amount
                        agg.merchants[merchant.take(24)] =
                            agg.merchants.getOrDefault(merchant.take(24), 0.0) + e.amount
                        added++
                    }
                }
                if (added > 0) {
                    Log.d(TAG, "👁️ 视觉解析[$source] 第屏新增 $added 笔（文字不可读时兜底）")
                    return added
                }
            }
        }
        return added
    }

 /** 安全获取当前活跃窗口根节点（服务未就绪时可能抛异常，这里吞掉返回 null*/
    private fun activeRoot(): AccessibilityNodeInfo? =
        try { rootInActiveWindow } catch (t: Throwable) {
            Log.w(TAG, "取根节点失败: ${t.message}")
            null
        }

    /**
 * 抓取每屏前校当前是否仍在目标平台"
 * 用户切回理伴或切去其它应用时返回 false（停止本次抓取）
 * 防止继续解析/上滑/点击我们自己的界—这是"跳转后切App 卡死"与误入假账的根因
 * 窗口暂时拿不到（页面加载/弹窗中）视为仍在处理
     */
    private fun ensureStillOnPlatform(targetPkg: String): Boolean {
        val cur = try { rootInActiveWindow?.packageName?.toString() } catch (t: Throwable) { null }
        if (cur.isNullOrEmpty()) return true
        if (cur == targetPkg) return true
        if (cur == packageName) {
            postUILog("⏹ 已回到理伴应用，自动停止抓取")
        } else {
            postUILog("⏹ 已离开「$targetPkg」（当前 $cur），自动停止抓取")
        }
        Log.d(TAG, "⏹ ensureStillOnPlatform=false: cur=$cur target=$targetPkg")
        return false
    }

 // ============== 调试：dump 当前窗口 a11y 树（text/desc/可点坐标）到 logcat ==============

 /** 深度受限dump 一棵节点（替代真机上常坏的 uiautomator dump*/
    private fun dumpActiveScreen() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            Log.w(TAG, "dump 失败：rootInActiveWindow 为空")
            return
        }
        Log.i(TAG, "📖 ===== 当前窗口 PKG=${root.packageName} =====")
        val counter = intArrayOf(0)
        dumpNode(root, 0, counter)
        Log.i(TAG, "📖 ===== dump 结束，共 ${counter[0]} 行 =====")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, counter: IntArray) {
        if (counter[0] > 2500) return
        counter[0]++
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val rect = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val indent = "  ".repeat(depth.coerceAtMost(6))
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            Log.i(
                TAG,
                "📖 ${indent}t=[${text.take(40)}] d=[${desc.take(40)}] c=${node.isClickable} " +
                        "cls=${node.className?.toString()?.substringAfterLast('.')} b=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1, counter) }
        }
    }

    /**
 * 淘宝专用：冷启动常恢复在"活动/领券/详情"等二级页（无底部主导航）
 * 策略：≤3 次逐级返回；仍检测不到底部导我的淘宝/购物时，CLEAR_TOP 重启淘宝到首页再试
     */
    private suspend fun ensureTaobaoHome(): Boolean {
        var backsThisRound = 0
        var nullStreak = 0
        var relaunches = 0
        for (round in 1..10) {
            delay(900)
            val root = activeRoot()
            if (root == null) {
                nullStreak++
                if (nullStreak >= 3 && relaunches < 2) {
                    postUILog("❌ 窗口不可读，重启淘宝到首页重试")
                    relaunchTaobaoHome()
                    relaunches++
                    nullStreak = 0
                    debugPrintCurrentScreen()
                }
                continue
            }
            nullStreak = 0
            val pk = root.packageName?.toString()
            if (pk != PKG_TAOBAO) {
                if (dismissJumpDialogIfAny(root)) {
                    Log.d(TAG, "🔐 回主界面途中点掉跳转许可弹窗")
                    delay(1400)
                    continue
                }
                Log.d(TAG, "🧭 ensureHome 第 $round 轮 不在淘宝(pkg=$pk)")
                if (pk != packageName && relaunches < 2 && round >= 3) {
                    postUILog("🔄 已离开淘宝，重启回首页重试")
                    relaunchTaobaoHome()
                    relaunches++
                    delay(2200)
                }
                continue
            }
            if (dismissDialogIfAny(root)) {
                delay(1500)
                continue
            }
            if (hasBottomTabNav(root)) {
                Log.d(TAG, "✅ 已在淘宝主界面（检测到底部导航）")
                return true
            }
            if (backsThisRound < 3) {
                if (tryClickText(root, "返回")) {
                    backsThisRound++
                    Log.d(TAG, "🧭 已点顶部「返回」，第$backsThisRound 次")
                    delay(1800)
                    continue
                }
                if (performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    backsThisRound++
                    Log.d(TAG, "🧭 全局返回，第$backsThisRound 次")
                    delay(1600)
                    continue
                }
            } else if (relaunches < 2) {
                postUILog("🔄 连续返回仍未见主界面，重启淘宝到首页重试")
                relaunchTaobaoHome()
                relaunches++
                backsThisRound = 0
                delay(2200)
            }
        }
        return hasBottomTabNav(activeRoot() ?: return false)
    }

 /** CLEAR_TOP 重启淘宝（回到首Activity*/
    private fun relaunchTaobaoHome() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(PKG_TAOBAO) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            Log.d(TAG, "✅ 已发起淘宝首页重启")
        } catch (t: Throwable) {
            Log.w(TAG, "重启淘宝失败: ${t.message}")
        }
    }

    /**
 * 是否已是淘宝"主界（可安全点击底部导航）：二选一命中true—
 * 底部导航>90% 屏高)出现「我的淘购物车」desc
 * 顶部频道y<20%)出现「推关注 + 闪购」text/desc（当前版本淘宝首页特征）
 * 领券/活动/详情页都没有这两个特不会在其上乱点
     */
    private fun hasBottomTabNav(root: AccessibilityNodeInfo): Boolean {
        val h = resources.displayMetrics.heightPixels
        var found = false
        var scanned = 0
        fun acceptDesc(r: android.graphics.Rect) {
            if (!r.isEmpty && r.centerY() > h * 0.90f && r.bottom <= h + 40) found = true
        }
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 4000 || found) return
            val d = node.contentDescription?.toString() ?: ""
            val t = node.text?.toString() ?: ""
            val r = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(r) }
            if (r.isEmpty) return
            if ((d.contains("我的淘宝") || d.contains("购物车")) &&
                !d.contains("订单") && !d.contains("全部") && !d.contains("更多")
            ) {
                acceptDesc(r)
                if (found) {
                    Log.d(TAG, "🏠 底部导航特征: desc=[$d] b=[${r.left},${r.top}][${r.right},${r.bottom}]")
                    return
                }
            }
 // 顶部频道行：闪购(频道)+关注/推荐 y<20%
            if (r.centerY() < h * 0.20f && (t.contains("闪购") || d.contains("闪购,未选中") || d.contains("闪购"))) {
 // 同屏再确认有 推荐/关注 频道
 // （简化：闪购频道头是淘宝首页独有，活动页无此布局
                found = true
                Log.d(TAG, "🏠 顶部频道特征: t=[$t] d=[$d] b=[${r.left},${r.top}][${r.right},${r.bottom}]")
                return
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try {
            scan(root)
        } catch (t: Throwable) {
            Log.w(TAG, "主界面检测异常: ${t.message}")
        }
        return found
    }
    /**
     * 精确点击底部 Tab（防误触上方领券/促销入口）：
 * 只接受「中y > 88% 屏高」的候选，y 最大（最靠底）的那个
 * 优先desc 所在节点自的中心，其次其可点击祖先（中心也必须在底部条带内）
     */
    private fun tryClickDescBottom(root: AccessibilityNodeInfo, label: String): Boolean {
        val h = resources.displayMetrics.heightPixels
        var bestRect: android.graphics.Rect? = null
        var bestY = Int.MIN_VALUE
        var scanned = 0
        fun accept(r: android.graphics.Rect) {
            if (r.centerY() > h * 0.88f && r.centerY() > bestY) {
                bestY = r.centerY()
                bestRect = android.graphics.Rect(r)
            }
        }
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 600) return
            val d = node.contentDescription?.toString() ?: ""
            if (d.contains(label) && !d.contains("更多")) {
                val own = android.graphics.Rect()
                runCatching { node.getBoundsInScreen(own) }
                if (!own.isEmpty && own.centerY() > h * 0.88f) {
                    accept(own) // desc 所在节点自身就在底部条精确点它
                } else {
                    var p: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (p != null && depth < 6) {
                        if (p.isClickable) {
                            val pr = android.graphics.Rect()
                            runCatching { p.getBoundsInScreen(pr) }
                            if (!pr.isEmpty && pr.centerY() > h * 0.88f) accept(pr)
                            break
                        }
                        p = p.parent
                        depth++
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scan(it) }
            }
        }
        try {
            scan(root)
        } catch (t: Throwable) {
            Log.w(TAG, "desc 底部查找失败: ${t.message}")
        }
        if (bestRect != null) {
            Log.d(TAG, "👉 精确点底部Tab「$label」@(${bestRect!!.centerX()},${bestRect!!.centerY()})")
            return gestureClick(bestRect!!.centerX().toFloat(), bestRect!!.centerY().toFloat())
        }
        return false
    }

 /** 取点击点：订单入口类按钮点在其可点区上方 1/3（避开可能盖在下方区域的弹浮层*/
    private fun clickYOf(rect: android.graphics.Rect, label: String): Int =
        if (label == "我的订单" || label == "待付款" || label == "待收货")
            (rect.top + rect.height() * 0.35f).toInt()
        else rect.centerY()

    /**
 * 中部区域(18%~88% 屏高)content-desc 精确点击（用于订单入口等非底部按钮）
 * desc label、排除含"文案；多个候选取可点击目标里面积最小（最精确）的那个
     */
    private fun tryClickDescTarget(root: AccessibilityNodeInfo, label: String): Boolean {
        val h = resources.displayMetrics.heightPixels
        var best: android.graphics.Rect? = null
        var bestArea = Long.MAX_VALUE
        var scanned = 0
        fun accept(r: android.graphics.Rect) {
            if (r.isEmpty) return
            val cy = r.centerY()
            if (cy < h * 0.18f || cy > h * 0.88f) return
            val area = r.width().toLong() * r.height()
            if (area < bestArea) {
                bestArea = area
                best = android.graphics.Rect(r)
            }
        }
        fun scan(node: AccessibilityNodeInfo) {
            if (scanned++ > 700) return
            val d = node.contentDescription?.toString() ?: ""
            if (d.contains(label) && !d.contains("更多")) {
                if (node.isClickable) {
                    val r = android.graphics.Rect()
                    runCatching { node.getBoundsInScreen(r) }
                    accept(r)
                } else {
                    var p: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (p != null && depth < 6) {
                        if (p.isClickable) {
                            val r = android.graphics.Rect()
                            runCatching { p.getBoundsInScreen(r) }
                            accept(r)
                            break
                        }
                        p = p.parent
                        depth++
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it) }
        }
        try {
            scan(root)
        } catch (t: Throwable) {
            Log.w(TAG, "desc 目标查找失败: ${t.message}")
        }
        if (best != null) {
            val r = best!!
            Log.d(TAG, "👉 desc 点「$label」@(${r.centerX()},${clickYOf(r, label)})")
            return gestureClick(r.centerX().toFloat(), clickYOf(r, label).toFloat())
        }
        return false
    }

    private fun openAlipay(): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(PKG_ALIPAY) ?: return false
        runCatching { startActivity(intent) }
        return true
    }

 /** 等待支付宝成为当前活跃窗*/
    private suspend fun waitForAlipayForeground(maxSeconds: Int): Boolean {
        repeat(maxSeconds * 2) {
            delay(500)
            val root = activeRoot()
            if (root != null && root.packageName == PKG_ALIPAY) return true
            // 跨应用拉起可能触发系统「跳转许可」弹窗：自动点掉，继续等
            if (root != null && dismissJumpDialogIfAny(root)) {
                Log.d(TAG, "🔐 支付宝前台等待中点掉跳转许可弹窗")
                delay(800)
            }
        }
        return false
    }

 /** 尝试进入账单页：文字点击优先，失败时用「视觉模型看截图」定位（支付我的"页内容对无障碍隐藏文字）
 * 顺序：点掉确认弹找「账单」→ 找「我的」→ 视觉兜底定位*/
    private suspend fun navigateToBillPage(): Boolean {
        var clickedMy = false
        var visionTriedBill = false
        var visionTriedMy = false
        var myEntryMisses = 0
        for (attempt in 1..8) {
            delay(600)
            fetchLastTick = System.currentTimeMillis()
            val root = activeRoot() ?: continue
            if (root.packageName?.toString() == packageName) {
                postUILog("⏹ 已回到理伴应用，停止抓取")
                return false
            }
            if (screenHasBillRows(root)) return true
            if (dismissDialogIfAny(root)) {
                delay(1500)
                continue
            }
            if (clickedMy) {
                // 已进入「我的」页：支付宝「我的」页内容常对无障碍隐藏文字——先文字/desc 找「账单」；
                // 找不到就多等一次加载再重扫；仍不行才视觉定位；连续两轮失败才放弃（不再首轮即退出）
                if (tryClickText(root, "账单") || tryClickDescTarget(root, "账单")) {
                    delay(2500)
                    return true // 交给主循环用视觉校验是否为账单明细列表
                }
                if (!visionTriedBill) {
                    delay(2500) // 内容懒加载：多等一次再重扫，避免"页面还没渲染完就判定失败"
                    val root2 = activeRoot() ?: return false
                    if (root2.packageName?.toString() != PKG_ALIPAY) return false
                    if (tryClickText(root2, "账单") || tryClickDescTarget(root2, "账单")) {
                        delay(2500)
                        return true
                    }
                    visionTriedBill = true
                    if (clickByVision("账单")) {
                        delay(3000)
                        return true // 同上：交由主循环校验
                    }
                }
                myEntryMisses++
                if (myEntryMisses >= 2) {
                    Log.d(TAG, "⚠️ 已在「我的」页但找不到「账单」入口，当前文本: " +
                            mutableListOf<String>().also { collectAllText(root, it) }
                                .filter { it.isNotBlank() }.take(40))
                    return false
                }
                delay(1500)
                continue // 页面结构/加载状态可能刚变化，再给一轮机会
            }
 // 首页阶段：只找底部导航「我的」，避免误点首页内容里的「花呗账单」等子入
            if (tryClickText(root, "我的")) {
                clickedMy = true
                delay(2500)
                continue
            }
            if (!visionTriedMy) {
                visionTriedMy = true
                if (clickByVision("我的")) {
                    clickedMy = true
                    delay(2800)
                    continue
                }
            }
            Log.d(TAG, "⚠️ 第 $attempt 轮：未找到可点击的「我的」，当前文本: " +
                    mutableListOf<String>().also { collectAllText(root, it) }
                        .filter { it.isNotBlank() }.take(40))
        }
        return screenHasBillRows(activeRoot() ?: return false)
    }

 /** 截取当前屏幕 JPEG/Base64（最长边压缩720px 节省 token）。挂起式：不阻塞主线*/
    private suspend fun captureScreenBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "截屏需要 Android 11+")
            return null
        }
        return withTimeoutOrNull(6000) {
            kotlin.coroutines.suspendCoroutine { cont ->
                val executor = ContextCompat.getMainExecutor(this@FinanceAccessibilityService)
                runCatching {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                val encoded = try {
                                    val buffer = screenshot.hardwareBuffer
                                    if (buffer == null) null
                                    else {
                                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                        buffer.close()
                                        if (wrapped == null) null
                                        else {
                                            val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                            wrapped.recycle()
                                            software?.let { src ->
                                                val maxW = 720f
                                                val scale = if (src.width > maxW) maxW / src.width else 1f
                                                val scaled = Bitmap.createScaledBitmap(
                                                    src,
                                                    (src.width * scale).toInt().coerceAtLeast(1),
                                                    (src.height * scale).toInt().coerceAtLeast(1),
                                                    true
                                                )
                                                val bos = ByteArrayOutputStream()
                                                scaled.compress(Bitmap.CompressFormat.JPEG, 72, bos)
                                                if (scaled !== src) scaled.recycle()
                                                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "截屏编码失败", e)
                                    null
                                }
                                cont.resumeWith(Result.success(encoded))
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "截屏失败 errorCode=$errorCode（可能被安全策略拦截）")
                                cont.resumeWith(Result.success(null))
                            }
                        }
                    )
                }.onFailure {
                    Log.w(TAG, "takeScreenshot 异常: ${it.message}")
                    cont.resumeWith(Result.success(null))
                }
            }
        }
    }

 /** 视觉兜底：截DeepSeek 视觉模型定位目标 手势点击 */
    private suspend fun clickByVision(target: String): Boolean {
        postUILog("👁️ 视觉定位「$target」…")
        val b64 = captureScreenBase64()
            ?: run {
                postUILog("⚠️ 截屏失败，视觉定位不可用")
                return false
            }
        val norm = AIService.locateUiElement(b64, target)
            ?: run {
                postUILog("❌ 视觉模型未找到「$target」")
                return false
            }
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        var cx = (norm.first * w).coerceIn(0f, w - 1f)
        var cy = (norm.second * h).coerceIn(0f, h - 1f)
 // 订单入口类目标：点再往上挪一点（避让可能盖在下面的弹浮层
        if (target == "我的订单" || target == "待付款" || target == "待收货") {
            cy = (cy - 35f).coerceAtLeast(h * 0.12f)
        }
        if (cy < h * 0.12f) {
            postUILog("⚠️ 视觉定位「$target」落在顶部搜索区(y=${cy.toInt()})，跳过防误触")
            return false
        }
        postUILog("🎯 视觉定位「$target」→ 点击(${cx.toInt()},${cy.toInt()})")
        return gestureClick(cx, cy)
    }

 /** 点掉常见的确提示弹窗按钮；返回是否点*/
    private fun dismissDialogIfAny(root: AccessibilityNodeInfo): Boolean {
        val dismissTexts = listOf("好的", "我知道了", "暂不", "以后再说", "取消", "关闭", "跳过", "忽略")
        return dismissTexts.any { tryClickText(root, it) }
    }

    /** 悬浮窗提示：WindowManager/View 操作必须回主线程，这里统一封装 */
    private fun showStatusSafe(text: String) {
        runCatching {
            ContextCompat.getMainExecutor(this).execute {
                runCatching { floatingWindowManager.showStatus(text) }
            }
        }
    }

 /** 开始抓取：显示常驻"抓取+ 点击停止"控制悬浮窗（不自动隐藏，直到抓取结束/被停止） */
    private fun beginFetchUI(text: String) {
        runCatching {
            ContextCompat.getMainExecutor(this).execute {
                runCatching { floatingWindowManager.showFetchControl(text) { requestStopFetch() } }
            }
        }
    }

    /** 抓取过程中更新控制悬浮窗文案 */
    private fun updateFetchUI(text: String) {
        runCatching {
            ContextCompat.getMainExecutor(this).execute {
                runCatching { floatingWindowManager.updateFetchStatus(text) }
            }
        }
    }

 /** 抓取结束：收起控制悬浮窗；finalText 非空时再5 秒自动隐藏的普通状态窗提示结果 */
    private fun endFetchUI(finalText: String?) {
        runCatching {
            ContextCompat.getMainExecutor(this).execute {
                runCatching { floatingWindowManager.hideFetchControl() }
                if (finalText != null) {
                    runCatching { floatingWindowManager.showStatus(finalText) }
                }
            }
        }
    }

 /** 停止当前抓取：悬浮窗"停止"按钮与外部停止指令共用入*/
    private fun requestStopFetch() {
        if (!isBillFetching) {
            postUILog("ℹ️ 当前没有进行中的抓取任务")
            return
        }
        postUILog("⏹ 正在停止抓取…")
        updateFetchUI("⏹ 正在停止…")
        stopAlipayBillFetch() // 两个抓取引擎都挂billFetchJob 上，取消即停；收尾由任务 finally 完成
    }

    /**
 * 识别并点掉系统「跳转应用许/ 允许打开其它应用」类弹窗（小MIUI 常见
 * 无障碍服务跨应用拉起时会弹「理想要打开 淘宝」）。只处理目标应用之外的系统弹窗，
 * 避免误点目标应用内的「允许」文案
     */
    private fun dismissJumpDialogIfAny(root: AccessibilityNodeInfo): Boolean {
        val pk = root.packageName?.toString() ?: return false
        if (pk == packageName || pk == PKG_ALIPAY || pk == PKG_MEITUAN || pk == PKG_TAOBAO) return false
        val texts = mutableListOf<String>()
        collectAllTextLimited(root, texts, 40)
        val joined = texts.joinToString(" ")
        val jumpish = joined.contains("跳转") || joined.contains("仅本次") ||
                joined.contains("总是允许") || joined.contains("想要打开") ||
                joined.contains("打开应用") || joined.contains("前往查看") ||
                (joined.contains("允许") && (joined.contains("打开") || joined.contains("跳转")))
        if (!jumpish) return false
        Log.d(TAG, "🔐 检测到系统跳转许可弹窗 pkg=$pk texts=${texts.filter { it.isNotBlank() }.take(14)}")
        postUILog("🔐 检测到系统「跳转」许可弹窗，点击允许…")
        for (c in listOf("总是允许", "仅本次允许", "允许", "前往", "继续", "仍要打开")) {
            if (tryClickText(root, c)) return true
        }
        return false
    }

    /** 截取当前窗口可见文本/描述（限量，供日志排查；desc 行加前缀便于识别淘宝等） */
    private fun snapshotTexts(root: AccessibilityNodeInfo, max: Int): List<String> {
        val list = mutableListOf<String>()
        collectSnapshotLimited(root, list, max)
        return list.filter { it.isNotBlank() }
    }

    private fun collectSnapshotLimited(node: AccessibilityNodeInfo, list: MutableList<String>, max: Int) {
        if (list.size >= max) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) list.add(t)
        else if (!d.isNullOrEmpty()) list.add("📎$d")
        if (list.size >= max) return
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSnapshotLimited(it, list, max) }
            if (list.size >= max) return
        }
    }

    private fun collectAllTextLimited(node: AccessibilityNodeInfo, list: MutableList<String>, max: Int) {
        if (list.size >= max) return
        if (!node.text.isNullOrEmpty()) list.add(node.text.toString().trim())
        if (list.size >= max) return
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllTextLimited(it, list, max) }
            if (list.size >= max) return
        }
    }

    private fun screenHasBillRows(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectAllText(root, texts)
        return texts.any { BILL_AMOUNT_REGEX.matches(it.trim()) }
    }

 /** 商品/美食信息流特征词：出现较多即说明当前Feed 而非账单列表 */
    private val feedMarkers = listOf(
        "好评榜", "热销", "已售", "月售", "加购", "首单价", "补贴后", "直降价", "券后", "包邮",
        "猜你喜欢", "立即购买", "去结算", "起送", "营业中", "评分", "进店", "下单立减"
    )

 /** 是否为商美食信息流页（订账单列表则返false）。text content-desc 都纳入判*/
    private fun looksLikeShoppingFeed(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectSnapshotLimited(root, texts, 90)
        if (texts.any {
                it.contains("已完成") || it.contains("待付款") || it.contains("待发货") ||
                        it.contains("待收货") || it.contains("待评价") || it.contains("交易成功") ||
                        it.contains("订单编号") || it.contains("订单详情")
            }
        ) return false
        val hits = texts.count { t -> feedMarkers.any { t.contains(it) } }
        return hits >= 3
    }

 /** 外部平台「订账单明细页」判定：金额 且非商品信息流（text+desc 双通道感知*/
    private fun isBillRowsPage(root: AccessibilityNodeInfo): Boolean {
        if (looksLikeShoppingFeed(root)) return false
        val texts = mutableListOf<String>()
        collectSnapshotLimited(root, texts, 120)
 // 实付金额是真text 节点（desc 行带 📎 前缀不会误算
        val amounts = texts.count { BILL_AMOUNT_REGEX.matches(it.trim()) }
        if (amounts < 2) return false
 // 账单行上下文词（信息流里的价格带往往没有这些；desc text 都算
        return texts.any {
                it.contains("已完成") || it.contains("待付款") || it.contains("待发货") ||
                    it.contains("下单时间") || it.contains("已完成") || it.contains("待付款") ||
                    it.contains("待发货") || it.contains("待收货") || it.contains("待评价")
        }
    }

 /** 文字解析当前屏：支付一行式"格式商家12.34元，，分类，，时）优先，兼容独立金额 */
    private fun parseBillScreenText(seen: MutableSet<String>, agg: ExternalAgg): Int {
        val root = activeRoot() ?: return 0
        val texts = mutableListOf<String>()
        collectAllText(root, texts)
        Log.d(TAG, "📱 账单屏文本(${texts.size}): ${texts.filter { it.isNotBlank() }.take(60)}")

        var added = 0
        var anchorCount = 0
        var timeCursorMs: Long? = null
 // 模式1：一行式 "商家名，-12.34；日期标题行在循环里顺带作为时间锚点
        val inline = BillAmountText.ROW_INLINE_YUAN_REGEX
        for (raw in texts) {
            val t = raw.trim()
            if (t.isEmpty()) continue
 // 纯日时间作为其后各笔的时间锚
            if (t.length <= 40) parseBillTimeText(t)?.let { timeCursorMs = it; anchorCount++ }
            if (t.length > 80) continue
            val m = inline.find(t) ?: continue
            if (m.groupValues[2] != "-") continue // 只记支出（负号行；收退款为 + 或其它形态）
            val amount = m.groupValues[3].toDoubleOrNull() ?: continue
            if (amount <= 0.0) continue
            val merchant = m.groupValues[1].trim().removeSuffix("，").take(24)
            if (merchant.length < 2 || isNoiseText(merchant)) continue
 // 支付宝行合并节点"：支付时间在行尾（今14:42 / 09-04 22:14 …），行内优先，独立日期行锚点兜
            val rowTime = extractRowTime(t) ?: timeCursorMs
            if (rowTime != null) timeCursorMs = rowTime
            val key = seenKeyFor(merchant, amount, rowTime)
            if (seen.size < billLimit && seen.add(key)) {
                AccessibilityEventRepository.postConsumption(
                    recordAt("支付宝账单", merchant, amount, rowTime)
                )
                agg.sum += amount
                agg.merchants[merchant] = agg.merchants.getOrDefault(merchant, 0.0) + amount
                added++
            }
        }
        if (added > 0) {
            Log.d(TAG, "支付宝文字[模式1]: 入账 $added，日期锚点 $anchorCount，最后锚=${fmtTimeForLog(timeCursorMs)}")
            return added
        }

 // 模式2（旧版兜底）：独立金额文+ 就近商家配对
        var cursor2 = timeCursorMs
        for ((i, raw) in texts.withIndex()) {
            val t = raw.trim()
            parseTimeFlexible(t)?.let { cursor2 = it; anchorCount++ }
            val amount = BILL_AMOUNT_REGEX.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val merchant = findMerchantNear(texts, i) ?: continue
            val key = seenKeyFor(merchant, amount, cursor2)
            if (seen.size < billLimit && seen.add(key)) {
                AccessibilityEventRepository.postConsumption(
                    recordAt("支付宝账单", merchant, amount, cursor2)
                )
                agg.sum += amount
                agg.merchants[merchant] = agg.merchants.getOrDefault(merchant, 0.0) + amount
                added++
            }
        }
        Log.d(TAG, "支付宝文字[模式2]: 入账 $added，日期锚点 $anchorCount，最后锚=${fmtTimeForLog(cursor2)}")
        return added
    }

    /** 在金额文本之前寻找最近的"商家/说明"文本 */
    private fun findMerchantNear(texts: List<String>, amountIndex: Int): String? =
        MerchantText.findMerchantNear(texts, amountIndex)

 /** 账单行之间的非商家噪声（日期/分类/状导航/额度等） */
    private fun isNoiseText(t: String): Boolean = MerchantText.isNoiseText(t)

 // ============== 支付时间提取（历史账单抓取：按每笔真实付费时间入账，不再全部记为"抓取当天"==============

 /** 构造入账记录：拿到真实支付时间用它；解析不到时才退抓取当前时刻" */
    private fun recordAt(source: String, merchant: String, amount: Double, timeMs: Long?): ConsumptionRecord {
        val occurred = timeMs ?: System.currentTimeMillis()
        Log.d(TAG, "💰 入账[$source] $merchant ¥${"%.2f".format(amount)} " +
                (if (timeMs == null) "（未识别支付时间，记抓取时刻）"
                else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(occurred))))
        return ConsumptionRecord(source = source, merchant = merchant, amount = amount, occurredAtMs = occurred)
    }

 /** 诊断日志用：毫秒 "yyyy-MM-dd HH:mm"，null " */
    private fun fmtTimeForLog(ms: Long?): String = BillKeys.fmtForLog(ms)

 /** 引擎内去重键：加发生，同商家同金额、不同天支付的账单不再被误合*/
    private fun seenKeyFor(merchant: String, amount: Double, timeMs: Long?): String =
        BillKeys.seenKey(merchant, amount, timeMs)

    /**
 * 解析账单日期分组标题里的"支付时间"文本 本地毫秒；解析不到返null
 * 支持：今昨天 [HH:mm]、yyyy[-/年]M[-/月]d[日] [HH:mm]、M月d日[(周X)] [HH:mm]
 * 规则：明显未来时间视为无效；无年份的 M月d最近一次不晚于今天"（跨年自动前推）
     */
    /** 解析账单日期/时间文本 → 本地毫秒（规则本体见 parsing/BillTimeParser.parse） */
    private fun parseBillTimeText(raw: String?): Long? = BillTimeParser.parse(raw)

    /**
 * 支付宝账单行合并文本节点"：整= 商家金额元，，分类，，支付时间
 * 时间在行今天 14:42 / 昨天 21:26 / 09-04 22:14 / 2024-07-15 12:30
 * 这里扫整行取【最后一个】时间短语交parseBillTimeText 解析
     */
    /** 扫整行取【最后一个】时间短语解析（规则本体见 parsing/BillTimeParser.extractRowTime） */
    private fun extractRowTime(row: String): Long? = BillTimeParser.extractRowTime(row)

    /** 容忍带前缀/混排的文本：整串匹配失败则扫行内时间短语（本体见 parsing/BillTimeParser.parseFlexible） */
    private fun parseTimeFlexible(raw: String?): Long? = BillTimeParser.parseFlexible(raw)

 /** 向上滑动一屏（从屏72% 高度滑到 30%*/
    private fun swipeUp(): Boolean {
        return try {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            val path = android.graphics.Path().apply {
                moveTo(w * 0.5f, h * 0.72f)
                lineTo(w * 0.5f, h * 0.28f)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350)
                )
                .build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "上滑手势失败", e)
            false
        }
    }

    private fun debugPrintCurrentScreen() {
        val root = activeRoot() ?: return
        val texts = mutableListOf<String>()
        collectAllText(root, texts)
        Log.d(TAG, "📱 当前屏幕文本: ${texts.filter { it.isNotBlank() }.take(50)}")
    }

    /** 在当前窗口找一个文本节点并点击它（或其最近可点击祖先）；失败则用手势坐标兜底 */
 /** 跳过明显不该点的目标：顶部搜索栏区域（y<12%）与输入框（EditText）及其祖先，误触搜索 */
    private fun isSafeClickTarget(node: AccessibilityNodeInfo): Boolean {
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.centerY() < resources.displayMetrics.heightPixels * 0.12f) return false
            var p: AccessibilityNodeInfo? = node
            var depth = 0
            while (p != null && depth < 5) {
                if (p.className?.toString()?.contains("EditText") == true) return false
                p = p.parent
                depth++
            }
        } catch (t: Throwable) {
            return false
        }
        return true
    }

    private fun tryClickText(root: AccessibilityNodeInfo, label: String): Boolean {
 // 统一用手势点击（ACTION_CLICK 在部分应用无效会"假成改为定位可点容器中心真实点击
        val nodes = root.findAccessibilityNodeInfosByText(label)
        var bestExact: AccessibilityNodeInfo? = null
        var bestContains: AccessibilityNodeInfo? = null
        fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var p: AccessibilityNodeInfo? = n
            var depth = 0
            while (p != null && depth < 6) {
                if (p.isClickable && isSafeClickTarget(p)) return p
                p = p.parent
                depth++
            }
            return null
        }
        // 第一轮：精确定位
        for (n in nodes) {
            val tv = n.text?.toString()?.trim()
            val dv = n.contentDescription?.toString()?.trim()
            if (tv == label || dv == label) {
                val ca = clickableAncestor(n)
                if (ca != null && bestExact == null) bestExact = ca
            }
        }
        if (bestExact != null) {
            val rect = android.graphics.Rect()
            bestExact!!.getBoundsInScreen(rect)
            Log.d(TAG, "👉 精确手势点「$label」@(${rect.centerX()},${clickYOf(rect, label)})")
            return gestureClick(rect.centerX().toFloat(), clickYOf(rect, label).toFloat())
        }
 // 第二轮：任意包含该文本的节点（desc 也扫，因 ByText 只匹text
        val h = resources.displayMetrics.heightPixels
        var scanned = 0
        fun scanContains(node: AccessibilityNodeInfo) {
            if (scanned++ > 600 || bestContains != null) return
            val tv = node.text?.toString() ?: ""
            val dv = node.contentDescription?.toString() ?: ""
            if (tv.contains(label) || dv.contains(label)) {
                val ca = clickableAncestor(node)
                if (ca != null) {
                    val r = android.graphics.Rect()
                    ca.getBoundsInScreen(r)
                    if (r.centerY() > h * 0.12f && r.centerY() < h * 0.95f) bestContains = ca
                    return
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { scanContains(it) }
        }
        try {
            scanContains(root)
        } catch (t: Throwable) {
            Log.w(TAG, "tryClickText 扫描异常: ${t.message}")
        }
        if (bestContains != null) {
            val rect = android.graphics.Rect()
            bestContains!!.getBoundsInScreen(rect)
            Log.d(TAG, "👉 手势点「$label」@(${rect.centerX()},${clickYOf(rect, label)})")
            return gestureClick(rect.centerX().toFloat(), clickYOf(rect, label).toFloat())
        }
        return false
    }

    /** 手势点击指定坐标 */
    private fun gestureClick(x: Float, y: Float): Boolean {
        return try {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "手势点击失败", e)
            false
        }
    }

    private fun stopEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = System.currentTimeMillis()
        runCatching { UserSettings(this).touchA11yEvent(now) } // #7 诊断：事件流动时间戳（节流写盘）
 // 统一节流：无论是否命中支付页，每 2 秒最多处理一个事件（防刷耗电
        if (now - lastProcessedTime < THROTTLE_MS) return
        lastProcessedTime = now

        when (event.packageName) {
            PKG_ALIPAY -> handleAlipayEvent(event)
            PKG_MEITUAN -> {
                handleMeituanEvent(event)
                maybeJudgeCurrentOrder(PKG_MEITUAN)
            }
            PKG_TAOBAO -> {
                handleTaobaoEvent(event)
                maybeJudgeCurrentOrder(PKG_TAOBAO)
            }
            PKG_ELE -> maybeJudgeCurrentOrder(PKG_ELE)
        }
    }

    /**
 * 下单AI 判断：在美团/淘宝确认订单/去支去结页自动识别商金额
 * 结合预算给出"合谨慎/不建。节流：同一候60 秒内只判断一次；抓取/本应用内不触发
 * 可用「我下单前判断」开关整体关闭（默认开）
     */
    private fun maybeJudgeCurrentOrder(pkg: String) {
        if (isBillFetching) return
        val now = System.currentTimeMillis()
 // 仅做 4 秒防抖（挡事件洪峰）；不同候选页可随时再判，不受长闸门影
        if (now - lastJudgeAt < 4000L && lastJudgeKey.isNotEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            delay(400)
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@launch
            if (root.packageName?.toString() != pkg) return@launch

 // 「我的」开关：默认开。关掉后结算页不再弹判断窗（抓取/实时点评不受影响
            if (!UserSettings(this@FinanceAccessibilityService).judgeBeforeOrderEnabled) return@launch

 // 确认下单类标记（text desc）：自底向上采集——结支付按钮与「合计」行都在页面
 // 最底部，旧版按树先序从头截 150 条会把它们漏"真结算页不弹"。text/desc 300
            val texts = mutableListOf<String>()
            val descs = mutableListOf<String>()
            collectTokensForJudge(root, texts, descs, JUDGE_TOKEN_CAP)

            val markers = ORDER_CTA_STRONG + ORDER_CTA_WEAK + SUM_LABELS
            val all = texts + descs
            val strongCta = all.any { tok -> ORDER_CTA_STRONG.any { tok.contains(it) } }
            val weakCta = all.any { tok -> ORDER_CTA_WEAK.any { tok.contains(it) } }
            val sumInfo = all.any { tok -> SUM_LABELS.any { tok.contains(it) } }
            val feedish = looksLikeShoppingFeed(root)

 // 判定顺序：先CTA/合计命中 再对"弱命做信息流拦截
 // 强结算词（提交订去支付…）只在结算/收银页出现，命中即判，不再被 feed 特征提前 return
 // 「立即购去结算」等详情页词已降为弱词（需配合合计/地址），浏览商品时因此不会误弹
            val ctaHit = strongCta || (weakCta && sumInfo)
            if (!ctaHit) {
                logJudgeMiss("无CTA/合计特征", texts, descs, strongCta, weakCta, sumInfo, feedish)
                return@launch
            }
            if (!strongCta && feedish) {
                logJudgeMiss("弱命中但疑似信息流", texts, descs, strongCta, weakCta, sumInfo, feedish)
                return@launch
            }

 // 本单金额 = 页面最下方的真¥（合应付行通常在底部）：先取下半屏 y 最大者，否则y 最大
            val h = resources.displayMetrics.heightPixels
            // 行级采集：只收屏幕可视区文本行（列表预渲染的屏外价格节点一律不要）
            data class PriceLine(val text: String, val top: Int, val bottom: Int, val cy: Int)
            val priceLines = ArrayList<PriceLine>()
            val seenTexts = HashSet<String>()
            var scannedAmt = 0
            fun scanAmt(node: android.view.accessibility.AccessibilityNodeInfo) {
                if (scannedAmt++ > 500) return
                val t = node.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty() && t.length <= 80) {
                    val r = android.graphics.Rect()
                    runCatching { node.getBoundsInScreen(r) }
                    if (!r.isEmpty && r.top >= 0 && r.bottom <= h && seenTexts.add(t)) {
                        priceLines.add(PriceLine(t, r.top, r.bottom, r.centerY()))
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { scanAmt(it) }
            }
            try { scanAmt(root) } catch (t: Throwable) { /* 忽略 */ }
            // 金额挑选：费用词行(共减/立减/券/运费…)排除；其拆分的独立"¥1.8"式邻行也排除；
            // 含应付词(实付/合计/支付/提交…)行内金额优先，否则取最下方可用金额
            val money = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
            val feeWords = listOf("共减", "立减", "已减", "满减", "券", "红包", "运费", "配送", "打包", "起送", "优惠", "代金")
            val payWords = listOf("应付", "实付", "合计", "共需", "需付", "支付", "提交", "确认", "结算", "总价", "小计")
            val feeLines = ArrayList<PriceLine>()
            for (line in priceLines) {
                if (feeWords.any { w -> line.text.contains(w) }) feeLines.add(line)
            }
            val candidates = ArrayList<Pair<Double, PriceLine>>()
            for (line in priceLines) {
                val m = money.find(line.text) ?: continue
                val amount = m.groupValues[1].toDoubleOrNull() ?: continue
                val hasFee = feeWords.any { w -> line.text.contains(w) }
                // 拆分行判定：与费用行重叠(同 y 带)或紧贴其上(≤80px)视为同一费用行的一部分
                val nearFee = feeLines.any { f ->
                    (f.top < line.bottom && f.bottom > line.top) ||
                            (f.bottom <= line.top && line.top - f.bottom <= 80)
                }
                if (!hasFee && !nearFee) candidates.add(amount to line)
            }
            val sorted = candidates.sortedByDescending { it.second.cy }
            val chosen = sorted.firstOrNull { (_, l) -> payWords.any { w -> l.text.contains(w) } }
                ?: sorted.firstOrNull()
            val amount = chosen?.first ?: return@launch
            Log.d(
                TAG,
                "🛒 金额候选(可见, 底→上): " + sorted.take(8)
                    .joinToString { a -> "¥" + "%.2f".format(a.first) + "@y" + a.second.cy } +
                        " | 选定 ¥" + "%.2f".format(amount) + " | 行=" + chosen.second.text.take(24)
            )
            val merchant = pickMerchantName(root, markers, h) ?: "待下单商品"

            val key = "$pkg|$merchant|$amount"
            if (key == lastJudgeKey && now - lastJudgeAt < 60_000L) return@launch // 同一60 秒去
            lastJudgeKey = key
            lastJudgeAt = now

            val snap = BudgetPlanner.snapshotMonth(this@FinanceAccessibilityService)
            val cat = BillCategories.categorize(merchant, pkg)
            val catRemaining = snap.catBudget[cat]?.let { it - snap.catSpentOf(cat) }
            val verdict = AIService.judgePurchase(merchant, amount, cat, snap.remaining, catRemaining)
            val hitStrong = ORDER_CTA_STRONG.filter { s -> all.any { t -> t.contains(s) } }
            val hitWeak = ORDER_CTA_WEAK.filter { s -> all.any { t -> t.contains(s) } }
            val hitSum = SUM_LABELS.filter { s -> all.any { t -> t.contains(s) } }
            Log.d(
                TAG,
                "🛒 下单前判断[强=$hitStrong 弱=$hitWeak 合计=$hitSum feed=$feedish] " +
                        "$merchant ¥${"%.2f".format(amount)}（$cat）→ $verdict"
            )
            runCatching {
                ContextCompat.getMainExecutor(this@FinanceAccessibilityService).execute {
                    runCatching { floatingWindowManager.showVerdict("$merchant ¥${"%.2f".format(amount)}\n$verdict") }
                }
            }
        }
    }

    /**
 * 下单判断专用采集：按"屏幕顺序把页text / content-desc 各自收满 cap 条
 * 结算/支付 CTA 与「合计」行位于页面最底部 —旧版 pre-order 从头截断会把它们漏掉
 * 对同一父节点先排子节点（y 大的靠前）再递归，天然让底部内容先进池
     */
    private fun collectTokensForJudge(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        descs: MutableList<String>,
        cap: Int
    ) {
        if (texts.size >= cap && descs.size >= cap) return
        val children = (0 until node.childCount).mapNotNull { i -> node.getChild(i) }
        if (children.isNotEmpty()) {
            val sorted = children.sortedByDescending { c ->
                val r = android.graphics.Rect()
                runCatching { c.getBoundsInScreen(r) }
                r.top // 屏幕坐标 y 越大越靠下，先遍
            }
            for (c in sorted) collectTokensForJudge(c, texts, descs, cap)
        }
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && texts.size < cap) texts.add(t)
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrEmpty() && descs.size < cap) descs.add(d)
    }

    /**
 * 挑订单摘要里的商家名：候选文本的 y 越接近屏45% 高度越优
 * （结算页顶部是地址、底部是金额/按钮，摘要卡中段才放店名）
 * 跳过运费/红包/文案行等 chrome 词；允许店名含数字（档口"）
     */
    private fun pickMerchantName(
        root: AccessibilityNodeInfo,
        markers: Array<String>,
        screenH: Int
    ): String? {
        val chrome = arrayOf(
            "配送", "自取", "预计", "送达", "红包", "券", "立减", "代金",
            "请适量", "环保", "餐具", "口味", "备注", "选择", "数量", "商品", "已选"
        )
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var bestY = Int.MAX_VALUE
        var scanned = 0
        fun walk(node: AccessibilityNodeInfo) {
            if (scanned++ > 600) return
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.length in 2..24 && !t.startsWith("¥") && !t.startsWith("￥") &&
                t.any { it in '\u4e00'..'\u9fff' } &&
                markers.none { t.contains(it) } && chrome.none { t.contains(it) }
            ) {
                val r = android.graphics.Rect()
                runCatching { node.getBoundsInScreen(r) }
                if (!r.isEmpty) {
                    val cy = r.centerY()
                    val dist = kotlin.math.abs(cy - (screenH * 0.45f).toInt())
                    if (dist < bestDist || (dist == bestDist && cy < bestY)) {
                        bestDist = dist
                        bestY = cy
                        best = t
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
        }
        return try {
            walk(root)
            best
        } catch (t: Throwable) {
            null
        }
    }

 /** "接近结算页但最终未调试日志0s 采样一条，附页面底token 便于真机复现调词 */
    private fun logJudgeMiss(
        reason: String,
        texts: List<String>,
        descs: List<String>,
        strong: Boolean,
        weak: Boolean,
        sum: Boolean,
        feedish: Boolean
    ) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - judgeMissLogAt < 20_000L) return
        judgeMissLogAt = nowMs
        val tail = texts.take(8).joinToString(" | ") { it.trim().take(28) }
        val dTail = descs.take(6).joinToString(" | ") { "📎" + it.trim().take(28) }
        Log.d(
            TAG,
            "🛒 结算页未判[$reason] strong=$strong weak=$weak 合计=$sum feed=$feedish " +
                    "底部token(前8): ${if (tail.isEmpty()) "〈空〉" else tail} " +
                    "desc(前6): ${if (dTail.isEmpty()) "〈空〉" else dTail}"
        )
    }

    // ======================
 // 🤖 AI 推荐自动化流
    // ======================

    private fun performAIRecommendedSearch(restaurantName: String) {
        if (restaurantName.isEmpty()) return

        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "🔍 开始执行AI推荐搜索: '$restaurantName'")
            delay(1200)

            debugPrintMeituanUI()

            if (clickSearchBoxInMeituan()) {
                Log.d(TAG, "✅ 成功点击美团搜索框")
                delay(600)

                setClipboardText(restaurantName)
                pasteInSearchBox()
                Log.d(TAG, "📋 已将 '$restaurantName' 写入剪贴板并尝试粘贴")
                delay(1000)

                if (clickFirstSearchResult()) {
                    Log.d(TAG, "✅ 成功点击第一个搜索结果")
                } else {
                    Log.w(TAG, "❌ 未找到可点击的搜索结果")
                    debugPrintMeituanUI()
                }
                Log.d(TAG, "🏁 完成AI推荐自动化流程: $restaurantName")
            } else {
                Log.w(TAG, "⚠️ 未找到美团搜索框，尝试坐标点击兜底")
                fallbackClickSearchByCoordinate()
                delay(1000)
                pasteInSearchBox()
                clickFirstSearchResult()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setClipboardText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI推荐", text))
    }

    private fun clickSearchBoxInMeituan(): Boolean {
        val root = activeRoot() ?: return false

        val searchIds = listOf(
            "com.sankuai.meituan:id/search_edit",
            "com.sankuai.meituan:id/search_bar",
            "com.sankuai.meituan:id/search_view",
            "com.sankuai.meituan:id/tv_search"
        )

        for (id in searchIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "🔍 按ID找到搜索框: $id (数量: ${nodes.size})")
                nodes.firstOrNull()?.let { node ->
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    } else {
                        Log.w(TAG, "⚠️ 找到搜索框但不可点击: $id")
                    }
                }
            }
        }

        val searchTexts = listOf("搜索商家或商品", "搜索", "请输入商家名称")
        for (text in searchTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "🔍 按文本找到搜索框: '$text' (数量: ${nodes.size})")
                nodes.firstOrNull()?.let { node ->
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }

        Log.w(TAG, "❌ 未找到任何可点击的搜索框")
        return false
    }

    private fun pasteInSearchBox() {
        val root = activeRoot() ?: return
        val candidates = listOf(
            "com.sankuai.meituan:id/search_edit",
            "android:id/edit"
        )
        for (id in candidates) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { node ->
                if (node.isEditable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    return
                }
            }
        }
        val allEditable = root.findAccessibilityNodeInfosByViewId("android:id/edit")
        if (allEditable.isNotEmpty()) {
            allEditable[0].performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    private fun clickFirstSearchResult(): Boolean {
        val root = activeRoot() ?: return false

        val titleIds = listOf(
            "com.sankuai.meituan:id/poi_title",
            "com.sankuai.meituan:id/tv_poi_name"
        )

        for (id in titleIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "🔍 找到餐厅标题: $id (数量: ${nodes.size})")
                nodes.firstOrNull()?.let { titleNode ->
                    var parent = titleNode.parent
                    var depth = 0
                    while (parent != null && depth < 5) {
                        if (parent.isClickable) {
                            Log.d(TAG, "✅ 找到可点击父容器 (深度: $depth)")
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return true
                        }
                        parent = parent.parent
                        depth++
                    }
                    if (titleNode.isClickable) {
                        Log.d(TAG, "✅ 餐厅标题自身可点击")
                        titleNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }

        Log.w(TAG, "❌ 未找到任何餐厅标题或可点击容器")
        return false
    }

    // ======================
 // 🔄 兜底方案：坐标点
    // ======================

    private fun fallbackClickSearchByCoordinate() {
        val centerX = resources.displayMetrics.widthPixels / 2
        val centerY = (resources.displayMetrics.heightPixels * 0.12).toInt() // 顶部12%
        Log.d(TAG, "📍 执行坐标点击: ($centerX, $centerY)")
        performClick(centerX, centerY)
    }

    private fun performClick(x: Int, y: Int) {
        val clickPath = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val clickGesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                clickPath, 0, 50
            ))
            .build()
        dispatchGesture(clickGesture, null, null)
    }

    // ======================
    // 🔍 调试函数
    // ======================

    private fun debugPrintMeituanUI() {
        val root = activeRoot() ?: run {
            Log.w(TAG, "⚠️ rootInActiveWindow 为空（界面未加载）")
            return
        }

        val allText = mutableListOf<String>()
        collectAllText(root, allText)
        val nonEmptyText = allText.filter { it.isNotBlank() }.take(10)
        Log.d(TAG, "📱 美团当前界面文本: $nonEmptyText")

        val searchBoxFound = root.findAccessibilityNodeInfosByViewId("com.sankuai.meituan:id/search_edit").isNotEmpty()
        val poiTitleFound = root.findAccessibilityNodeInfosByViewId("com.sankuai.meituan:id/poi_title").isNotEmpty()
        Log.d(TAG, "🔍 关键控件检查 - 搜索框: $searchBoxFound, 餐厅标题: $poiTitleFound")
    }

    // ======================
 // 💳 实时支付监听：付款成功页 自动入账 + 悬浮+ AI 建议
    // ======================

 /** 统一实时入账：跨通道去重 强类型入悬浮AI 建议 */
    private fun onRealtimeConsumption(source: String, merchant: String, amount: Double): Boolean {
        if (amount <= 0.0) return false
 // 跨通道去重 秒内同来源同金额视为同一笔（页面通道通常先到，商家名更准
        if (!RealtimeGate.acquire(source, amount)) {
            Log.d(TAG, "⏭ 实时[$source] ¥$amount 与另一通道重复，跳过（merchant=$merchant）")
            return false
        }
        val m = merchant.ifBlank { "$source 消费" }
        val key = "$m|$amount|$source"
        if (!processedRecords.add(key)) return false
        lastProcessedTime = System.currentTimeMillis()
        AccessibilityEventRepository.postConsumption(
            ConsumptionRecord(source = source, merchant = m, amount = amount)
        )
        Log.d(TAG, "✅ 实时[$source]: $m - ¥$amount")
 // 预算联动（IO）：算当分类预算剩余 悬浮窗提+ AI 点评带上预算上下
        CoroutineScope(Dispatchers.IO).launch {
            lastRealtimeRemindAt = System.currentTimeMillis() // 供 AI 点评上胶囊
            try {
                val category = BillCategories.categorize(m, source)
                val snap = BudgetPlanner.snapshotMonth(this@FinanceAccessibilityService)
                showStatusSafe("💳 $source 支出 ¥${"%.2f".format(amount)} · ${snap.shortReminder(category)}")
                AIService.processConsumptionEvent("$m ¥$amount", snap.aiBudgetContext())
            } catch (t: Throwable) {
                Log.e(TAG, "预算联动失败", t)
                showStatusSafe("💳 $source 支出 ¥${"%.2f".format(amount)}")
            }
        }
        return true
    }

 /** 翻历史列消息流时付款成功￥x"等旧记录文案 用时间词识别并跳过，避免误报 */
    private fun isFeedLike(tokens: List<String>): Boolean =
        tokens.any { it.contains("最近消费") || it.contains("小时前") || it.contains("分钟前") ||
                it.contains("秒前") || it.contains("天前") || it.contains("刚刚") }

    private fun handleAlipayEvent(event: AccessibilityEvent) {
        event.source?.let { root ->
            val allText = mutableListOf<String>()
            collectAllText(root, allText)
            val joined = allText.joinToString(" ")

 // 判定是否支付/转账成功（含转账成功/已转交易成功等，并排除历史流误报
            val success = !isFeedLike(allText) && allText.any {
                it.contains("账单详情") || it.contains("交易成功") || it.contains("支付成功") ||
                        it.contains("付款成功") || it.contains("转账成功") || it.contains("已转出") ||
                        it.contains("转出成功")
            }
            if (!success) {
 // 降噪：非付款页日志每 5 条打1 条（配合 2s 事件节流
                if (a11yNonPayLog++ % 5L == 0L) {
                    Log.d(TAG, "💳 支付宝事件但非付款成功页，文本(前12): " +
                            allText.filter { it.isNotBlank() }.take(12))
                }
                return
            }

            val amount = Regex("""支出[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""").find(joined)
                ?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""(?:已转出|转出)[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""").find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""(?:付款成功|支付成功|转账成功|成功)[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""").find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""实付[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""").find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                // 兜底：支付宝转账成功页金额是独立节点，如 "转账成功 0.01 收款人…"
                ?: Regex("""(?:收款|收款成功|转账成功|成功)[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""").find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
            if (amount == null) {
                Log.d(TAG, "💳 支付宝成功页但未提取到金额，文本: " +
                        allText.filter { it.isNotBlank() }.take(20))
                return
            }

            var merchant = "支付宝消费"
            val goodsIndex = allText.indexOfFirst { it.contains("商品说明") }
            if (goodsIndex >= 0 && goodsIndex + 1 < allText.size) {
                var c = allText[goodsIndex + 1].trim()
                if (c.contains("外卖订单")) c = c.substringBefore("外卖订单").trim()
                if (c.isNotEmpty()) merchant = c
            }
            if (merchant == "支付宝消费") {
 // 收款方：令牌列表收款之后一个节点即收款
                val payeeIdx = allText.indexOfFirst { it.contains("收款人") }
                val payeeFromNext = if (payeeIdx >= 0 && payeeIdx + 1 < allText.size) {
                    allText[payeeIdx + 1].trim()
                } else null
                val payee = payeeFromNext
                    ?: allText.firstOrNull { it.contains("转账给") }?.substringAfter("转账给")?.trim()
                    ?: allText.firstOrNull { it.contains("转给") }?.substringAfter("转给")?.trim()
                    ?: allText.firstOrNull { it.contains("收款") && it.contains("付款") }?.let {
                        it.substringAfter("收款").substringBefore("付款").trim()
                    }
                    ?: allText.firstOrNull { it.contains("付款给") }?.substringAfter("付款给")?.trim()
                if (!payee.isNullOrEmpty()) merchant = payee.take(24)
            }
            onRealtimeConsumption("支付宝", merchant, amount)
        }
    }

    private fun handleMeituanEvent(event: AccessibilityEvent) {
        event.source?.let { root ->
            val allText = mutableListOf<String>()
            collectAllText(root, allText)
            if (allText.none { it.contains("订单详情") } && !allText.any { it.contains("支付成功") }) return
            val amount = Regex("""实付[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""")
                .find(allText.joinToString(" "))
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: return
            val merchant = allText.firstOrNull {
                it.isNotBlank() && !it.contains("订单详情") && !it.contains("实付") &&
                        !it.contains("订单编号") && !it.contains("支付")
            }?.trim()?.take(24) ?: "美团外卖"
            onRealtimeConsumption("美团", merchant, amount)
        }
    }

    private fun handleTaobaoEvent(event: AccessibilityEvent) {
        event.source?.let { root ->
            val allText = mutableListOf<String>()
            collectAllText(root, allText)
            val joined = allText.joinToString(" ")
            val strong = (allText.any { it.contains("订单详情") || it.contains("交易成功") || it.contains("确认收货") }) ||
                    (allText.any { it.contains("支付成功") || it.contains("付款成功") } && !isFeedLike(allText))
            if (!strong) return

            val amount = Regex("""(?:实付|实付款|支付成功|付款成功)[¥￥]?([0-9]+(?:\.[0-9]{1,2})?)""")
                .find(joined)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""[¥￥]([0-9]+(?:\.[0-9]{1,2})?)""").find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
            if (amount == null) return

            val merchant = allText.firstOrNull {
                it.isNotBlank() && it.length in 2..24 && !it.contains("订单") && !it.contains("实付") &&
                        !it.contains("支付") && !it.contains("¥") && !it.contains("￥")
            }?.trim() ?: "淘宝闪购"
            onRealtimeConsumption("淘宝闪购", merchant, amount)
        }
    }

    // ======================
    // 🧩 工具函数
    // ======================

    private fun collectAllText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (!node.text.isNullOrEmpty()) {
            list.add(node.text.toString().trim())
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllText(it, list) }
        }
    }

    // ======================
    // 🧹 生命周期
    // ======================

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
        UserSettings(this).a11yServiceConnected = false
 // 隐藏悬浮
        floatingWindowManager.hideWindow() // 修复方法
        floatingWindowManager.hideFetchControl() // 顺带收起抓取控制
        stopAlipayBillFetch()
        stopEventListener()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "无障碍服务解除绑定（可能被用户关闭）")
        UserSettings(this).a11yServiceConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        adviceIslandJob?.cancel()
        adviceIslandJob = null
        super.onDestroy()
        Log.d(TAG, "无障碍服务销毁")
        UserSettings(this).a11yServiceConnected = false
 // 隐藏悬浮
        floatingWindowManager.hideWindow() // 修复方法
        floatingWindowManager.hideFetchControl() // 顺带收起抓取控制
        stopAlipayBillFetch()
        stopEventListener()
    }
}

// data/AccessibilityEventRepository.kt
// 全 App 的通信中枢（"快递站"）。
//   · records —— 强类型消费记录流：无障碍服务感知到的每一笔消费都会进入这里，UI 实时展示。
//   · events  —— 控制/消息流：AI 推荐结果等动作指令（不再承载消费原始数据，见 FinanceModels）。
// 事件缓存策略保留：即使暂时无人订阅也不会丢（SharedFlow extraBufferCapacity）。

package com.example.finance.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AccessibilityEventRepository {

    /** 由 FinanceApp 注入的本地账单库（用于持久化，可能为空=未初始化则跳过） */
    @Volatile
    private var db: FinanceDb? = null

    fun attachDb(database: FinanceDb) {
        db = database
    }

    /** 控制消息前缀：AI 推荐 → 交给无障碍服务自动执行 */
    const val PREFIX_AI_RECOMMENDATION = "[AI_RECOMMENDATION]"

    /** 控制消息前缀：抓取支付宝历史账单 → 交给无障碍服务执行 */
    const val PREFIX_FETCH_ALIPAY_BILLS = "[FETCH_ALIPAY_BILLS]"

    /** 控制消息前缀：抓取美团账单 → 交给无障碍服务执行 */
    const val PREFIX_FETCH_MEITUAN_BILLS = "[FETCH_MEITUAN_BILLS]"

    /** 控制消息前缀：抓取淘宝闪购账单 → 交给无障碍服务执行 */
    const val PREFIX_FETCH_TAOBAO_BILLS = "[FETCH_TAOBAO_BILLS]"

    /** 控制消息前缀：停止当前抓取（悬浮窗"停止"按钮 / 调试广播共用） */
    const val PREFIX_STOP_BILL_FETCH = "[STOP_BILL_FETCH]"

    /** 控制消息前缀：让无障碍服务 dump 当前窗口 text/desc/bounds 到 logcat（替代易坏的 uiautomator） */
    const val PREFIX_DUMP_SCREEN = "[DUMP_SCREEN]"

    /** 控制消息前缀：无障碍自带截图(API30+)测试/OCR 用 */
    const val PREFIX_A11Y_SHOT = "[A11Y_SHOT]"

    fun postA11yShotRequest() {
        _events.tryEmit(PREFIX_A11Y_SHOT)
    }

    // ---------- 消费记录流（强类型） ----------
    private val _records = MutableSharedFlow<ConsumptionRecord>(extraBufferCapacity = 64)
    val records: SharedFlow<ConsumptionRecord> = _records.asSharedFlow()

    fun postConsumption(record: ConsumptionRecord) {
        // 入口统一归一化来源（支付宝账单→支付宝 等），保证统计/筛选口径一致
        val normalized = record.copy(source = BillSources.canonical(record.source))
        _records.tryEmit(normalized)
        persist(normalized)
    }

    /** 落库：真实特征去重（来源+商家+金额+当天），重复自动忽略（insert 返回 -1） */
    private fun persist(record: ConsumptionRecord) {
        // 合法性守卫：金额必须在 (0, 100万) 且商家非空，脏数据直接丢弃不入账
        if (record.merchant.isBlank() ||
            !record.amount.isFinite() || record.amount <= 0.0 || record.amount > 1_000_000.0
        ) {
            android.util.Log.w("FinanceRepo", "丢弃非法消费记录: source=${record.source} merchant=${record.merchant} amount=${record.amount}")
            return
        }
        val database = db ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { database.billDao().insert(BillEntity.fromRecord(record)) }
        }
    }

    /** 清空本地账单库（测试/调试用） */
    fun clearAllBills() {
        val database = db ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { database.billDao().clearAll() }
        }
    }

    // ---------- 控制/消息流（字符串，仅承载指令与提示） ----------
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** 发布一条纯提示/指令消息（如 AI 推荐餐厅名） */
    fun postMessage(message: String) {
        _events.tryEmit(message)
    }

    /** 把 AI 推荐的餐厅名投递给无障碍服务去执行（仅"自动执行"开启时由 UI 调用） */
    fun postAIRecommendedRestaurant(restaurantName: String) {
        _events.tryEmit("$PREFIX_AI_RECOMMENDATION$restaurantName")
    }

    /** 请求无障碍服务：打开支付宝并自动抓取往期账单 */
    fun postAlipayBillFetchRequest() {
        _events.tryEmit(PREFIX_FETCH_ALIPAY_BILLS)
    }

    /** 请求无障碍服务：打开美团并自动抓取账单 */
    fun postMeituanBillFetchRequest() {
        _events.tryEmit(PREFIX_FETCH_MEITUAN_BILLS)
    }

    /** 请求无障碍服务：打开淘宝闪购并自动抓取账单 */
    fun postTaobaoBillFetchRequest() {
        _events.tryEmit(PREFIX_FETCH_TAOBAO_BILLS)
    }

    /** 请求无障碍服务：停止当前正在进行的抓取（由悬浮窗"停止"按钮调用） */
    fun postStopBillFetchRequest() {
        _events.tryEmit(PREFIX_STOP_BILL_FETCH)
    }

    /** 请求无障碍服务：dump 当前窗口 a11y 树到 logcat（调试用） */
    fun postDumpScreenRequest() {
        _events.tryEmit(PREFIX_DUMP_SCREEN)
    }
}

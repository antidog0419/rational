// ai/AIService.kt
// 决策层入口（云边协同的最小实现）：
//   1. 默认走"云端"：调用本机 AI 服务（ModelAPIClient），带 15s 超时保护；
//   2. 云端不可用时自动降级"端侧"：由本地启发式规则即时生成建议/推荐（<500ms），
//      保证演示与日常高频场景不依赖外部服务（对应路线图"请求分级路由 + 边缘兜底"）。
// 单例：所有调用方共享同一条建议事件流（历史上多实例导致建议丢失，已修复）。

package com.example.finance.ai

import com.example.finance.data.DemoProfile
import com.example.finance.data.UserPreference
import com.example.finance.data.WeeklyReportInput
import com.example.finance.network.ModelAPIClient
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AIAdvice(
    val originalEvent: String,
    val advice: String,
    /** 推理来源：云端 / 端侧 */
    val source: String = CLOUD_SOURCE
) {
    companion object {
        const val CLOUD_SOURCE = "云端"
        const val LOCAL_SOURCE = "端侧"
    }
}

data class RestaurantRecommendation(
    val name: String,
    /** 推理来源：云端 / 端侧演示 */
    val source: String
)

object AIService {
    private const val CLOUD_TIMEOUT_MS = 15_000L

    private val _adviceFlow = MutableSharedFlow<AIAdvice>()
    val adviceFlow = _adviceFlow.asSharedFlow()

    private fun isCloudError(reply: String?): Boolean =
        reply.isNullOrBlank() || reply.startsWith("AI 服务错误")

    /** 带超时保护的云端调用；失败/超时返回 null */
    private suspend fun cloudCall(block: suspend () -> String): String? =
        withTimeoutOrNull(CLOUD_TIMEOUT_MS) { block() }

    // ============ 图片账单分析 ============
    suspend fun processImageWithPrompt(prompt: String, imageBase64: String) {
        if (!ModelAPIClient.isConfigured()) {
            _adviceFlow.emit(
                AIAdvice(
                    "图片分析",
                    "未配置 DeepSeek API Key：请在「DeepSeek 设置」中填写后重试（图片未上传）。",
                    AIAdvice.LOCAL_SOURCE
                )
            )
            return
        }
        val reply = cloudCall {
            ModelAPIClient.chatWithImage("<image>\n$prompt", imageBase64)
        }
        if (!isCloudError(reply)) {
            _adviceFlow.emit(AIAdvice("图片分析", reply!!, AIAdvice.CLOUD_SOURCE))
        } else {
            _adviceFlow.emit(
                AIAdvice(
                    "图片分析",
                    "DeepSeek 当前模型无法识别图片：请改用支持视觉输入的多模态模型，或先用文字描述账单内容（图片未离开本机）。",
                    AIAdvice.LOCAL_SOURCE
                )
            )
        }
    }

    // ============ 单笔消费建议 ============
    suspend fun processConsumptionEvent(consumptionText: String, budgetContext: String) {
        val prompt = """
            你是一个智能财务助手。用户刚发生一笔消费：「$consumptionText」。
            当前预算状态：$budgetContext。
            请用一句话给出理性建议（不超过30字）。
        """.trimIndent()
        val reply = cloudCall { ModelAPIClient.chat(prompt) }
        val isLocal = isCloudError(reply)
        val advice = if (isLocal) localConsumptionAdvice(consumptionText) else reply!!
        _adviceFlow.emit(
            AIAdvice(
                consumptionText, advice,
                if (isLocal) AIAdvice.LOCAL_SOURCE else AIAdvice.CLOUD_SOURCE
            )
        )
    }

    // ============ Top3 外卖推荐（对比本地商家库与历史账单） ============
    suspend fun recommendTop3(currentNeeds: String, candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val prompt = """
            你是外卖推荐助手。用户现在想点外卖，当前需求：「$currentNeeds」。
            以下是该用户本地账单历史里的高频店家（由高到低列出，这是真实的点单偏好）：
            ${candidates.joinToString("\n")}
            请结合「当前需求」和「历史偏好」从上述清单中挑出 3 家现在最想吃的店。
            只输出 3 行店名（第1行=最想吃），不要序号以外任何解释，不要编造清单外的店。
        """.trimIndent()
        val reply = cloudCall { ModelAPIClient.chat(prompt) }
        if (isCloudError(reply)) return candidates.take(3)
        val out = reply!!.lineSequence()
            .map { it.trim().replace(Regex("""^[1-3][.、)）:：]?\s*"""), "").trim() }
            .filter { it.isNotEmpty() && candidates.any { c -> c == it || c.contains(it) || it.contains(c) } }
            .distinct()
            .take(3)
            .toList()
        return out.ifEmpty { candidates.take(3) }
    }

    // ============ 批量账单汇总分析（抓取完一整个平台的账单后调用一次） ============
    suspend fun analyzeBillBatch(
        source: String,
        count: Int,
        totalAmount: Double,
        topMerchants: String
    ) {
        val prompt = """
            你是一个智能财务助手。刚自动抓取了「$source」的 $count 笔支出，合计约 ¥${"%.2f".format(totalAmount)}。
            主要去向：$topMerchants。
            请用两句话点评：1) 总体消费结构；2) 给出一条最值得执行的省钱建议。
        """.trimIndent()
        val reply = cloudCall { ModelAPIClient.chat(prompt) }
        val isLocal = isCloudError(reply)
        val advice = if (isLocal) {
            "端侧统计：$source 共 $count 笔、合计 ¥${"%.2f".format(totalAmount)}，去向集中：$topMerchants。"
        } else {
            reply!!
        }
        _adviceFlow.emit(
            AIAdvice(
                "📊 $source 账单汇总",
                advice,
                if (isLocal) AIAdvice.LOCAL_SOURCE else AIAdvice.CLOUD_SOURCE
            )
        )
    }

    // ============ 每周小结（周报） ============

    /** 金额短格式 */
    private fun fmtR(v: Double): String {
        val s = "%.2f".format(v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /**
     * 生成"近 7 天消费周报"：云端点评为主，云端不可用时输出端侧统计摘要。
     * @param input 由 WeeklyReportBuilder 汇总好的本地统计
     */
    suspend fun generateWeeklyReport(input: WeeklyReportInput) {
        val delta = if (input.prevSpent > 0) (input.spent - input.prevSpent) / input.prevSpent * 100 else null
        val trend = when {
            delta == null -> "上个 7 天无支出记录，暂无环比"
            delta >= 5 -> "比上个 7 天上涨 ${"%.0f".format(delta)}%（需留意）"
            delta <= -5 -> "比上个 7 天下降 ${"%.0f".format(-delta)}%（控制得不错）"
            else -> "与上个 7 天基本持平"
        }
        val dailyAvg = if (input.count > 0) input.spent / 7 else 0.0
        val prompt = """
            你是智能财务助手。请为用户生成一份"近7天消费周报"。
            统计区间：${input.fromLabel} ~ ${input.toLabel}
            - 总支出 ¥${fmtR(input.spent)}，共 ${input.count} 笔，日均 ¥${fmtR(dailyAvg)}
            - 分类分布：${input.catLines.joinToString("；").ifEmpty { "（无支出）" }}
            - 渠道分布：${input.sourceLines.joinToString("；").ifEmpty { "（无）" }}
            - 高频去向：${input.topMerchants.joinToString("；").ifEmpty { "（无）" }}；最大单笔：${input.biggestLine}
            - 环比：上个 7 天 ¥${fmtR(input.prevSpent)}（${input.prevCount} 笔）→ 本次。$trend
            当前预算：${input.budgetContext}
            输出要求：≤130字，直接正文、不要标题与编号。内容三部分：
            1) 一句话总体评价；2) 最值得注意的一点（变化/异常/预算超支风险）；3) 下周 1 条具体可执行建议。
        """.trimIndent()
        val reply = cloudCall { ModelAPIClient.chat(prompt) }
        val text = if (isCloudError(reply)) localWeeklySummary(input, dailyAvg, trend) else reply!!
        _adviceFlow.emit(
            AIAdvice(
                originalEvent = "📅 每周小结 · ${input.fromLabel}~${input.toLabel}",
                advice = text,
                source = if (isCloudError(reply)) AIAdvice.LOCAL_SOURCE else AIAdvice.CLOUD_SOURCE
            )
        )
    }

    /** 端侧兜底：无 AI 时的统计式周报 */
    private fun localWeeklySummary(input: WeeklyReportInput, dailyAvg: Double, trend: String): String {
        val sb = StringBuilder()
        sb.append("端侧统计：近7天支出 ¥${fmtR(input.spent)}（${input.count} 笔，日均 ¥${fmtR(dailyAvg)}）；")
        sb.append("$trend；")
        if (input.catLines.isNotEmpty()) sb.append("分类：${input.catLines.joinToString("；")}；")
        if (input.topMerchants.isNotEmpty()) sb.append("高频：${input.topMerchants.joinToString("；")}；")
        sb.append("最大单笔：${input.biggestLine}。")
        if (input.budgetContext.contains("超支") || input.budgetContext.contains("已超")) {
            sb.append("本月已接近/超出预算，建议下半月控制大额消费。")
        } else {
            sb.append("建议保持现状，重点盯住占比最高的分类。")
        }
        return sb.toString()
    }

    // ============ 下单前 AI 判断（预支付守卫） ============

    /**
     * 判断"现在准备下的这一单"是否合适。
     * @param merchant 商家/商品
     * @param amount 金额
     * @param category 分类（餐饮/购物…）
     * @param overallRemaining 当月总预算剩余（可为负=超支）
     * @param catRemaining 该分类预算剩余；null=该分类未设限
     * @return 一句话判断（如"合适，预算充足" / "⚠️ 不建议：餐饮只剩¥8，这单¥25会超支"）
     */
    suspend fun judgePurchase(
        merchant: String,
        amount: Double,
        category: String,
        overallRemaining: Double,
        catRemaining: Double?
    ): String {
        val catHint = catRemaining?.let { "该分类预算剩余 ¥${fmtR(it)}；" } ?: "该分类未单独设限；"
        val overallHint = if (overallRemaining >= 0) "本月总预算剩余 ¥${fmtR(overallRemaining)}"
        else "本月总预算已超支 ¥${fmtR(-overallRemaining)}"
        val prompt = """
            你是个人财务助手。用户正打算下单：「$merchant」 金额 ¥${"%.2f".format(amount)}（分类：$category）。
            预算情况：$catHint$overallHint。
            请判断这笔是否合适，只输出一行（≤28字），格式：合适/谨慎/不建议 + 一句简短理由（结合预算剩余与金额，不要用感叹号刷屏）。
        """.trimIndent()
        val reply = cloudCall { ModelAPIClient.chat(prompt) }
        if (!isCloudError(reply)) return reply!!.trim()

        // 端侧兜底：金额与剩余预算硬比较
        return if (catRemaining != null && amount > catRemaining) {
            "⚠️ 不建议：$category 预算只剩 ¥${fmtR(catRemaining)}，这单 ¥${"%.2f".format(amount)} 会超支"
        } else if (overallRemaining < 0) {
            "谨慎：本月总预算已超支 ¥${fmtR(-overallRemaining)}，建议小额或取消"
        } else if (amount > overallRemaining && overallRemaining > 0) {
            "谨慎：剩余预算仅 ¥${fmtR(overallRemaining)}，低于本单金额"
        } else {
            "合适：预算充足，可以下单"
        }
    }

    // ============ AI 外卖推荐 ============
    suspend fun recommendRestaurant(
        userPreference: UserPreference,
        currentNeeds: String
    ): RestaurantRecommendation {
        val prompt = buildRecommendationPrompt(userPreference, currentNeeds)
        val reply = cloudCall { ModelAPIClient.chat(prompt) }
        if (!isCloudError(reply)) {
            return RestaurantRecommendation(reply!!.trim(), AIAdvice.CLOUD_SOURCE)
        }
        // 端侧演示兜底：从用户偏好餐厅中轮换推荐，便于无服务时演示全链路
        return localRecommendation(userPreference)
    }

    // =================== 端侧启发式引擎 ===================

    /** 端侧规则：按关键词归类支出并给出固定话术（对应"边缘小脑"雏形） */
    private fun localConsumptionAdvice(consumptionText: String): String {
        val amount = Regex("""([0-9]+\.?[0-9]{0,2})""")
            .find(consumptionText)?.groupValues?.get(1)?.toDoubleOrNull()
        val category = when {
            consumptionText.contains("外卖") || consumptionText.contains("餐") ||
                    consumptionText.contains("饭") || consumptionText.contains("咖啡") ||
                    consumptionText.contains("食堂") -> "餐饮"
            consumptionText.contains("购") || consumptionText.contains("超市") ||
                    consumptionText.contains("淘宝") || consumptionText.contains("京东") ||
                    consumptionText.contains("商城") || consumptionText.contains("店") -> "购物"
            else -> "其他"
        }
        return when (category) {
            "餐饮" -> if (amount != null && amount > 25) {
                "这笔餐饮偏高：建议先冷静 10 分钟再决定是否继续加单（端侧规则）。"
            } else {
                "已计入餐饮预算，保持当前节奏即可（端侧规则）。"
            }
            "购物" -> if (amount != null && amount > 100) {
                "大额购物建议隔夜再决定，想想它相当于你几小时的劳动（端侧规则）。"
            } else {
                "购物支出已记录，建议月底统一回看（端侧规则）。"
            }
            else -> "支出已记录，24 小时后回看是否仍觉得值得（端侧规则）。"
        }
    }

    /** 端侧演示推荐：在用户偏好餐厅中按分钟轮换 */
    private fun localRecommendation(userPreference: UserPreference): RestaurantRecommendation {
        val favorites = userPreference.favoriteRestaurants
        val name = if (favorites.isEmpty()) {
            DemoProfile.favoriteRestaurants.firstOrNull() ?: "老乡鸡"
        } else {
            val index = ((System.currentTimeMillis() / 60_000L) % favorites.size).toInt()
            favorites[index]
        }
        return RestaurantRecommendation(name, AIAdvice.LOCAL_SOURCE)
    }

    // =================== 视觉屏幕定位（找"看不见文字"的按钮） ===================

    /** 视觉读取的一笔账单 */
    data class VisionBillEntry(
        val merchant: String,
        val amount: Double,
        val type: String,  // 支出 / 收入
        val time: String = ""  // 该笔真实支付/交易时间，格式 yyyy-MM-dd HH:mm 或 yyyy-MM-dd；屏幕没显示则为空
    )

    /** 视觉读屏结果 */
    data class VisionBillScreen(
        val isBillList: Boolean,
        val entries: List<VisionBillEntry>
    )

    /**
     * 让 DeepSeek 视觉模型看屏幕截图，判断是否为账单明细列表，并读取可见的收支行（含每笔时间）。
     * @return 金额均为正数；type=支出/收入；time 为空表示该屏未显示时间；识别失败或非列表时 entries 为空。
     */
    suspend fun readBillScreen(screenshotBase64: String): VisionBillScreen {
        val prompt = """
            你是账单明细阅读器。下面是一张手机屏幕截图。
            判断：此界面是否为"消费/收支明细列表"（通常含多行"商家+金额/分类/时间"记录，标题含"账单/明细/收支"）。
            - 若【不是】明细列表：输出 {"isBillList": false}
            - 若是：从上到下读取【完整可见】的每一行记录，金额只取数字（忽略"支出/收入/¥"等符号，退款/入账金额也算收入/支出类型按其文字判断）。
              输出：{"isBillList": true, "entries": [{"merchant": "商家或说明", "amount": 12.34, "type": "支出", "time": "2024-07-15 12:30"}]}
            - 商家名不要包含金额与分类/时间；看不清的商家填"未知商家"；跳过纯广告/推荐/还款提示等非消费行。
            - time 字段：填这行账单显示的真实交易/支付时间；若屏幕用上方日期分组标题（如"6月18日"）而每行没单独时间，就把该日期补到组内每行；今天/昨天换算成实际日期；没有时间显示就填空字符串 ""。
            - 忽略"释放刷新/更多/领神券/筛选/全部/收起"等界面提示噪音；商家取订单里的店名/商品主体名，禁止取提示语。
            只输出 JSON（禁止解释与 markdown 代码块）。
        """.trimIndent()
        val reply = ModelAPIClient.chatWithVision(prompt, screenshotBase64)
        if (reply.startsWith("AI 服务错误")) {
            Log.e("AIService", "视觉读屏调用失败: $reply")
            return VisionBillScreen(false, emptyList())
        }
        return try {
            val jsonStr = reply.substringAfter('{').substringBeforeLast('}').let { "{$it}" }
            val obj = Json.parseToJsonElement(jsonStr).jsonObject
            val isBill = obj["isBillList"]?.jsonPrimitive?.contentOrNull
                ?: obj["isBillList"]?.toString()
                ?: "false"
            if (!isBill.equals("true", ignoreCase = true)) {
                Log.d("AIService", "视觉读屏：非账单明细列表")
                return VisionBillScreen(false, emptyList())
            }
            val list = obj["entries"]?.jsonArray ?: return VisionBillScreen(true, emptyList())
            val entries = list.mapNotNull { el ->
                try {
                    val e = el.jsonObject
                    val amount = e["amount"]?.jsonPrimitive?.contentOrNull
                        ?.replace(",", "")?.toDoubleOrNull() ?: return@mapNotNull null
                    val merchant = (e["merchant"]?.jsonPrimitive?.contentOrNull ?: "")
                        .trim().take(24)
                    if (merchant.isEmpty()) return@mapNotNull null
                    val time = (e["time"]?.jsonPrimitive?.contentOrNull ?: "")
                        .trim().take(32)
                    VisionBillEntry(
                        merchant = merchant,
                        amount = amount.coerceAtLeast(0.01),
                        type = (e["type"]?.jsonPrimitive?.contentOrNull ?: "支出"),
                        time = time
                    )
                } catch (ex: Exception) {
                    null
                }
            }
            Log.d("AIService", "视觉读屏：明细列表，读到 ${entries.size} 行")
            VisionBillScreen(true, entries)
        } catch (e: Exception) {
            Log.e("AIService", "视觉读屏解析失败: ${reply.take(200)}", e)
            VisionBillScreen(false, emptyList())
        }
    }

    /**
     * 让 DeepSeek 视觉模型看屏幕截图，定位目标元素。
     * @return 归一化点击中心 (x, y)，取值 0..1（图片左上角为原点）；找不到返回 null。
     */
    suspend fun locateUiElement(screenshotBase64: String, target: String): Pair<Float, Float>? {
        val prompt = """
            你是屏幕理解与 UI 定位助手。下面是一张安卓手机截图。
            任务：找到界面元素【$target】。
            - 若是"我的"：通常在底部导航栏最右侧的图标+文字；
            - 若是"账单"：找文字/图标为"账单"的入口或页面标题；
            - 返回该元素中心点的归一化坐标：图片左上角=(0,0)，右下角=(1,1)。
            只输出 JSON（禁止解释、禁止 markdown 代码块），格式：
            {"found": true, "x": 0.9, "y": 0.97}
            找不到就输出：{"found": false}
        """.trimIndent()
        val reply = ModelAPIClient.chatWithVision(prompt, screenshotBase64)
        if (reply.startsWith("AI 服务错误")) {
            Log.e("AIService", "视觉定位调用失败: $reply")
            return null
        }
        return try {
            val jsonStr = reply.substringAfter('{').substringBeforeLast('}').let { "{$it}" }
            val obj = Json.parseToJsonElement(jsonStr).jsonObject
            val found = obj["found"]?.jsonPrimitive?.contentOrNull
                ?: obj["found"]?.toString()
                ?: "false"
            if (!found.equals("true", ignoreCase = true)) {
                Log.d("AIService", "视觉定位：未找到 $target")
                return null
            }
            val x = obj["x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null
            val y = obj["y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null
            if (x < 0f || x > 1f || y < 0f || y > 1f) return null
            Log.d("AIService", "视觉定位 $target @ ($x,$y)")
            x to y
        } catch (e: Exception) {
            Log.e("AIService", "视觉定位解析失败: ${reply.take(200)}", e)
            null
        }
    }

    // =================== 提示词构建 ===================

    private fun buildRecommendationPrompt(userPreference: UserPreference, currentNeeds: String): String {
        val favoriteCuisines = userPreference.favoriteCuisines.joinToString(", ")
        val dietaryRestrictions = userPreference.dietaryRestrictions.joinToString(", ")
        val favoriteRestaurants = userPreference.favoriteRestaurants.joinToString(", ")

        return """
            你是一个智能外卖推荐助手。根据以下用户信息推荐最适合的外卖：

            用户偏好：
            - 喜欢的菜系：$favoriteCuisines
            - 价格区间：${userPreference.priceRange}
            - 饮食限制：$dietaryRestrictions
            - 喜欢的餐厅：$favoriteRestaurants

            当前需求：$currentNeeds

            请从美团外卖的餐厅列表中推荐一个最符合用户需求的餐厅，并只返回餐厅名称，不要包含其他内容。
        """.trimIndent()
    }
}

package com.example.finance.ui.mock

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.finance.ui.theme.libanColors

/**
 * Mock 数据层：6 个新页面的全部展示数据。
 * UI 与数据分离 —— 后续接入真实数据时，只需把 Screen 里的
 * Mock 引用替换为 ViewModel 暴露的字段，布局不用动。
 */

// ---------- 1. 首页仪表盘 ----------

data class InterventionRecord(
    val merchant: String,
    val amount: String,
    val time: String,
    val status: String, // 已冷静 / 已拒绝
)

data class DashboardMock(
    val greeting: String = "Hi, 同学",
    val dateLine: String = "10月15日 周二 · 今天也要理性消费",
    val rationalityScore: Int = 78,
    val scoreDelta: String = "✓ 较上周 +3 分",
    val scoreSummary: String = "你的消费实力超过了 71% 的用户，继续保持「24 小时冷静」习惯。",
    val spentAmount: String = "¥428.00",
    val spentDelta: String = "↓ 15% vs 上月",
    val budgetRemaining: String = "¥647",
    val budgetTotal: String = "/ ¥2,600",
    val budgetUsedPercent: Float = 0.77f,
    val budgetUsedLabel: String = "已使用 77%",
    val interventions: List<InterventionRecord> = listOf(
        InterventionRecord("电商平台", "¥329.00", "昨天 14:32", "已冷静 ✓"),
        InterventionRecord("游戏充值", "¥128.00", "昨天 21:46", "已拒绝"),
    ),
)

// ---------- 2. 支付干预弹窗 ----------

data class InterventionRow(val label: String, val value: String, val valueColor: Color? = null)

data class InterventionDetail(
    val remainingPercent: Int = 23,
    val usedAmount: String = "¥2,133",
    val budgetAmount: String = "¥2,600",
    val rows: List<InterventionRow> = listOf(
        InterventionRow("本次支付", "¥329.00 · 电商平台"),
        InterventionRow("支付后本月总计", "¥1,204.00"),
        InterventionRow("近 3 月均值", "¥977.00"),
        InterventionRow("支付后超支概率", "89% · 触发预警"),
    ),
    val aiAnalysis: String = "你在过去 3 个月的月消费临界点持续上升，本次为本月第 3 次临期大额支出。按当前 24 小时延迟决策的习惯，有 82% 概率取消本次消费。",
    val saveHint: String = "预计节省 ¥329",
    val footerNote: String = "延迟确认后，24 小时内随时可以取消这笔支付",
)

// ---------- 3. 为什么给出这条提醒 ----------

data class ExplainFactRow(val label: String, val value: String)

data class ExplainPrinciple(
    val emoji: String,
    val title: String,
    val body: String,
)

data class ExplainabilityMock(
    val facts: List<ExplainFactRow> = listOf(
        ExplainFactRow("触发时间", "今天 19:41 · 游戏充值 ¥128"),
        ExplainFactRow("本月该类目支出", "¥438.00（含本次）"),
        ExplainFactRow("历史国内均值", "¥232.00 · 超出 89%"),
        ExplainFactRow("触发规则", "预算使用率 77% + 价格点 75%"),
    ),
    val principles: List<ExplainPrinciple> = listOf(
        ExplainPrinciple(
            "🧠",
            "心理账户偏误",
            "你对「游戏充值」收入敏感，低估了它对总预算的挤占效应 —— 该类目已连续 3 个月超支。",
        ),
        ExplainPrinciple(
            "⏰",
            "即时满足偏误",
            "晚上 21-24 点是你的高冲动消费时段，历史上该时段的支付决策后悔率达 64%。",
        ),
        ExplainPrinciple(
            "⚖️",
            "锚定效应",
            "首次 6 元设定档位让你对后续大额充值的敏感度下降了 41%。",
        ),
    ),
    val counterfactual: String =
        "如果把这笔 ¥128 换成 14 个人的「旅行基金」目标，就能达到该月目标的 38.7%，且 3 个月后它将带来 ¥847 的被动收益 —— 相当于当前增值率的三倍有余。",
)

// ---------- 4. 我的数据与权限 ----------

data class SceneTrack(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
)

data class PrivacyMock(
    val sensitivity: Int = 6,
    val sensitivityLabel: String = "平衡模式 · 已保护您 32 天",
    val scenes: List<SceneTrack> = listOf(
        SceneTrack("🛒", "购物消费追踪", "电商下单 · 比价站外价", true),
        SceneTrack("🎮", "游戏充值追踪", "充值与订阅预警", true),
        SceneTrack("🍜", "外卖餐饮追踪", "高频小额累计提醒", true),
        SceneTrack("🎬", "文娱消费追踪", "演出与会员监测", false),
    ),
    val weeklyBars: List<Float> = listOf(3f, 5f, 2f, 6f, 4f, 8f, 1f),
    val weeklyHighlight: Int = 5,
)

// ---------- 5. 会员订阅 ----------

data class MembershipPlan(
    val id: String,
    val name: String,
    val price: String,
    val period: String?,
    val features: List<String>,
    val badge: String? = null,
)

data class MembershipMock(
    val title: String = "升级「认知版」",
    val subtitle: String = "解锁完整的 AI 理性分析能力\n看见每一笔冲动背后的认知偏差",
    val plans: List<MembershipPlan> = listOf(
        MembershipPlan(
            "basic", "基础版", "¥0", null,
            listOf("基础预算提醒", "自定义预算阈值", "每周理性报告", "网页管理"),
        ),
        MembershipPlan(
            "pro", "认知版", "¥15", "/月",
            listOf("全部基础功能", "认知升级建议", "冷静期干预", "清单周报"),
            badge = "首月 ¥9.9",
        ),
        MembershipPlan(
            "family", "家庭版", "¥25", "/月",
            listOf("认知版全部功能", "5 人家庭空间", "家长守护模式", "多账户报告"),
        ),
    ),
    val savingBanner: String = "过去 30 天，认真回应超过 10 次 AI 干预\n平均节省 ¥1,842 · 订阅费用回 123 倍",
    val cta: String = "立即升级 · 首月 ¥9.9",
    val footnote: String = "随时取消 · 支持解绑 · 支付宝/微信 · 学生认证享 8 折",
)

// ---------- 6. 理性成长社区 ----------

data class CommunityPost(
    val author: String,
    val avatarEmoji: String,
    val timeAgo: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val likes: Int,
    val comments: Int,
    val shares: String,
)

data class CommunityMock(
    val points: String = "2,680 理性币",
    val challengeTitle: String = "🔥 21 天理性消费挑战",
    val challengeDay: String = "DAY 14",
    val challengeProgress: String = "已坚持 14/21 天 · 连续打卡 7 天",
    val challengeReward: String = "今日打卡 +20",
    val tabs: List<String> = listOf("精选", "关注", "最新"),
    val postsByTab: Map<String, List<CommunityPost>> = mapOf(
        "精选" to listOf(
            CommunityPost(
                "图名同学", "🧑‍💻", "2 小时前 · 理性记录分享",
                "坚持第 30 天，不冲动消费了",
                "坚持 30 天，不冲动消费了。省下了 2,000+，最大的变化是：以前看到「多巴胺」就想要，现在会先问自己一句——我是真需要，还是只想要。",
                listOf("不冲动消费", "35 天清醒"),
                128, 36, "2.1k",
            ),
            CommunityPost(
                "理性青年_07", "🧑‍🔬", "5 小时前",
                "用 AI 干预一个月的变化",
                "用 AI 干预一个月，游戏充值减少 68%，理性指数从 61 升到 82，冷静期限结束时，有冲动烟消云散了不少。",
                listOf("游戏充值", "冷静 24 小时"),
                89, 24, "986",
            ),
            CommunityPost(
                "街键小助手", "🤖", "昨天 21:15",
                "本周理性榜更新啦",
                "上周共有 3,214 位同学完成冷静打卡，共避免冲动消费 ¥86,420。看看你排第几？",
                listOf("理性周榜"),
                56, 12, "432",
            ),
        ),
        "关注" to listOf(
            CommunityPost(
                "存钱小能手中", "🐹", "1 小时前",
                "储蓄目标提前达成 40%",
                "跟着社区的 52 周存钱法走了 12 周，目标进度从 20% 涨到 40%，记录一下里程碑。",
                listOf("储蓄目标", "52 周挑战"),
                73, 18, "540",
            ),
        ),
        "最新" to listOf(
            CommunityPost(
                "早睡冠军", "🌙", "刚刚",
                "深夜下单前先来打个卡",
                "23 点了，购物车里的东西明天再说。今天又是战胜冲动消费的一天。",
                listOf("深夜守护"),
                12, 2, "45",
            ),
        ),
    ),
)

/** 取色便捷函数（供页面里少量语义色使用，避免硬编码） */
@Composable
fun mockDangerColor(): Color = libanColors().danger

package com.example.finance.parsing

/**
 * 商家文本归一化 / 噪声过滤（纯函数，可单测）。
 *
 * 原实现位于 FinanceAccessibilityService.kt 内部，2026-09-07 抽取至此；
 * 规则与真机校准版一致，未做任何行为改动。
 */
object MerchantText {

    /** 淘宝订单卡的状态词（出现即说明该节点不是店名/金额） */
    val TB_STATUS_WORDS: List<String> = listOf(
        "已送达", "已完成", "已收货", "交易成功", "已评价", "待评价", "待付款",
        "待发货", "待收货", "待使用",
    )

    /** 淘宝店名噪声过滤（按金额/条目说明等）；太长或含平台词一律视为噪声 */
    fun isTbBrandNoise(desc: String): Boolean {
        if (desc.length < 2 || desc.length > 26) return true
        return listOf(
            "订单", "实付", "¥", "×", "下单", "购买", "拼单", "默认", "理赔", "赔付", "慢必赔", "删除",
            "评价", "再买", "退款", "售后", "投诉", "签到", "图片", "商品", "加载", "更多", "搜索",
            "筛选", "管理", "消息", "返回", "释放", "已送达", "已完成", "订单号", "门店", "包装"
        ).any { desc.contains(it) } || desc.matches(Regex("""\d[\d.¥]*"""))
    }

    /**
     * 归一化店名：去括号分店/后缀分隔符，供跨平台（淘宝 ↔ 支付宝）金额+店名匹配用。
     * 注意：全部小写（英文品牌），与原实现一致。
     */
    fun normMerchant(raw: String): String {
        var r = raw.trim().lowercase()
        r = r.replace(Regex("""[(（][^)）]*[)）]"""), "")                 // (海洋大学店) 等括号内文本
        r = r.replace(Regex("""外卖订单|外送订单|订单|海洋大学店|海大店|湖光岩店|海大湖畔食堂店|商中美食城|第\s*\d+\s*档口|门店|美食城"""), "")
        r = r.replace(Regex("""[·・，。、\s'"]"""), "")
        return r
    }

    /** 账单行之间的非商家噪声（日期/分类/状态/导航/额度等）；>24 字直接视为噪声 */
    fun isNoiseText(t: String): Boolean {
        if (t.length > 24) return true
        return t.contains("账单") || t.contains("今天") || t.contains("昨天") ||
            t.contains("星期") || t.contains("月") && t.any { it.isDigit() } ||
            t.contains("全部") || t.contains("收入") || t.contains("支出") ||
            t.contains("退款") || t.contains("更多") || t.contains("余额") ||
            t.contains("额度") || t.contains("应还") || t.contains("立即") ||
            t.contains("还款") || t.contains("我的省钱") || t.contains("总计") ||
            t.contains("贴纸") || t.contains("本月已省") || t.contains("账单月报") ||
            t.contains("收支分析") || t.contains("生活号") || t.contains("花呗金") || t.contains("分期") ||
            t.matches(Regex("""\d{1,2}:\d{2}""")) || t.matches(Regex("""\d{4}-\d{2}-\d{2}""")) ||
            t.contains("转账") || t.contains("红包") || t.contains("搜索")
    }

    /** 在金额文本之前寻找最近的"商家/说明"文本（最多跳过 4 个空/噪声节点） */
    fun findMerchantNear(texts: List<String>, amountIndex: Int): String? {
        var j = amountIndex - 1
        var skip = 0
        while (j >= 0 && skip < 4) {
            val t = texts[j].trim()
            if (t.isEmpty() || BillAmountText.AMOUNT_LINE_REGEX.matches(t) || isNoiseText(t)) {
                skip++
                j--
                continue
            }
            return t.take(24)
        }
        return null
    }
}

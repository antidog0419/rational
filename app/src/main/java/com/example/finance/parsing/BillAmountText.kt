package com.example.finance.parsing

/**
 * 金额文本（纯函数，可单测）。
 *
 * 原实现位于 FinanceAccessibilityService.kt 内部（美团 ¥ 拆分节点合并等），
 * 2026-09-07 抽取至此；规则与真机校准版一致。
 */
object BillAmountText {

    /** 独立金额行样式：¥12.00 / -12.00 / +¥88.50 / - ¥ 12.00（整行必须就是金额） */
    val AMOUNT_LINE_REGEX = Regex("""^[+-]?\s*[¥￥]?\s*([0-9]{1,5}\.[0-9]{2})$""")

    /** 纯价格 token（text/desc 里单独出现的 ¥9.9 样式） */
    val PRICE_TOKEN_REGEX = Regex("""^[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)$""")

    /** 支付宝"模式1"整行样式：商家名，-10.90元（合并文本节点，分类/时间跟在后面） */
    val ROW_INLINE_YUAN_REGEX = Regex("""^(.{1,40}?)，\s*([-+])?\s*[¥￥]?\s*([0-9]+(?:\.[0-9]{1,2})?)元""")

    private val INT_TOKEN = Regex("""\d{1,4}""")
    private val FRAC_TOKEN = Regex("""\.\d{1,2}""")

    /**
     * 美团订单列表的金额拆分节点合并：金额被拆成 ["¥", "9", ".9"] 三个节点。
     * 调用方需保证 tokens 是已 trim 的文本序列、tokens[i] 为 ¥/￥。
     *
     * @return (金额, 消费结束下标 nextIndex)；组不出合法金额（≤0 或结构不符）时返回 (null, i)。
     *         组成功时 nextIndex 指向整数/小数节点之后，调用方应从 nextIndex 继续扫描。
     */
    fun mergeYuanTokens(tokens: List<String>, i: Int): Pair<Double?, Int> {
        var amountStr = ""
        var j = i + 1
        val intTok = tokens.getOrNull(j) ?: ""
        if (intTok.matches(INT_TOKEN)) {
            amountStr = intTok
            j++
        }
        val fracTok = tokens.getOrNull(j) ?: ""
        if (fracTok.matches(FRAC_TOKEN)) {
            amountStr = if (amountStr.isEmpty()) "0$fracTok" else amountStr + fracTok
            j++
        }
        val amount = amountStr.toDoubleOrNull()
        return if (amount != null && amount > 0.0) amount to j else null to i
    }
}

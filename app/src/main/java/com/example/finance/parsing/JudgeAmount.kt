// parsing/JudgeAmount.kt
// 下单前判断的"应付金额挑选"纯规则（2026-09-07 真机校准，从结算页 a11y 树/OCR 行通用）：
//  ① 费用行(共减/立减/优惠券/红包/运费…)一律排除；
//  ② 其拆分的独立纯金额邻行(同行重叠或紧贴上方≤40px)也排除(修"共减¥1.8→误取1.8")；
//  ③ 含应付类词(应付/实付/合计/支付/提交…)的行优先；
//  ④ 否则取最下方可用金额。
// 输入行可来自无障碍控件树(有拆分节点)或 OCR(整行)，规则一致、可单测。

package com.example.finance.parsing

object JudgeAmount {

    data class Row(val text: String, val top: Int, val bottom: Int, val cy: Int)

    /** 减免/费用/不可作应付的行特征词（"券前"不是费用行，勿加"券"） */
    val feeWords = listOf(
        "共减", "立减", "已减", "满减", "优惠券", "红包", "运费", "配送", "打包", "起送", "优惠", "代金",
    )

    /** 应付/合计类标签：命中优先 */
    val payWords = listOf(
        "应付", "实付", "合计", "共需", "需付", "支付", "提交", "确认", "结算", "总价", "小计",
    )

    private val money = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    private val purePrice = Regex("""^[¥￥]\s*[0-9]+(?:\.[0-9]{1,2})?$""")
    private val hasDigit = Regex("""[0-9]""")

    fun pick(rows: List<Row>): Double? {
        if (rows.isEmpty()) return null
        val feeLines = rows.filter { line -> feeWords.any { w -> line.text.contains(w) } }
        // "纯文字费用标签"：含费用词但不含任何数字/¥（如"共减"），它的金额是拆分的相邻小数字
        val feeTags = feeLines.filter { line ->
            !line.text.contains("¥") && !line.text.contains("￥") && !hasDigit.containsMatchIn(line.text)
        }
        val candidates = rows.mapNotNull { line ->
            val m = money.find(line.text) ?: return@mapNotNull null
            val amount = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val hasFee = feeWords.any { w -> line.text.contains(w) }
            val hasPayWord = payWords.any { w -> line.text.contains(w) }
            val isPurePrice = purePrice.matches(line.text.trim())
            // 拆分行排除：仅"纯金额节点"且紧贴费用标签下方(≤10px，同一卡内拆分)才排除；
            // 正常行距(≥20px)或左右并排的正价(如"券前"区)不受影响
            val nearFeeTag = !hasPayWord && isPurePrice && feeTags.any { f ->
                f.bottom <= line.top && line.top - f.bottom <= 10
            }
            if (hasFee || nearFeeTag) null else amount to line
        }
        val sorted = candidates.sortedByDescending { it.second.cy }
        return (sorted.firstOrNull { (_, l) -> payWords.any { w -> l.text.contains(w) } }
            ?: sorted.firstOrNull())?.first
    }
}

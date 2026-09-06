// data/BillSources.kt
// 账单"来源"的规范集与归一化。
// 历史原因：同一通道在不同时期写入过不同名（"支付宝"/"支付宝账单"、"美团"/"美团账单"、
// "本地演示"…），导致月度统计与筛选把同一来源拆成多行。这里收敛为统一写法：
//   支付宝 / 美团 / 淘宝闪购 / 手动 / 其他（未知来源保留原名，仅归入"其他"色系）。

package com.example.finance.data

object BillSources {

    const val ALIPAY = "支付宝"
    const val MEITUAN = "美团"
    const val TAOBAO = "淘宝闪购"
    const val MANUAL = "手动"
    const val OTHER = "其他"

    /** 手动补记可选的来源 */
    val PRESETS: List<String> = listOf(ALIPAY, MEITUAN, TAOBAO, MANUAL, OTHER)

    /** 展示/统计用的"全部来源"虚拟项 */
    const val ALL = "全部"

    /** 把任意旧写法归一化为规范来源；无法归类的空白归"其他"，其余保留原名（视为自定义来源） */
    fun canonical(raw: String?): String {
        val t = (raw ?: "").trim()
        return when {
            t.isEmpty() -> OTHER
            t.contains("支付宝") -> ALIPAY
            t.contains("美团") || t.contains("点评") -> MEITUAN
            t.contains("淘宝") || t.contains("闪购") || t.contains("天猫") -> TAOBAO
            t.contains("演示") || t == "本地" -> MANUAL
            t in PRESETS -> t
            else -> t
        }
    }

    /** 手动补记下拉的选项里，是否属于规范来源（否则归"其他"展示） */
    fun isPreset(source: String): Boolean = source in PRESETS
}

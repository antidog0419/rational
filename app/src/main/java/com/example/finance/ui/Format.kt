// ui/Format.kt
// liban-main ui/Format.kt 移植：金额 / 时间显示小工具（供移植页面共用）。

package com.example.finance.ui

import java.text.DateFormat
import java.util.Date

/** 金额格式化（分 → ¥x,xxx.xx） */
fun yuan(cents: Long): String = "¥%,.2f".format(cents / 100.0)

/** 时间戳格式化 */
fun date(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

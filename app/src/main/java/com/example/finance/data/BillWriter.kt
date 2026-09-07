// data/BillWriter.kt
// 通用"写一笔本地账单"工具：识屏智能体确认购买/主动记一笔时使用（与手动补记同库同权）。
// finance.db 是全 App 账单的唯一权威来源；写入后日历/统计/首页自动刷新。

package com.example.finance.data

import android.content.Context

object BillWriter {

    /**
     * 安静地记入一笔账单（不触发悬浮窗/AI 点评，同手动补记口径）。
     * @return 0=成功入库；-1=与已有账单重复(同来源商家金额+同一分钟)；null=参数非法
     */
    suspend fun add(
        context: Context,
        merchant: String,
        amountYuan: Double,
        source: String = BillSources.SCREEN,
        note: String = "",
    ): Long? {
        val name = merchant.trim()
        if (name.isEmpty() || amountYuan <= 0.0) return null
        val now = System.currentTimeMillis()
        val entity = BillEntity(
            source = BillSources.canonical(source),
            merchant = name.take(40),
            amount = amountYuan,
            occurredAtMs = now,
            dayBucket = BillEntity.dayBucket(now),
            note = note.trim().take(80),
            timeBucket = BillEntity.timeBucketOf(now),
        )
        return FinanceDb.get(context.applicationContext).billDao().insert(entity)
    }
}

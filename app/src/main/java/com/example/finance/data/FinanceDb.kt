// data/FinanceDb.kt
// 本地账单库（Room）：所有自动/抓取的消费都持久化到这里。
// 去重：唯一索引 (来源, 商家, 金额, 当天) —— 跨通道/跨抓取/跨重启都不会重复入库。
// 用途：月度统计、CSV 导出、启动时回填最近记录。

package com.example.finance.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Entity(
    tableName = "bills",
    // 去重键：来源+商家+金额+分钟级时间桶（yyyy-MM-dd HH:mm）。
    // 同一天"不同分钟"买两件同价商品不再被误合并；滚动重读同一笔仍是同一分钟 → 照常去重。
    indices = [Index(value = ["source", "merchant", "amount", "timeBucket"], unique = true)]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val source: String,          // 已归一化的来源：支付宝 / 美团 / 淘宝闪购 / 手动 / 其他…
    val merchant: String,        // 商家或商品说明
    val amount: Double,          // 支出金额（元，>0）
    val occurredAtMs: Long,      // 发生时刻（毫秒）
    val dayBucket: String,       // yyyy-MM-dd，列表分组/筛选用
    val note: String = "",       // 备注（手动补记/编辑可填）
    val timeBucket: String = ""  // yyyy-MM-dd HH:mm，真实特征去重键（分钟级）
) {
    fun toRecord() = ConsumptionRecord(
        source = source, merchant = merchant, amount = amount, occurredAtMs = occurredAtMs
    )

    /** 深拷贝并修正派生字段（改时间/金额后 dayBucket/timeBucket 需重算） */
    fun withDerived(
        source: String = this.source,
        merchant: String = this.merchant,
        amount: Double = this.amount,
        occurredAtMs: Long = this.occurredAtMs,
        note: String = this.note
    ): BillEntity =
        BillEntity(
            id = id,
            source = BillSources.canonical(source),
            merchant = merchant.trim().take(40),
            amount = amount,
            occurredAtMs = occurredAtMs,
            dayBucket = dayBucket(occurredAtMs),
            note = note.trim().take(80),
            timeBucket = timeBucketOf(occurredAtMs)
        )

    companion object {
        fun fromRecord(r: ConsumptionRecord): BillEntity =
            BillEntity(
                source = BillSources.canonical(r.source),
                merchant = r.merchant.trim().take(40),
                amount = r.amount,
                occurredAtMs = r.occurredAtMs,
                dayBucket = dayBucket(r.occurredAtMs),
                timeBucket = timeBucketOf(r.occurredAtMs)
            )

        fun dayBucket(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

        /** 分钟级时间桶：yyyy-MM-dd HH:mm（去重用） */
        fun timeBucketOf(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

/** 来源分组统计（total 为该组合计，cnt 为该组笔数） */
data class SourceStat(val source: String, val total: Double, val cnt: Int)

/** 预算/分类聚合用的一笔（区间内明细的轻量投影） */
data class MerchantAmount(val source: String, val merchant: String, val amount: Double)

@Dao
interface BillDao {
    /**
     * 落一笔账。唯一索引 (来源, 商家, 金额, 当天) 做真实特征去重：
     * 冲突时忽略并返回 -1（调用方据此提示"已存在相同记录"）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BillEntity): Long

    /** 编辑保存（按主键整行更新；改时间后需自行带上重算的 dayBucket） */
    @Update
    suspend fun update(entity: BillEntity)

    /** 删除单笔 */
    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 全量明细（账单页/导出用），新→旧 */
    @Query("SELECT * FROM bills ORDER BY occurredAtMs DESC, id DESC")
    fun observeAll(): Flow<List<BillEntity>>

    /** 最近 5 笔（首页"最近一笔"等预览用） */
    @Query("SELECT * FROM bills ORDER BY occurredAtMs DESC, id DESC LIMIT 5")
    fun latestFew(): Flow<List<BillEntity>>

    /** 区间总支出（首页"今日支出"用）；无记录时为 0.0 */
    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM bills WHERE occurredAtMs >= :from AND occurredAtMs < :to")
    fun sumBetween(from: Long, to: Long): Flow<Double>

    /** 区间笔数（首页"今日 N 笔"用） */
    @Query("SELECT COUNT(*) FROM bills WHERE occurredAtMs >= :from AND occurredAtMs < :to")
    fun countBetween(from: Long, to: Long): Flow<Int>

    /** 区间总支出（一次性查询，预算/服务线程用） */
    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM bills WHERE occurredAtMs >= :from AND occurredAtMs < :to")
    suspend fun sumBetweenOnce(from: Long, to: Long): Double

    /** 区间内每笔的来源+商家+金额（预算分类统计用，一次性查询） */
    @Query(
        "SELECT source AS source, merchant AS merchant, amount AS amount FROM bills " +
                "WHERE occurredAtMs >= :from AND occurredAtMs < :to"
    )
    suspend fun merchantAmountsBetween(from: Long, to: Long): List<MerchantAmount>

    @Query("SELECT * FROM bills ORDER BY occurredAtMs DESC LIMIT 300")
    fun recent(): Flow<List<BillEntity>>

    /** 区间统计（按来源分组），用于月度统计卡 */
    @Query(
        "SELECT source AS source, SUM(amount) AS total, COUNT(*) AS cnt FROM bills " +
                "WHERE occurredAtMs >= :from AND occurredAtMs < :to " +
                "GROUP BY source ORDER BY total DESC"
    )
    fun statsBetween(from: Long, to: Long): Flow<List<SourceStat>>

    @Query("SELECT * FROM bills ORDER BY occurredAtMs DESC")
    suspend fun all(): List<BillEntity>

    @Query("DELETE FROM bills WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM bills")
    suspend fun clearAll()

    // ---- 一次性来源名归一化（幂等：只匹配旧版本写入的旧名）----
    @Query("UPDATE bills SET source = '支付宝' WHERE source = '支付宝账单'")
    suspend fun migrateSourceAlipayBill()

    @Query("UPDATE bills SET source = '美团' WHERE source = '美团账单'")
    suspend fun migrateSourceMeituanBill()

    @Query("UPDATE bills SET source = '手动' WHERE source = '本地演示'")
    suspend fun migrateSourceDemo()
}

@Database(entities = [BillEntity::class], version = 3, exportSchema = false)
abstract class FinanceDb : RoomDatabase() {
    abstract fun billDao(): BillDao

    companion object {
        @Volatile
        private var instance: FinanceDb? = null

        /** v1 → v2：新增 note（备注）列 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2 → v3：去重从"天"升级到"分钟"。
         * 1) 新增 timeBucket 列并用现有发生时间回填（yyyy-MM-dd HH:mm）；
         * 2) 把唯一索引从 (source,merchant,amount,dayBucket) 换成 (source,merchant,amount,timeBucket)。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN timeBucket TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE bills SET timeBucket = " +
                            "strftime('%Y-%m-%d %H:%M', occurredAtMs / 1000, 'unixepoch', 'localtime') " +
                            "WHERE timeBucket = ''"
                )
                db.execSQL("DROP INDEX IF EXISTS index_bills_source_merchant_amount_dayBucket")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_bills_source_merchant_amount_timeBucket " +
                            "ON bills (source, merchant, amount, timeBucket)"
                )
            }
        }

        fun get(context: Context): FinanceDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDb::class.java,
                    "finance.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        /** 由 FinanceApp 初始化；返回单例（UI/仓库共用） */
        fun init(context: Context): FinanceDb = get(context).also {
            instance = it
        }
    }
}

/** 当月起止时间戳（本地时区） */
fun monthRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1) }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val from = cal.timeInMillis
    cal.add(Calendar.MONTH, 1)
    return from to cal.timeInMillis
}

/** 今天 00:00 至明天 00:00（本地时区），用于"今日支出/今日笔数" */
fun todayRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val from = cal.timeInMillis
    cal.add(Calendar.DAY_OF_YEAR, 1)
    return from to cal.timeInMillis
}

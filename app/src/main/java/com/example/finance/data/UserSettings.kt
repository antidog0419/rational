// data/UserSettings.kt
// 用户设置（本地 SharedPreferences 持久化）。
// 1. 执行层人类监督开关：AI 推荐后是否自动打开美团并搜索（默认关闭）。
// 2. DeepSeek 运行时配置（API Key / 模型 / Base URL）。
// 3. "抓取支付宝账单"待执行标记：解决"点了没反应"（无障碍服务未连接时先落盘，连上后自动执行）。

package com.example.finance.data

import android.content.Context
import com.example.finance.utils.SecurePrefs

class UserSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    /** AI 推荐后自动打开美团并搜索（默认关闭：先给用户看结果，由用户决定是否执行） */
    var autoExecuteAiSearch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXECUTE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_EXECUTE, value).apply()
        }

    /** 单次抓取账单的条数上限（支付宝/美团/淘宝共用；默认 100，范围 10..500） */
    var fetchBillLimit: Int
        get() = prefs.getInt(KEY_FETCH_LIMIT, 100).coerceIn(10, 500)
        set(value) {
            prefs.edit().putInt(KEY_FETCH_LIMIT, value.coerceIn(10, 500)).apply()
        }

    /** 待执行"抓取支付宝账单"请求标记（无障碍服务连接后自动消费） */
    var pendingBillFetch: Boolean
        get() = prefs.getBoolean(KEY_PENDING_FETCH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PENDING_FETCH, value).apply()
        }

    /** 无障碍服务是否已连接（由 FinanceAccessibilityService 生命周期写入，作为 UI 门控信号） */
    var a11yServiceConnected: Boolean
        get() = prefs.getBoolean(KEY_A11Y_CONNECTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_A11Y_CONNECTED, value).apply()
        }

    /** 下单前 AI 判断（结算页守卫悬浮窗）：默认开，可在「我的」关闭 */
    var judgeBeforeOrderEnabled: Boolean
        get() = prefs.getBoolean(KEY_JUDGE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_JUDGE_ENABLED, value).apply()
        }

    // ============ DeepSeek 云端配置（运行时覆盖，留空则用 BuildConfig 默认） ============

    /** DeepSeek API Key（如 sk-xxx）——Keystore AES-GCM 加密后落盘（兼容旧明文自动迁移） */
    var deepseekApiKey: String
        get() {
            val raw = prefs.getString(KEY_DS_API_KEY, "") ?: ""
            if (raw.isBlank()) return ""
            if (raw.startsWith(SecurePrefs.ENC_PREFIX)) {
                return SecurePrefs.decrypt(raw) ?: "" // 解密失败按空处理（避免崩/泄露）
            }
            return raw // 旧版明文（启动时 migrateKeyStorageIfNeeded 会转加密）
        }
        set(value) {
            val v = value.trim()
            prefs.edit()
                .putString(
                    KEY_DS_API_KEY,
                    if (v.isBlank()) "" else (SecurePrefs.encrypt(v) ?: v) // 失败回退原文，绝不空写丢数据
                )
                .apply()
        }

    /** 把旧版明文 Key 迁移为加密存储（幂等；启动时调用一次即可） */
    fun migrateKeyStorageIfNeeded() {
        val raw = prefs.getString(KEY_DS_API_KEY, "") ?: ""
        if (raw.isNotBlank() && !raw.startsWith(SecurePrefs.ENC_PREFIX)) {
            deepseekApiKey = raw // 走 setter 重新加密写入
        }
    }

    /** 模型名（如 deepseek-chat / deepseek-reasoner / 多模态预览模型） */
    var deepseekModel: String
        get() = prefs.getString(KEY_DS_MODEL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DS_MODEL, value.trim()).apply()
        }

    /** Base URL（一般无需改，默认 https://api.deepseek.com） */
    var deepseekBaseUrl: String
        get() = prefs.getString(KEY_DS_BASE_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DS_BASE_URL, value.trim()).apply()
        }

    fun saveDeepSeek(apiKey: String, model: String, baseUrl: String) {
        val v = apiKey.trim()
        val keyStore = if (v.isBlank()) "" else (SecurePrefs.encrypt(v) ?: v)
        prefs.edit()
            .putString(KEY_DS_API_KEY, keyStore)
            .putString(KEY_DS_MODEL, model.trim())
            .putString(KEY_DS_BASE_URL, baseUrl.trim())
            .apply()
    }

    private companion object {
        const val KEY_AUTO_EXECUTE = "auto_execute_ai_search"
        const val KEY_PENDING_FETCH = "pending_bill_fetch"
        const val KEY_A11Y_CONNECTED = "a11y_service_connected"
        const val KEY_FETCH_LIMIT = "fetch_bill_limit"
        const val KEY_JUDGE_ENABLED = "judge_before_order_enabled"
        const val KEY_DS_API_KEY = "deepseek_api_key"
        const val KEY_DS_MODEL = "deepseek_model"
        const val KEY_DS_BASE_URL = "deepseek_base_url"
    }
}

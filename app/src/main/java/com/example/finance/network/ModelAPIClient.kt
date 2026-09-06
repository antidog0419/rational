// network/ModelAPIClient.kt
// DeepSeek（OpenAI 兼容协议）HTTP 客户端。
//   · 端点: {baseUrl}/chat/completions   （默认 https://api.deepseek.com）
//   · 鉴权: Authorization: Bearer <API Key>
//   · 文本: messages[].content = 纯文本
//   · 图片: 按 OpenAI 视觉消息格式 content=[{type:"text"..},{type:"image_url",image_url:{url:"data:image/jpeg;base64,.."}}]
//           —— 仅当配置的模型支持视觉输入时有效（如多模态预览模型），纯文本模型会返回错误并由上层降级处理。
//   · 约定：失败时返回以 "AI 服务错误" 开头的字符串，上层（AIService）据此降级到端侧引擎。
// 配置来源优先级：App 设置页运行时写入（UserSettings）> BuildConfig 编译期默认值。

package com.example.finance.network

import com.example.finance.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import android.util.Log
import java.util.concurrent.TimeUnit

object ModelAPIClient {

    const val TAG = "ModelAPI"
    private const val ERROR_PREFIX = "AI 服务错误"

    /** DeepSeek 多模态（视觉）预览模型：用于"看懂屏幕、定位元素" */
    const val VISION_MODEL_NAME = "deepseek-v4-flash-vision-exp"

    // 运行时配置（初始值 = BuildConfig 编译期默认；可被 App 设置页覆盖并持久化）
    @Volatile
    private var apiKey: String = BuildConfig.DEEPSEEK_API_KEY
    @Volatile
    private var model: String = BuildConfig.DEEPSEEK_MODEL
    @Volatile
    private var baseUrl: String = BuildConfig.DEEPSEEK_BASE_URL

    /** 由 FinanceApp 启动时（或设置页保存后）注入运行配置；空值保持原默认 */
    fun updateConfig(apiKey: String?, model: String?, baseUrl: String?) {
        if (!apiKey.isNullOrBlank()) this.apiKey = apiKey.trim()
        if (!model.isNullOrBlank()) this.model = model.trim()
        if (!baseUrl.isNullOrBlank()) this.baseUrl = baseUrl.trim()
        Log.d(TAG, "DeepSeek 配置已更新: model=$model baseUrl=$baseUrl keyConfigured=${!apiKey.isNullOrBlank()}")
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)   // 连接超时
                readTimeout(60, TimeUnit.SECONDS)      // 读取超时
                writeTimeout(60, TimeUnit.SECONDS)     // 发送超时
            }
        }
    }

    // ==================== 对外 API ====================

    /** 纯文本对话，返回模型回复；失败返回 "AI 服务错误: ..." */
    suspend fun chat(prompt: String): String {
        if (apiKey.isBlank()) return "$ERROR_PREFIX: 未配置 DeepSeek API Key（可在 App 设置页填写）"
        return requestChatCompletion(jsonElementOfText(prompt), model, 800)
    }

    /**
     * 带图片的对话（OpenAI 视觉消息格式）。
     * 若当前模型不支持图片输入，DeepSeek 会返回错误 → 由上层降级并友好提示。
     */
    suspend fun chatWithImage(prompt: String, imageBase64: String): String {
        if (apiKey.isBlank()) return "$ERROR_PREFIX: 未配置 DeepSeek API Key（可在 App 设置页填写）"
        return requestChatCompletion(jsonElementOfImage(prompt, imageBase64), model, 800)
    }

    /** 专用视觉模型：看屏幕截图回答问题（推理型模型，放宽 token 上限以获取最终正文） */
    suspend fun chatWithVision(prompt: String, imageBase64: String): String {
        if (apiKey.isBlank()) return "$ERROR_PREFIX: 未配置 DeepSeek API Key（可在 App 设置页填写）"
        return requestChatCompletion(jsonElementOfImage(prompt, imageBase64), VISION_MODEL_NAME, 2000)
    }

    // ==================== 内部实现 ====================

    private fun jsonElementOfText(prompt: String): JsonElement = buildJsonObject {
        put("role", "user")
        put("content", prompt)
    }

    /** OpenAI 视觉格式：content 为数组 [文本, 图片 dataURL] */
    private fun jsonElementOfImage(prompt: String, imageBase64: String): JsonElement = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                }
            )
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:image/jpeg;base64,$imageBase64")
                    }
                }
            )
        }
    }

    private suspend fun requestChatCompletion(
        userMessage: JsonElement,
        modelName: String,
        maxTokens: Int
    ): String {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val body = buildJsonObject {
            put("model", modelName)
            put("stream", false)
            put("max_tokens", maxTokens)
            put("temperature", 1.0)
            putJsonArray("messages") {
                add(userMessage)
            }
        }.toString()

        Log.d(TAG, "→ POST $url model=$modelName (messages=${userMessage.toString().length} bytes)")
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(body)
            }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                parseContent(text)
            } else {
                parseApiError(text) ?: "$ERROR_PREFIX: HTTP ${response.status.value} $text"
            }
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek 请求失败", e)
            "$ERROR_PREFIX: ${e.message ?: "未知网络错误"}"
        }
    }

    /** 成功响应中提取 choices[0].message.content */
    private fun parseContent(body: String): String {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            root["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?: run { "$ERROR_PREFIX: 响应中没有可用内容" }
        } catch (e: Exception) {
            Log.e(TAG, "响应解析失败: $body", e)
            "$ERROR_PREFIX: 响应解析失败 ${e.message}"
        }
    }

    /** 失败响应中提取 error.message */
    private fun parseApiError(body: String): String? {
        return try {
            val err = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
            msg?.let { "$ERROR_PREFIX: $it" }
        } catch (e: Exception) {
            null
        }
    }
}

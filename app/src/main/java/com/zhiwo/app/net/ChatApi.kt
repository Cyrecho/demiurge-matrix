package com.zhiwo.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApiMessage(
    val role: String,
    val content: String,
    /** 已经编码好的 data:image/...;base64,... 字符串。只有 user 消息能带图。 */
    val images: List<String> = emptyList(),
    /** Files API 上传后拿到的 file_id，用文件块引用。 */
    val files: List<String> = emptyList()
)

sealed interface StreamEvent {
    data class Reasoning(val text: String) : StreamEvent
    data class Delta(val text: String) : StreamEvent
    /** 服务端正在联网搜索或读网页，detail 是查询词或 URL。 */
    data class Searching(val kind: String, val detail: String) : StreamEvent
    /** 回答引用了哪些网页。流式时在最后一块里一次性给全。 */
    data class Citations(val items: List<Pair<String, String>>) : StreamEvent
    data class Failed(val message: String) : StreamEvent
    data object Done : StreamEvent
}

/**
 * OpenAI 兼容协议。baseUrl 换掉就能接别家（Kimi / 智谱 / OpenRouter / 本地 Ollama）。
 * 思维链在流式返回里叫 reasoning_content。
 */
class ChatApi(private val baseUrl: String, private val apiKey: String) {

    fun stream(
        model: String,
        messages: List<ApiMessage>,
        thinking: Boolean,
        effort: String,
        temperature: Float,
        webSearch: Boolean = false,
        webRead: Boolean = false
    ): Flow<StreamEvent> = flow {
        if (apiKey.isBlank()) {
            emit(StreamEvent.Failed("还没有填 API Key，去设置里填一下"))
            return@flow
        }

        var failed = false

        try {
            val request = Request.Builder()
                .url(endpoint())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(buildBody(model, messages, thinking, effort, temperature, webSearch, webRead))
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(500)
                    failed = true
                    emit(StreamEvent.Failed(friendlyError(resp.code, body)))
                    return@use
                }
                val source = resp.body?.source()
                if (source == null) {
                    failed = true
                    emit(StreamEvent.Failed("响应为空"))
                    return@use
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    // 服务端工具的进度：call_started 里带着查询词或 URL
                    val toolEvent = choices.optJSONObject(0)?.optJSONObject("server_tool_event")
                    if (toolEvent != null && toolEvent.optString("type") == "call_started") {
                        val action = toolEvent.optJSONObject("call")?.optJSONObject("action")
                        val kind = action?.optString("type").orEmpty()
                        val queries = action?.optJSONArray("queries")
                        val detail = when {
                            queries != null && queries.length() > 0 -> queries.optString(0)
                            else -> action?.optString("url").orEmpty()
                        }
                        emit(StreamEvent.Searching(kind, detail))
                    }
                    // 注意：JSON 里 value 为 null 时 optString 会返回字符串 "null"，
                    // 思考阶段 content 是 null、回答阶段 reasoning_content 是 null，
                    // 直接拼进去就会满屏 null。所以必须按类型判断。
                    val reasoning = fieldText(delta, "reasoning_content")
                        .ifEmpty { fieldText(delta, "reasoning") }
                    if (reasoning.isNotEmpty()) emit(StreamEvent.Reasoning(reasoning))
                    val content = fieldText(delta, "content")
                    if (content.isNotEmpty()) emit(StreamEvent.Delta(content))

                    // 引用在最后一块里一次性给全：annotations[].url_citation
                    val annotations = delta.optJSONArray("annotations")
                    if (annotations != null && annotations.length() > 0) {
                        val items = ArrayList<Pair<String, String>>()
                        for (k in 0 until annotations.length()) {
                            val cit = annotations.optJSONObject(k)
                                ?.optJSONObject("url_citation") ?: continue
                            val url = cit.optString("url")
                            if (url.isBlank()) continue
                            items.add(cit.optString("title").ifBlank { url } to url)
                        }
                        if (items.isNotEmpty()) emit(StreamEvent.Citations(items))
                    }
                }
            }
            if (!failed) emit(StreamEvent.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(StreamEvent.Failed(e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private fun endpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** 只有真正的字符串才取值；JSONObject.NULL、缺失、数字都当空。 */
    private fun fieldText(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        return if (value is String) value else ""
    }

    /** 把接口的原始报错翻译成人话，别把 JSON 直接甩给用户。 */
    private fun friendlyError(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return when {
            code == 401 -> "API Key 不对或已经过期，去设置里重新填一下"
            code == 402 -> "账户余额不足，去 platform.deepseek.com 充点钱"
            code == 429 -> "请求太频繁了，等几秒再试"
            code == 400 && detail.contains("context", ignoreCase = true) ->
                "这段对话太长了，模型装不下，开个新对话吧"
            code >= 500 -> "服务器那边出问题了（$code），过一会儿再试"
            detail.isNotBlank() -> "请求失败（$code）：$detail"
            else -> "请求失败（HTTP $code）"
        }
    }

    private fun buildBody(
        model: String,
        messages: List<ApiMessage>,
        thinking: Boolean,
        effort: String,
        temperature: Float,
        webSearch: Boolean,
        webRead: Boolean
    ) = JSONObject().apply {
        put("model", model)
        put("stream", true)
        val arr = JSONArray()
        messages.forEach { m ->
            val item = JSONObject().put("role", m.role)
            if (m.images.isEmpty() && m.files.isEmpty()) {
                item.put("content", m.content)
            } else {
                // 带附件时 content 必须是块数组：文档 → 文字 → 图片
                val blocks = JSONArray()
                m.files.forEach { id ->
                    blocks.put(JSONObject().put("type", "file").put("file_id", id))
                }
                if (m.content.isNotBlank()) {
                    blocks.put(JSONObject().put("type", "text").put("text", m.content))
                }
                m.images.forEach { dataUrl ->
                    blocks.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl))
                    )
                }
                item.put("content", blocks)
            }
            arr.put(item)
        }
        put("messages", arr)
        if (thinking) {
            put("thinking", JSONObject().put("type", "enabled"))
            put("reasoning_effort", effort)
        } else {
            put("thinking", JSONObject().put("type", "disabled"))
            put("temperature", temperature.toDouble())
        }
        if (webSearch || webRead) {
            val tools = JSONArray()
            if (webSearch) tools.put(JSONObject().put("type", "deepseek_web_search"))
            if (webRead) tools.put(JSONObject().put("type", "deepseek_read_url"))
            put("tools", tools)
        }
    }.toString().toRequestBody("application/json".toMediaType())

    companion object {
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

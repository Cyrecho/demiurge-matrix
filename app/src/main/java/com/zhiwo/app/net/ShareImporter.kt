package com.zhiwo.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ImportedTurn(
    val role: String,
    val content: String,
    val thinking: String,
    /** 分享页里的图片地址（绝对 URL），导入时下载到本地。 */
    val imageUrls: List<String> = emptyList()
)

data class SharePreview(
    val shareId: String,
    val title: String,
    val turns: List<ImportedTurn>
) {
    val totalChars: Int get() = turns.sumOf { it.content.length }
}

/**
 * 抓 DeepSeek 分享链接。
 * 分享页是前端渲染的，但 /api/v0/share/content 这个接口不用登录就能拿到完整 JSON。
 * 对方哪天改接口，这里会先坏 —— 所以错误信息要能看懂。
 */
object ShareImporter {

    private val idPattern = Regex("""share/([A-Za-z0-9]+)""")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun extractId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        idPattern.find(text)?.let { return it.groupValues[1] }
        if (text.matches(Regex("[A-Za-z0-9]{8,}"))) return text
        return null
    }

    /**
     * 分享返回的 signed_path 是 "/file?file_id=...&state=..."，
     * 但它属于独立的文件服务，不是 chat.deepseek.com 的页面路由：
     *   - 域名要换成 files.deepseeksvc.com
     *   - 路径前面要加 /api
     *   - 必须带 ty=p（图片预览类型），否则 400
     * 这三条都是从网页端 JS 里扒出来的，缺一条就返回 HTML 或报错。
     */
    private fun fileUrl(path: String): String {
        if (path.startsWith("http")) {
            return if (path.contains("?")) "$path&ty=p" else "$path?ty=p"
        }
        val base = "https://files.deepseeksvc.com/api"
        val full = if (path.startsWith("/")) base + path else "$base/$path"
        return if (full.contains("?")) "$full&ty=p" else "$full?ty=p"
    }

    suspend fun fetch(input: String): SharePreview = withContext(Dispatchers.IO) {
        val id = extractId(input) ?: throw IllegalArgumentException("这不像一个分享链接")
        val request = Request.Builder()
            .url("https://chat.deepseek.com/api/v0/share/content?share_id=$id")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            )
            .addHeader("Referer", "https://chat.deepseek.com/share/$id")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}，链接可能失效了")
            }
            val body = resp.body?.string().orEmpty()
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException("接口返回 ${root.optString("msg", "未知错误")}")
            }
            val biz = root.optJSONObject("data")?.optJSONObject("biz_data")
                ?: throw IllegalStateException("返回里没有对话内容")
            val array = biz.optJSONArray("messages")
                ?: throw IllegalStateException("返回里没有 messages")

            val turns = ArrayList<ImportedTurn>()
            for (i in 0 until array.length()) {
                val m = array.optJSONObject(i) ?: continue
                val role = when (m.optString("role").uppercase()) {
                    "USER" -> "user"
                    "ASSISTANT" -> "assistant"
                    else -> continue
                }
                val fragments = m.optJSONArray("fragments") ?: continue
                val sbContent = StringBuilder()
                val sbThink = StringBuilder()
                val images = ArrayList<String>()
                for (j in 0 until fragments.length()) {
                    val f = fragments.optJSONObject(j) ?: continue
                    val type = f.optString("type").uppercase()
                    val raw = f.opt("content")
                    val text = if (raw is String) raw else ""
                    when (type) {
                        "REQUEST", "RESPONSE" -> sbContent.append(text)
                        "THINK" -> sbThink.append(text)
                        // 用户发过的图片/文件：fragment 的 content 是空的，图在 files 数组里
                        "FILE" -> {
                            val files = f.optJSONArray("files") ?: continue
                            for (k in 0 until files.length()) {
                                val file = files.optJSONObject(k) ?: continue
                                if (!file.optBoolean("is_image")) continue
                                if (file.optString("status") != "SUCCESS") continue
                                val path = file.optString("signed_path")
                                if (path.isNotBlank()) {
                                    images.add(fileUrl(path))
                                }
                            }
                        }
                        else -> Unit // TIP 之类的免责声明，丢掉
                    }
                }
                if (sbContent.isBlank() && images.isEmpty()) continue
                turns.add(ImportedTurn(role, sbContent.toString(), sbThink.toString(), images))
            }

            if (turns.isEmpty()) throw IllegalStateException("没解析出任何消息")

            val firstUser = turns.firstOrNull { it.role == "user" }?.content
                ?.replace("\n", " ")
                ?.trim()
                ?.take(24)
                ?.takeIf { it.isNotBlank() } ?: "导入的对话"
            SharePreview(
                shareId = id,
                title = firstUser.replace("\n", " ").take(24),
                turns = turns
            )
        }
    }
}

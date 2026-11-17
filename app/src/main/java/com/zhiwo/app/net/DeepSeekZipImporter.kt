package com.zhiwo.app.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * 解析 DeepSeek 官方的「导出所有历史对话」压缩包。
 *
 * 结构：
 * ```
 * manifest.json
 * user.json
 * chat_session_index.json
 * chat_sessions/<uuid>.json     ← 每个会话一个文件
 * ```
 *
 * 每个会话的 messages 带数字 id 和 parent_id，本身已经是一棵树；
 * fragments 里混着 REQUEST / RESPONSE / THINK / FILE / TOOL_* 等。
 *
 * 附件只导出了文件名（export_path 永远是 null），文件本体不在包里。
 */
object DeepSeekZipImporter {

    data class ImportedMessage(
        val oldId: Long,
        val parentOldId: Long?,
        val role: String,
        val content: String,
        val thinking: String,
        val fileNames: List<String>
    )

    data class ImportedSession(
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ImportedMessage>,
        /** 当前停在哪个叶子（原 id）。 */
        val leafOldId: Long?
    )

    /**
     * 流式读 zip，逐个会话解析。大文件（这份 215MB）不会一次性进内存。
     */
    suspend fun parse(
        context: Context,
        uri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<ImportedSession>> = withContext(Dispatchers.IO) {
        try {
            val sessions = ArrayList<ImportedSession>()
            var done = 0
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.startsWith("chat_sessions/") && name.endsWith(".json")) {
                            val bytes = zip.readBytes()
                            if (bytes.isNotEmpty()) {
                                parseSession(String(bytes, Charsets.UTF_8))?.let { sessions.add(it) }
                            }
                            done++
                            onProgress(done, 0)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(Exception("打不开这个文件"))

            // 按创建时间从旧到新，导入后顺序自然
            sessions.sortBy { it.createdAt }
            Result.success(sessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSession(json: String): ImportedSession? {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("messages") ?: return null
            val messages = ArrayList<ImportedMessage>(arr.length())

            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = when (m.optString("role")) {
                    "USER" -> "user"
                    "ASSISTANT" -> "assistant"
                    else -> continue
                }
                var content = ""
                var thinking = ""
                val files = ArrayList<String>()

                val frags = m.optJSONArray("fragments")
                if (frags != null) {
                    for (j in 0 until frags.length()) {
                        val f = frags.optJSONObject(j) ?: continue
                        when (f.optString("type")) {
                            "REQUEST", "RESPONSE", "TEMPLATE_RESPONSE" ->
                                f.optString("content").takeIf { it.isNotEmpty() }?.let { content = it }
                            "THINK" ->
                                f.optString("content").takeIf { it.isNotEmpty() }?.let { thinking = it }
                            "FILE" -> {
                                val list = f.optJSONArray("files") ?: continue
                                for (k in 0 until list.length()) {
                                    val file = list.optJSONObject(k) ?: continue
                                    val fileName = file.optString("file_name")
                                    if (fileName.isNotBlank()) files.add(fileName)
                                }
                            }
                            else -> Unit  // TOOL_SEARCH / TOOL_OPEN / CONTENT_RISK 丢掉
                        }
                    }
                }

                val parent = m.opt("parent_id")
                messages.add(
                    ImportedMessage(
                        oldId = m.optLong("id"),
                        parentOldId = if (parent is Number) parent.toLong() else null,
                        role = role,
                        content = content.trim(),
                        thinking = thinking.trim(),
                        fileNames = files
                    )
                )
            }

            if (messages.isEmpty()) return null
            val leaf = obj.opt("current_message_id")

            ImportedSession(
                title = obj.optString("title").ifBlank { "未命名对话" }.take(60),
                createdAt = parseTime(obj.optString("inserted_at")),
                updatedAt = parseTime(obj.optString("updated_at")),
                messages = messages,
                leafOldId = if (leaf is Number) leaf.toLong() else null
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTime(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(iso).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }
}

package com.zhiwo.app.net

import kotlinx.coroutines.flow.toList
import org.json.JSONObject

/**
 * 归档助手：给一段对话起标题，并判断它该进哪个知识库。
 * 单独走一次很便宜的调用，失败就当作没发生过，绝不影响正常聊天。
 */
object Archiver {

    private const val SYSTEM = """你在帮用户整理对话记录。读下面这段对话，做两件事：

1. 起标题：不超过 14 个汉字，要具体。不要「关于……的讨论」「一次对话」这种空话。
2. 归库：判断它属于哪个知识库。库名要是一个学科或主题（例如「线性代数」「小说世界观」「安卓开发」「求职」）。
   如果对话没有明确主题，返回空字符串。

只输出 JSON，不要任何解释：
{"title": "标题", "kb": "库名或空字符串"}"""

    suspend fun suggest(
        baseUrl: String,
        apiKey: String,
        model: String,
        snippet: String
    ): Pair<String, String>? {
        if (apiKey.isBlank()) return null
        return try {
            val api = ChatApi(baseUrl, apiKey)
            val events = api.stream(
                model = model,
                messages = listOf(
                    ApiMessage("system", SYSTEM),
                    ApiMessage("user", snippet.take(4000))
                ),
                thinking = false,
                effort = "low",
                temperature = 0.3f
            ).toList()

            val text = buildString {
                events.forEach { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> append(ev.text)
                        is StreamEvent.Failed -> return null
                        else -> Unit
                    }
                }
            }
            parse(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(raw: String): Pair<String, String>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(raw.substring(start, end + 1))
            val title = obj.optString("title").trim().take(24)
            val kb = obj.optString("kb").trim()
            if (title.isBlank()) null else title to kb
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 一键整理 ----------------

    data class SessionBrief(
        val id: String,
        val currentTitle: String,
        val snippet: String
    )

    data class Assignment(
        val id: String,
        val title: String,
        val kb: String,
        val summary: String = "",
        /** 这个库该有多重的记忆分量，0-100。只在新建库时采用。 */
        val kbWeight: Int = 50
    )

    private val ORGANIZE_SYSTEM = """你在帮用户整理他所有的对话记录，把它们归进知识库。

对每个会话做三件事：
1. 起标题：不超过 14 个汉字，要具体，能一眼看出聊了什么。不要「关于……的讨论」。
2. 归库：优先使用「已有知识库」里的库名。只有当某主题明显不属于任何已有库、
   而且本批里至少有两个会话属于它时，才新建一个库名。
3. 写摘要：80 到 150 字。必须包含这四件事：
   - 这段对话在解决什么问题（背景）
   - 得出了什么结论、做成了什么
   - 用户的立场、偏好，或者卡住的地方
   - 有没有没解决的尾巴
   要写成能直接拿来回忆的笔记。不要写「用户询问了……」这种流水账，
   也不要复述课本知识本身 —— 记的是「他和我之间发生了什么」，不是抄一遍教科书。

另外，如果是新建库，顺便给这个库一个记忆权重 kbWeight（0-100），按主题性质判断：
- 关于用户本人：生活、情绪、关系、经历、他在意的人和事 → 70 到 85（这是老朋友最该记住的）
- 创作、世界观、故事设定 → 55 到 65
- 工作、技术、工具折腾 → 45 到 55
- 学术、习题、课本知识 → 30 到 45（查得到的东西，不必记那么死）
已有库不用给，保持原样。

库名要是学科或主题级别（「线性代数」「小说世界观」「安卓开发」「求职」），
不要细到「矩阵的秩」「第三章」这种。

只输出 JSON，不要解释：
{"assign":[{"id":"会话id","title":"标题","kb":"库名","summary":"摘要","kbWeight":60}]}"""

    suspend fun organize(
        baseUrl: String,
        apiKey: String,
        model: String,
        existingKbs: List<String>,
        briefs: List<SessionBrief>
    ): List<Assignment>? {
        if (apiKey.isBlank() || briefs.isEmpty()) return null
        val body = buildString {
            append("已有知识库：")
            append(if (existingKbs.isEmpty()) "（空）" else existingKbs.joinToString("、"))
            append("\n\n会话列表：\n")
            briefs.forEach { b ->
                append("\n[id] ").append(b.id)
                append("\n[现标题] ").append(b.currentTitle.take(40))
                append("\n[内容] ").append(b.snippet.take(700))
                append("\n")
            }
        }
        return try {
            val api = ChatApi(baseUrl, apiKey)
            val events = api.stream(
                model = model,
                messages = listOf(
                    ApiMessage("system", ORGANIZE_SYSTEM),
                    ApiMessage("user", body)
                ),
                thinking = false,
                effort = "low",
                temperature = 0.2f
            ).toList()

            val text = buildString {
                events.forEach { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> append(ev.text)
                        is StreamEvent.Failed -> return null
                        else -> Unit
                    }
                }
            }
            parseAssignments(text)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 用户档案 ----------------

    private const val DISTILL_SYSTEM = """从下面这段对话里，提炼关于「用户本人」的事实。

只写关于他这个人的：身份、背景、专业、偏好、立场、正在做的事、在意的人或事、
说话和思考的习惯。不要写他在问什么题、聊了什么知识 —— 那些属于知识库，不属于档案。

要求：
- 每条一句话，具体到能拿去用。不要「他喜欢学习」这种空话，
  要写「他在准备考研，数学基础还行，但线代生疏」这种。
- 最多 8 条，宁缺毋滥。没有值得记的就返回空数组。
- 只输出 JSON：{"facts":["...","..."]}"""

    suspend fun distill(
        baseUrl: String,
        apiKey: String,
        model: String,
        content: String
    ): List<String> {
        if (apiKey.isBlank() || content.isBlank()) return emptyList()
        return try {
            val api = ChatApi(baseUrl, apiKey)
            val events = api.stream(
                model = model,
                messages = listOf(
                    ApiMessage("system", DISTILL_SYSTEM),
                    ApiMessage("user", content.take(12000))
                ),
                thinking = false,
                effort = "low",
                temperature = 0.3f
            ).toList()

            val text = buildString {
                events.forEach { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> append(ev.text)
                        is StreamEvent.Failed -> return emptyList()
                        else -> Unit
                    }
                }
            }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val arr = JSONObject(text.substring(start, end + 1)).optJSONArray("facts")
                ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- 总 AI 的历史压缩 ----------------

    private const val COMPRESS_SYSTEM = """你和用户是一对老朋友，下面是一段你们过去的对话。

把它压缩成 200 到 400 字的记忆，用第一人称「我」来写，像你在心里记下的：
- 那天聊了什么、为什么聊起
- 他的情绪、态度，他说过的关键的话
- 你们得出的结论，或者没说完的尾巴
- 保留具体细节：时间、人名、原话片段。不要写成抽象总结。

只输出记忆正文，不要 JSON，不要标题，不要解释。"""

    suspend fun compressMaster(
        baseUrl: String,
        apiKey: String,
        model: String,
        content: String
    ): String? {
        if (apiKey.isBlank() || content.isBlank()) return null
        return try {
            val api = ChatApi(baseUrl, apiKey)
            val events = api.stream(
                model = model,
                messages = listOf(
                    ApiMessage("system", COMPRESS_SYSTEM),
                    ApiMessage("user", content.take(20000))
                ),
                thinking = false,
                effort = "low",
                temperature = 0.4f
            ).toList()

            val text = buildString {
                events.forEach { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> append(ev.text)
                        is StreamEvent.Failed -> return null
                        else -> Unit
                    }
                }
            }.trim()
            text.takeIf { it.isNotBlank() }?.take(1200)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAssignments(raw: String): List<Assignment>? {        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val root = JSONObject(raw.substring(start, end + 1))
            val arr = root.optJSONArray("assign") ?: return null
            val out = ArrayList<Assignment>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val title = o.optString("title").trim().take(24)
                val kb = o.optString("kb").trim()
                val summary = o.optString("summary").trim().take(200)
                val kbWeight = o.optInt("kbWeight", 50).coerceIn(0, 100)
                if (id.isNotBlank() && title.isNotBlank()) {
                    out.add(Assignment(id, title, kb, summary, kbWeight))
                }
            }
            out.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}

package com.zhiwo.app.data

/** 把对话导成 Markdown。只导当前分支，不导被切走的历史分支。 */
object Exporter {

    private fun safeFileName(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
        return (cleaned.ifBlank { "对话" }).take(40) + ".md"
    }

    fun fileNameFor(conv: Conversation): String = safeFileName(conv.title)

    fun conversationMarkdown(
        conv: Conversation,
        messages: List<Message>,
        kbName: String?
    ): String = buildString {
        append("# ").append(conv.title).append("\n\n")
        append("> ")
        if (kbName != null) append(kbName).append(" · ")
        append(messages.size).append(" 条消息")
        append(" · ").append(formatDate(conv.createdAt))
        if (conv.summary?.isNotBlank() == true) {
            append("\n>\n> **已压缩的早期内容**：").append(conv.summary)
        }
        append("\n\n---\n\n")

        messages.forEach { m ->
            if (m.isUser) {
                append("### 我\n\n")
                if (m.attachments.isNotEmpty()) {
                    val docs = m.attachments.filterNot { it.startsWith("/") }
                    val imgs = m.attachments.filter { it.startsWith("/") }
                    imgs.forEach { append("![图片](").append(it).append(")\n\n") }
                    docs.forEach { append("[附件] ").append(it.substringAfter("|")).append("\n\n") }
                }
                append(m.content.trim()).append("\n\n")
            } else {
                append("### 助手\n\n")
                if (m.thinking.isNotBlank()) {
                    append("<details><summary>思考过程</summary>\n\n")
                    append(m.thinking.trim()).append("\n\n</details>\n\n")
                }
                append(m.content.trim()).append("\n\n")
                if (m.citations.isNotEmpty()) {
                    append("**参考来源**\n\n")
                    m.citations.forEach { c ->
                        append("- [").append(c.title).append("](").append(c.url).append(")\n")
                    }
                    append("\n")
                }
            }
            append("---\n\n")
        }
    }

    fun allMarkdown(
        conversations: List<Conversation>,
        kbs: List<Kb>,
        messagesOf: (String) -> List<Message>
    ): String = buildString {
        append("# 德谬歌矩阵 · 全部对话导出\n\n")
        append("> 导出于 ").append(formatDate(System.currentTimeMillis()))
        append("，共 ").append(conversations.size).append(" 条对话\n\n")

        val kbNameById = kbs.associate { it.id to it.name }
        val groups = LinkedHashMap<String, MutableList<Conversation>>()
        conversations.forEach { c ->
            val key = c.kbId?.let { kbNameById[it] } ?: "未分类"
            groups.getOrPut(key) { mutableListOf() }.add(c)
        }

        groups.forEach { (kbName, list) ->
            append("\n\n# 【").append(kbName).append("】\n\n")
            list.forEach { conv ->
                append("\n\n## ").append(conv.title).append("\n\n")
                append("> ").append(formatDate(conv.createdAt)).append("\n\n")
                messagesOf(conv.id).forEach { m ->
                    if (m.isUser) {
                        append("**我**：").append(m.content.trim().replace("\n", "\n> ")).append("\n\n")
                    } else {
                        append("**助手**：\n\n").append(m.content.trim()).append("\n\n")
                    }
                }
            }
        }
    }

    private fun formatDate(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }
}

package com.zhiwo.app.data

/** 总 AI 自己的对话被压缩后留下的记忆片段。 */
data class MasterMemory(
    val id: String,
    val text: String,
    /** 默认比任何知识库都高：那是你们俩直接的交流。 */
    val weight: Int = 75,
    val createdAt: Long
)

/** 一条会话。activeLeafId 指向当前所在的分支末端。 */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val activeLeafId: String?,
    val systemPrompt: String? = null,
    val pinned: Boolean = false,
    /** 归属的知识库；null 表示未分类。 */
    val kbId: String? = null,
    /** 用户手动指定过库。置位后「一键整理」不再动它。 */
    val kbLocked: Boolean = false,
    /** 这条对话的摘要，总 AI 靠它记住你聊过什么。 */
    val summary: String? = null,
    /** 已经提炼进用户档案。 */
    val inProfile: Boolean = false,
    /** 总 AI 自己那一条特殊会话。 */
    val isMaster: Boolean = false,
    /**
     * 主实例。总 AI 可以开多个（各自独立的对话），
     * 但只有第一个是主实例：不能删、不能改名。
     */
    val isPrimary: Boolean = false,
    /**
     * 这个总 AI 实例只关心哪些知识库。
     * 空 = 全部；非空 = 记忆和检索都收在这几个库里。
     */
    val kbScope: List<String> = emptyList()
)

/** 知识库：一个主题下的会话集合。 */
data class Kb(
    val id: String,
    val name: String,
    val createdAt: Long,
    val sort: Int = 0,
    /**
     * 记忆权重 0-100。总 AI 组装档案时按它排序：
     * 高权重的库给完整摘要，低权重的只留标题。
     * 调权重不会删任何数据，只影响「被想起的概率」。
     */
    val weight: Int = 50
)

/** 联网搜索带回来的来源。 */
data class Citation(val title: String, val url: String)

/** 一条消息。parentId 让它成为一棵树 —— 重新生成和编辑都会长出新分支。 */
data class Message(
    val id: String,
    val convId: String,
    val parentId: String?,
    val role: String,          // user / assistant / system
    val content: String,
    val thinking: String = "",
    val status: String = "done", // done / streaming / error
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    /** 图片等附件，存本地文件路径。导入自分享链接的会先下载到 app 私有目录。 */
    val attachments: List<String> = emptyList(),
    /** 联网搜索引用到的网页。 */
    val citations: List<Citation> = emptyList()
) {
    val isUser: Boolean get() = role == "user"
}

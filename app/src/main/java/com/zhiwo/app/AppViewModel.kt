package com.zhiwo.app

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.app.data.Backup
import com.zhiwo.app.data.Conversation
import com.zhiwo.app.data.Exporter
import com.zhiwo.app.data.Kb
import com.zhiwo.app.net.DeepSeekZipImporter
import com.zhiwo.app.data.Message
import com.zhiwo.app.data.Settings
import com.zhiwo.app.data.Store
import com.zhiwo.app.net.ApiMessage
import com.zhiwo.app.net.Archiver
import com.zhiwo.app.net.ChatApi
import com.zhiwo.app.net.FileUploader
import com.zhiwo.app.net.ShareImporter
import com.zhiwo.app.net.SharePreview
import com.zhiwo.app.net.StreamEvent
import com.zhiwo.app.prompt.Persona
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatState(
    val conv: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val streaming: Boolean = false,
    val liveId: String? = null,
    val liveText: String = "",
    val liveThinking: String = "",
    val error: String? = null,
    val thinkingOn: Boolean = true,
    val searchOn: Boolean = false,
    /** 联网时给用户看的进度提示，比如「正在搜：xxx」。 */
    val searchHint: String? = null,
    /** 待发送的图片（本地路径）。放在这里而不是 UI 里，避免点击发送时读到旧值。 */
    val pendingImages: List<String> = emptyList(),
    /** 待发送的文档，格式 "file-api-xxx|report.pdf"。 */
    val pendingDocs: List<String> = emptyList(),
    /** 每条消息的分支位置，key 是 messageId。只在有兄弟节点时出现。 */
    val branches: Map<String, BranchInfo> = emptyMap()
)

/** 一条消息在它那组兄弟里的位置。 */
data class BranchInfo(
    val index: Int,
    val count: Int,
    val prevId: String?,
    val nextId: String?
)

/** 一键整理的进度。 */
data class OrganizeState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null
)

/** 官方导出包导入的进度。 */
data class ZipImportState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store.get(app)
    private val settings = Settings.get(app)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _kbs = MutableStateFlow<List<Kb>>(emptyList())
    val kbs: StateFlow<List<Kb>> = _kbs.asStateFlow()

    /** 总 AI 的全部实例（主实例排最前）。必须声明在 init 之前，否则 init 里读到 null。 */
    private val _masters = MutableStateFlow<List<Conversation>>(emptyList())
    val masters: StateFlow<List<Conversation>> = _masters.asStateFlow()

    private val _organize = MutableStateFlow(OrganizeState())
    val organize: StateFlow<OrganizeState> = _organize.asStateFlow()

    /** 一次性提示，UI 弹完就清掉。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    private var job: Job? = null
    private var convId: String? = null

    init {
        refreshList()
        refreshKbs()
        refreshMasters()
    }

    fun refreshList() {
        _conversations.value = store.conversations()
        refreshMasters()
    }

    fun refreshKbs() {
        _kbs.value = store.kbs()
    }

    fun createKb(name: String, weight: Int = 50): Kb? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val kb = store.createKb(n, weight)
        refreshKbs()
        return kb
    }

    fun setKbWeight(id: String, weight: Int) {
        store.setKbWeight(id, weight)
        refreshKbs()
    }

    /**
     * 把某条对话提炼成「关于用户」的事实，追加进档案。
     * 只记他这个人和他的事，不记他问了什么题。
     */
    fun addToProfile(convId: String) {
        val key = settings.apiKey
        if (key.isBlank()) {
            _notice.value = "先去设置里填 API Key"
            return
        }
        viewModelScope.launch {
            val msgs = store.messages(convId)
            if (msgs.isEmpty()) {
                _notice.value = "这条对话还是空的"
                return@launch
            }
            val content = msgs.joinToString("\n\n") { m ->
                val who = if (m.role == "user") "用户" else "助手"
                "$who：${m.content.take(800)}"
            }
            val facts = Archiver.distill(settings.baseUrl, key, settings.model, content)
            if (facts.isEmpty()) {
                _notice.value = "这条对话里没提炼出关于你的事实"
                return@launch
            }
            val existing = store.profileText().trim()
            val block = facts.joinToString("\n") { "- $it" }
            val merged = if (existing.isBlank()) block else "$existing\n$block"
            store.setProfileText(merged.take(4000))
            store.markInProfile(convId, true)
            refreshList()
            _notice.value = "档案里多了 ${facts.size} 条关于你的事"
        }
    }

    /** 总 AI 在当前设置里的名字。 */
    val masterName: String get() = settings.masterName

    fun renameKb(id: String, name: String) {
        if (name.isBlank()) return
        store.renameKb(id, name)
        refreshKbs()
        refreshList()
        reload()
    }

    fun deleteKb(id: String) {
        store.deleteKb(id)
        refreshKbs()
        refreshList()
        reload()
    }

    fun moveConversation(convId: String, kbId: String?) {
        // 手动指定 = 加锁，之后「一键整理」不会覆盖
        store.setConversationKb(convId, kbId, manual = true)
        refreshList()
        reload()
    }

    fun unlockConversation(convId: String) {
        store.clearKbLock(convId)
        refreshList()
    }

    /** 当前这段对话占多少 token（粗估）。 */
    fun contextTokens(convId: String): Int = store.estimateTokens(convId)

    fun contextMessageCount(convId: String): Int = store.messages(convId).size

    /**
     * 手动压缩：把前半段对话总结成一段记忆，删掉原文。
     * 摘要在之后的每轮请求里作为「之前聊过」注入，所以不会断片。
     */
    fun compressConversation(convId: String) {
        val key = settings.apiKey
        if (key.isBlank()) {
            _notice.value = "先去设置里填 API Key"
            return
        }
        val msgs = store.messages(convId)
        if (msgs.size < 10) {
            _notice.value = "才 ${msgs.size} 条，还很短，不用压"
            return
        }
        val cut = msgs.size / 2
        val boundary = msgs[cut]
        val before = store.estimateTokens(convId)

        viewModelScope.launch {
            _notice.value = "正在压缩前 $cut 条…"
            val content = msgs.take(cut).joinToString("\n\n") { m ->
                val who = if (m.role == "user") "他" else "我"
                "$who：${m.content.take(800)}"
            }
            val memory = Archiver.compressMaster(
                settings.baseUrl, key, settings.model, content
            )
            if (memory == null) {
                _notice.value = "压缩失败，稍后再试"
                return@launch
            }
            val existing = store.conversation(convId)?.summary.orEmpty().trim()
            val merged = if (existing.isBlank()) memory else "$existing\n\n$memory"
            store.setSummary(convId, merged.take(3000))
            store.compressBefore(convId, boundary.id)

            val after = store.estimateTokens(convId)
            reload()
            refreshList()
            _notice.value = "压掉了 $cut 条，省下约 ${(before - after).coerceAtLeast(0)} token"
        }
    }

    /** 导出单条对话：返回 (文件名, Markdown 正文)。 */
    fun exportConversation(convId: String): Pair<String, String>? {
        val conv = store.conversation(convId) ?: return null
        val messages = store.path(convId)
        val kbName = conv.kbId?.let { id -> store.kbs().firstOrNull { it.id == id }?.name }
        return Exporter.fileNameFor(conv) to
            Exporter.conversationMarkdown(conv, messages, kbName)
    }

    /** 导出全部对话：一个按知识库分组的 Markdown。 */
    fun exportAll(): Pair<String, String> {
        val conversations = store.conversations()
        val content = Exporter.allMarkdown(conversations, store.kbs()) { store.messages(it) }
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "德谬歌矩阵-全部对话-$day.md" to content
    }

    /** 打包完整备份（数据库 + 图片），返回 zip 文件，失败返回 null。 */
    suspend fun createBackupFile(): java.io.File? = Backup.create(getApplication())

    /**
     * 从备份恢复。zip 走完整恢复（含图片），裸 db 走老格式（只有消息）。
     * 恢复完直接重启进程 —— 旧连接还指着被替换掉的文件，不重启会读到脏数据。
     */
    fun restoreFromFile(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            store.close()
            val error = Backup.restore(app, uri)
            withContext(Dispatchers.Main) {
                if (error != null) {
                    _notice.value = "恢复失败：$error"
                    restartApp(app)
                } else {
                    _notice.value = "恢复完成，正在重启"
                    restartApp(app)
                }
            }
        }
    }

    private fun restartApp(app: Application) {
        runCatching {
            val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            intent?.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            app.startActivity(intent)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** 官方 zip 导入的进度。 */
    private val _zipImport = MutableStateFlow(ZipImportState())
    val zipImport: StateFlow<ZipImportState> = _zipImport.asStateFlow()

    /**
     * 导入 DeepSeek 官方「导出所有历史对话」的 zip。
     * 大包（几百 MB、上千个会话）解析和写库都慢，用状态把进度透出去。
     */
    fun importExportZip(uri: android.net.Uri) {
        if (_zipImport.value.running) return
        viewModelScope.launch {
            _zipImport.value = ZipImportState(running = true, message = "正在读取压缩包…")
            val parsed = DeepSeekZipImporter.parse(getApplication(), uri) { done, _ ->
                if (done % 10 == 0) {
                    _zipImport.value = _zipImport.value.copy(done = done)
                }
            }
            val sessions = parsed.getOrElse { e ->
                _zipImport.value = ZipImportState(
                    message = "解析失败：${e.message ?: e.javaClass.simpleName}"
                )
                return@launch
            }
            _zipImport.value = ZipImportState(
                running = true,
                done = sessions.size,
                total = sessions.size,
                message = "正在写入数据库…"
            )
            val count = withContext(Dispatchers.IO) { store.importZipSessions(sessions) }
            refreshList()
            refreshKbs()
            _zipImport.value = ZipImportState(message = "导入了 $count 条对话")
            _notice.value = "导入了 $count 条对话"
        }
    }

    fun dismissZipImport() {
        if (!_zipImport.value.running) _zipImport.value = ZipImportState()
    }

    /** 读崩溃日志和请求日志，方便远程排查。 */    fun diagnosticLog(): String? {
        val dir = java.io.File(getApplication<Application>().filesDir, "logs")
        val parts = listOf("crash.log", "diag.log").mapNotNull { name ->
            val f = java.io.File(dir, name)
            if (f.exists() && f.length() > 0L) {
                runCatching { "===== $name =====\n" + f.readText() }.getOrNull()
            } else {
                null
            }
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /** 记一条诊断信息：崩溃之外的问题（比如请求失败）也要留痕迹。 */
    private fun recordDiag(message: String) {
        runCatching {
            val dir = java.io.File(getApplication<Application>().filesDir, "logs")
            dir.mkdirs()
            val f = java.io.File(dir, "diag.log")
            if (f.exists() && f.length() > 300 * 1024) f.writeText("")
            val stamp = java.text.SimpleDateFormat(
                "MM-dd HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())
            f.appendText("[$stamp] $message\n")
        }
    }

    fun dismissOrganize() {
        if (!_organize.value.running) _organize.value = OrganizeState()
    }

    /**
     * 一键整理：把所有会话（含导入的）分批丢给 AI，重新起标题、归库。
     * 库名优先复用已有的，避免建一堆碎库。
     */
    fun organizeAll() {
        if (_organize.value.running) return
        val key = settings.apiKey
        if (key.isBlank()) {
            _organize.value = OrganizeState(message = "先去设置里填 API Key")
            return
        }
        val all = store.conversations().filter { !it.kbLocked }
        if (all.isEmpty()) {
            _organize.value = OrganizeState(
                message = "没有需要整理的对话\n（手动指定过知识库的已经锁定，不会被改动）"
            )
            return
        }

        viewModelScope.launch {
            _organize.value = OrganizeState(running = true, total = all.size)

            val knownNames = store.kbs().map { it.name }.toMutableList()
            val lockedCount = store.conversations().count { it.kbLocked }
            var done = 0
            var assigned = 0
            var createdKbs = 0
            var failed = 0

            all.chunked(15).forEach { chunk ->
                val briefs = chunk.map { conv ->
                    val msgs = store.messages(conv.id)
                    // 长对话只看开头几轮会漏掉结论，取「头 4 条 + 尾 4 条」
                    val picked = if (msgs.size <= 8) msgs else (msgs.take(4) + msgs.takeLast(4))
                        .distinctBy { it.id }
                    val snippet = picked.joinToString("\n") { "${it.role}：${it.content.take(500)}" }
                    Archiver.SessionBrief(conv.id, conv.title, snippet)
                }
                val result = Archiver.organize(
                    settings.baseUrl, key, settings.model, knownNames, briefs
                )
                if (result == null) {
                    failed++
                } else {
                    result.forEach { a ->
                        store.rename(a.id, a.title)
                        if (a.summary.isNotBlank()) store.setSummary(a.id, a.summary)
                        if (a.kb.isNotBlank()) {
                            val existing = store.kbs().firstOrNull { it.name == a.kb }
                            val kb = if (existing != null) existing else {
                                createdKbs++
                                store.createKb(a.kb, a.kbWeight)
                            }
                            store.setConversationKb(a.id, kb.id)
                            if (!knownNames.contains(a.kb)) knownNames.add(a.kb)
                        }
                        assigned++
                    }
                }
                done += chunk.size
                _organize.update { it.copy(done = done) }
            }

            refreshKbs()
            refreshList()
            reload()

            val msgText = when {
                assigned == 0 -> "整理失败，检查一下网络或 API Key"
                failed > 0 -> "整理了 $assigned 条，有 $failed 批失败，新建 $createdKbs 个库"
                else -> "整理了 $assigned 条对话，新建 $createdKbs 个知识库" +
                    if (lockedCount > 0) "；跳过 $lockedCount 条手动指定的" else ""
            }
            _organize.value = OrganizeState(message = msgText)
        }
    }

    fun openConversation(id: String) {
        convId = id
        _chat.value = ChatState(thinkingOn = settings.thinking)
        reload()
    }

    /**
     * 开一个新的空白对话。注意这里**不落库**——
     * 只有真正发出第一条消息时才创建记录，否则每次点「新对话」都会
     * 在库里留一条空壳，删都删不干净。
     */
    fun newChat() {
        if (_chat.value.streaming) stop()
        convId = null
        _chat.value = ChatState(thinkingOn = settings.thinking)
    }

    fun newConversation(): String {
        if (_chat.value.streaming) stop()
        // 当前已经是一条空对话就直接复用，别在库里堆空记录
        val current = convId?.let { store.conversation(it) }
        if (current != null && store.messages(current.id).isEmpty()) {
            _chat.value = ChatState(thinkingOn = settings.thinking)
            reload()
            refreshList()
            return current.id
        }
        val c = store.createConversation()
        convId = c.id
        _chat.value = ChatState(thinkingOn = settings.thinking)
        reload()
        refreshList()
        return c.id
    }

    fun setThinking(on: Boolean) {
        _chat.update { it.copy(thinkingOn = on) }
    }

    fun setSearch(on: Boolean) {
        _chat.update { it.copy(searchOn = on) }
    }

    /** 总 AI 的全部实例（主实例排最前），给抽屉渲染用。 */
    private fun refreshMasters() {
        _masters.value = runCatching { store.masterInstances() }.getOrDefault(emptyList())
    }

    /** 进入总 AI。convId 为空时进主实例。 */
    fun openMaster(convId: String? = null) {
        if (_chat.value.streaming) stop()
        runCatching {
            val master = convId?.let { store.conversation(it) }
                ?: store.ensureMaster(settings.masterName)
            this.convId = master.id
            _chat.value = ChatState(thinkingOn = settings.thinking)
            reload()
            refreshList()
            refreshMasters()
        }.onFailure { e ->
            _notice.value = "打不开总 AI：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 新建一个空白的总 AI 实例（Demiurge (1)、(2)…），直接进去。 */
    fun newMasterVariant() {
        if (_chat.value.streaming) stop()
        runCatching {
            val n = store.masterInstances().size
            val conv = store.createMasterVariant("${settings.masterName} ($n)")
            this.convId = conv.id
            _chat.value = ChatState(thinkingOn = settings.thinking)
            reload()
            refreshList()
            refreshMasters()
        }.onFailure { e ->
            _notice.value = "新建失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 删除一个总 AI 实例。主实例不允许删。 */
    fun deleteMasterVariant(id: String) {
        val conv = store.conversation(id) ?: return
        if (conv.isPrimary) {
            _notice.value = "这个是主实例，删不了"
            return
        }
        store.deleteConversation(id)
        if (convId == id) {
            convId = null
            _chat.value = ChatState(thinkingOn = settings.thinking)
        }
        refreshList()
        refreshMasters()
    }

    /** 设置某个总 AI 实例的知识范围。空列表 = 全部知识库。 */
    fun setMasterScope(convId: String, kbIds: List<String>) {
        store.setKbScope(convId, kbIds)
        reload()
        refreshList()
        refreshMasters()
        _notice.value = if (kbIds.isEmpty()) {
            "已放开到全部知识库"
        } else {
            "已限定 ${kbIds.size} 个知识库"
        }
    }

    /** 进入某个知识库的助手：同一套机制，作用域收到这一个库。 */
    fun openKbMaster(kbId: String) {
        if (_chat.value.streaming) stop()
        val kb = store.kbs().firstOrNull { it.id == kbId } ?: return
        val master = store.ensureMaster("${kb.name} · 知识库助手", kbId)
        convId = master.id
        _chat.value = ChatState(thinkingOn = settings.thinking)
        reload()
        refreshList()
    }

    /** 读某个知识库助手自定义的人格；空则用默认人设。 */
    fun kbAssistantPrompt(kbId: String): String =
        runCatching { store.kbAssistant(kbId)?.systemPrompt.orEmpty() }.getOrDefault("")

    fun setKbAssistantPrompt(kbId: String, prompt: String) {
        runCatching {
            val kb = store.kbs().firstOrNull { it.id == kbId } ?: return
            val conv = store.ensureMaster("${kb.name} · 知识库助手", kbId)
            store.setSystemPrompt(conv.id, prompt)
            refreshList()
            _notice.value = if (prompt.isBlank()) "已恢复默认人格" else "人格已保存"
        }.onFailure { e ->
            _notice.value = "保存失败：${e.message}"
        }
    }

    // ---------------- 用户档案 ----------------

    fun profileLines(): List<String> =
        store.profileText().lines().map { it.trim() }.filter { it.isNotBlank() }

    /** 删掉档案里的某一条（加入错了想撤掉）。 */
    fun removeProfileLine(line: String) {
        val rest = profileLines().filter { it != line }
        store.setProfileText(rest.joinToString("\n"))
        refreshList()
    }

    fun addProfileLine(line: String) {
        val text = line.trim()
        if (text.isBlank()) return
        val existing = profileLines()
        if (existing.any { it == text }) return
        store.setProfileText((existing + text).joinToString("\n").take(4000))
        refreshList()
    }

    /**
     * 从这句话里抽出可以拿去检索的词。
     * 中文没空格，简单做法：按标点和空白切开，太长的再滑窗切成 4 字。
     */
    private fun extractTerms(question: String): List<String> {
        val parts = question.split(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9]+"))
        val out = ArrayList<String>()
        parts.forEach { p ->
            when {
                p.length in 2..8 -> out.add(p)
                p.length > 8 -> {
                    var i = 0
                    while (i + 4 <= p.length) {
                        out.add(p.substring(i, i + 4))
                        i += 2
                    }
                }
            }
        }
        return out.distinct().take(6)
    }

    /** 把检索到的原文拼成给模型看的一段。kbId 非空时只在这个库里翻。 */
    private fun relevantExcerpts(question: String, kbId: String? = null): String {
        val terms = extractTerms(question)
        if (terms.isEmpty()) return ""
        val hits = store.searchMessages(terms, kbId = kbId)
        if (hits.isEmpty()) return ""
        return hits.joinToString("\n\n") { m ->
            val who = if (m.role == "user") "他当时说" else "你当时答"
            "$who：${m.content.trim().take(350)}"
        }
    }

    /**
     * Demiurge 的系统提示词：人设 + 记忆。
     * kbId 为空是总 AI（全部对话）；给了 kbId 就是那个知识库的分身，只看本库。
     *
     * 整段用 try 包着：万一某个记忆表读不出来（老版本升级残留、数据异常），
     * 也要退化成「只有人设」的普通对话，而不是整个总 AI 用不了。
     */
    private fun masterSystemPrompt(
        question: String,
        kbId: String? = null,
        customPersona: String? = null,
        scope: List<String> = emptyList()
    ): String {
        return try {
            buildMasterPrompt(question, kbId, customPersona, scope)
        } catch (e: Exception) {
            // 把异常类型和消息都写出来，别再让用户只看到「出了点问题」
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "无消息"}"
            recordDiag("总AI记忆读取失败 master=${kbId == null} $detail")
            Persona.DEFAULT + "\n\n（读取记忆出错：$detail。先用普通对话方式回答，把上面这个错误告诉我。）"
        }
    }

    private fun buildMasterPrompt(
        question: String,
        kbId: String? = null,
        customPersona: String? = null,
        scope: List<String> = emptyList()
    ): String {
        val allConversations = store.conversations()
        val allKbs = store.kbs()
        val scopeKb = kbId?.let { id -> allKbs.firstOrNull { it.id == id } }
        // 知识库助手：只看这一个库
        // 小德缪歌：只看它订阅的那几个库（没订阅就是全部）
        val all = when {
            kbId != null -> allConversations.filter { it.kbId == kbId }
            scope.isNotEmpty() -> allConversations.filter { it.kbId in scope }
            else -> allConversations
        }
        val kbs = if (scope.isNotEmpty()) allKbs.filter { it.id in scope } else allKbs
        val sb = StringBuilder()
        // 知识库助手可以自带人格；没设就用默认人设
        val persona = customPersona?.takeIf { it.isNotBlank() } ?: Persona.DEFAULT
        sb.append(persona)

        if (scopeKb != null) {
            sb.append("\n\n## 你的特殊身份\n")
            sb.append("你是「${scopeKb.name}」这个知识库的助手。")
            sb.append("你认识他本人，但你只了解这个领域里的事，别的话题你不清楚。\n")
            sb.append("下面是这个库里你们聊过的全部内容。\n")
        } else {
            sb.append("\n\n## 你的特殊身份\n")
            sb.append("你是用户的老朋友，也是唯一一个知道他所有事情的人。")
            sb.append("下面是他这些日子跟你聊过的全部内容整理成的档案。\n")
            if (scope.isNotEmpty()) {
                val names = allKbs.filter { it.id in scope }.joinToString("、") { it.name }
                sb.append("你只关心这几个领域：$names。别的话题你不了解，也不要假装知道。\n")
            }
        }
        sb.append("- 回答时自然用这些记忆。可以说「你上次提过……」，但别机械念档案，也别说「根据我的记录」。\n")
        sb.append("- 档案里没有的细节，就说你不记得，不要编。\n")

        sb.append("\n## 关于他本人（确定的事实，最优先）\n")
        val profile = store.profileText()
        if (profile.isBlank()) {
            sb.append("（还没建立档案。可以在某条对话上长按「加入用户档案」）\n")
        } else {
            sb.append(profile).append('\n')
        }

        // 总 AI 自己那些被压缩掉的长谈。权重比任何知识库都高。
        val memories = store.masterMemories()
        if (memories.isNotEmpty()) {
            sb.append("\n## 你和他更早的那些长谈（你自己记下的，最不该忘）\n")
            memories.forEach { m -> sb.append("- ").append(m.text).append('\n') }
        }

        if (scopeKb != null) {
            // 知识库助手只有一个库，权重没有意义，全部给完整摘要
            sb.append("\n## 这个库里聊过的事\n")
            all.filter { !it.summary.isNullOrBlank() }.forEach { c ->
                sb.append("- ${c.title}：${c.summary}\n")
            }
        } else {
            sb.append("\n## 你和他之间聊过的事\n")
            sb.append("（标注的权重越高，那件事对他越重要，越该主动想起来）\n")

            // 按记忆权重从高到低排，低权重的只留标题
            kbs.sortedByDescending { it.weight }.forEach { kb ->
                val list = all.filter { it.kbId == kb.id && !it.summary.isNullOrBlank() }
                if (list.isNotEmpty()) {
                    sb.append("\n### ${kb.name}（权重大约 ${kb.weight}/100）\n")
                    list.forEach { c ->
                        val summary = c.summary.orEmpty()
                        val line = when {
                            kb.weight >= 60 -> "- ${c.title}：$summary"
                            kb.weight >= 30 -> "- ${c.title}：${summary.take(40)}…"
                            else -> "- ${c.title}"
                        }
                        sb.append(line).append('\n')
                    }
                }
            }
            val unclassified = all.filter { it.kbId == null && !it.summary.isNullOrBlank() }
            if (unclassified.isNotEmpty()) {
                sb.append("\n### 未归类\n")
                unclassified.forEach { sb.append("- ${it.title}：${it.summary}\n") }
            }
        }
        val missing = all.count { it.summary.isNullOrBlank() }
        if (missing > 0) {
            sb.append("\n（另有 $missing 条对话还没有摘要，你可以问他要不要整理一下）\n")
        }
        if (all.isEmpty()) {
            sb.append("\n（这个库里还没有整理过的对话）\n")
        }

        // L3：翻原文。问的事如果档案里没有细节，这里能捞出当时的原话。
        val excerpts = relevantExcerpts(question, kbId)
        if (excerpts.isNotBlank()) {
            sb.append("\n## 你刚翻出来的原文（和这次问的有关）\n")
            sb.append("这些是从原始记录里翻到的，可以直接引用原话。\n\n")
            sb.append(excerpts).append('\n')
        }
        return sb.toString()
    }

    fun reload() {
        val id = convId ?: return
        val conv = store.conversation(id)
        val all = store.messages(id)
        val branches = HashMap<String, BranchInfo>()
        all.groupBy { (it.parentId ?: "") + "|" + it.role }.forEach { (_, group) ->
            if (group.size > 1) {
                val sorted = group.sortedBy { it.createdAt }
                sorted.forEachIndexed { index, m ->
                    branches[m.id] = BranchInfo(
                        index = index + 1,
                        count = sorted.size,
                        prevId = sorted.getOrNull(index - 1)?.id,
                        nextId = sorted.getOrNull(index + 1)?.id
                    )
                }
            }
        }
        _chat.update {
            it.copy(conv = conv, messages = store.path(id), branches = branches)
        }
    }

    /**
     * 第一轮问答结束后，悄悄让归档助手起个标题、归个库。
     * 只在恰好「一问一答」时触发，避免每轮都花钱；失败就当没发生。
     */
    private fun autoTitle(convId: String) {
        if (_chat.value.streaming) return
        // 总 AI 那条会话不需要标题和归类
        if (store.conversation(convId)?.isMaster == true) return
        val msgs = store.messages(convId)
        if (msgs.size != 2) return
        val key = settings.apiKey
        if (key.isBlank()) return

        val snippet = msgs.joinToString("\n\n") { m ->
            val who = if (m.role == "user") "用户" else "助手"
            "$who：${m.content.take(800)}"
        }

        viewModelScope.launch {
            val result = Archiver.suggest(settings.baseUrl, key, settings.model, snippet) ?: return@launch
            val (title, kbName) = result
            store.rename(convId, title)
            if (kbName.isNotBlank()) {
                val existing = store.kbs().firstOrNull { it.name == kbName }
                val kb = existing ?: runCatching { store.createKb(kbName) }.getOrNull()
                if (kb != null) store.setConversationKb(convId, kb.id)
            }
            refreshKbs()
            refreshList()
            reload()
        }
    }

    /** 树视图要一次拿到整棵树，而不只是当前路径。 */
    fun allMessages(convId: String): List<Message> = store.messages(convId)

    /** 切到某个分支，并落到那条分支的最深处。 */
    fun switchBranch(messageId: String) {
        val id = convId ?: return
        if (_chat.value.streaming) return
        val leaf = store.deepestLeaf(id, messageId)
        store.setActiveLeaf(id, leaf)
        reload()
    }

    fun addPendingImage(path: String) {
        _chat.update { it.copy(pendingImages = it.pendingImages + path) }
    }

    fun removePendingImage(path: String) {
        _chat.update { it.copy(pendingImages = it.pendingImages - path) }
    }

    /** 上传文档交给服务端解析。卡片立即出现，状态随上传进度变化。 */
    fun uploadDocument(uri: android.net.Uri, name: String) {
        val key = settings.apiKey
        if (key.isBlank()) {
            _notice.value = "先去设置里填 API Key"
            return
        }
        // 先占位成「上传中」，成功后换成 ok|file_id|文件名
        val token = UUID.randomUUID().toString()
        val placeholder = "up|$token|$name"
        _chat.update { it.copy(pendingDocs = it.pendingDocs + placeholder) }

        viewModelScope.launch {
            val result = FileUploader.upload(
                getApplication(),
                uri,
                name,
                FileUploader.purposeFor(name),
                settings.baseUrl,
                key
            )
            val replacement = result.fold(
                onSuccess = { "ok|$it|$name" },
                onFailure = { "err|$token|$name" }
            )
            _chat.update { state ->
                state.copy(
                    pendingDocs = state.pendingDocs.map {
                        if (it == placeholder) replacement else it
                    }
                )
            }
            result.onFailure {
                _notice.value = "${name}：${it.message ?: "解析失败"}"
            }
        }
    }

    fun removePendingDoc(entry: String) {
        _chat.update { it.copy(pendingDocs = it.pendingDocs - entry) }
    }

    fun send(text: String) {
        val body = text.trim()
        // 从 ViewModel 自己的状态里取，别信任调用方传进来的快照
        val images = _chat.value.pendingImages
        // 只发已经解析完成的；上传中和失败的留着，等用户处理
        val docs = _chat.value.pendingDocs
            .filter { it.startsWith("ok|") }
            .map { it.removePrefix("ok|") }
        val attachments = images + docs
        if (body.isEmpty() && attachments.isEmpty()) return
        if (_chat.value.streaming) return
        val id = convId ?: newConversation()
        val conv = store.conversation(id) ?: return

        val userMsg = Message(
            id = UUID.randomUUID().toString(),
            convId = id,
            parentId = conv.activeLeafId,
            role = "user",
            content = body,
            attachments = attachments
        )
        store.insertMessage(userMsg)
        store.setActiveLeaf(id, userMsg.id)
        if (conv.title.isBlank() || conv.title == "新对话") {
            store.rename(id, body.take(24).ifBlank { "附件" })
        }
        _chat.update { it.copy(pendingImages = emptyList(), pendingDocs = emptyList()) }
        reload()
        startAssistant(id, userMsg.id)
    }

    /** 重新生成：同一个 parent 下再挂一个 assistant，老的留在另一个分支里。 */
    fun regenerate(target: Message) {
        val id = convId ?: return
        if (_chat.value.streaming || target.role != "assistant") return
        startAssistant(id, target.parentId)
    }

    /** 编辑输入：从这条用户消息的父节点处分叉，旧分支完全保留。 */
    fun editUser(target: Message, newText: String) {
        val id = convId ?: return
        val body = newText.trim()
        // 编辑也要把附件带上：图片可能没改，文档可能是新加的
        val images = _chat.value.pendingImages
        val docs = _chat.value.pendingDocs
            .filter { it.startsWith("ok|") }
            .map { it.removePrefix("ok|") }
        val attachments = images + docs
        if (body.isEmpty() && attachments.isEmpty()) return
        if (_chat.value.streaming) return
        val userMsg = Message(
            id = UUID.randomUUID().toString(),
            convId = id,
            parentId = target.parentId,
            role = "user",
            content = body,
            attachments = attachments
        )
        store.insertMessage(userMsg)
        store.setActiveLeaf(id, userMsg.id)
        _chat.update { it.copy(pendingImages = emptyList(), pendingDocs = emptyList()) }
        reload()
        startAssistant(id, userMsg.id)
    }

    /** 清空待发送附件（进入/退出编辑时用）。 */
    fun clearPendingAttachments() {
        _chat.update { it.copy(pendingImages = emptyList(), pendingDocs = emptyList()) }
    }

    /** 把已有文档放回待发送区，entry 形如 ok|file_id|文件名。 */
    fun addPendingDoc(entry: String) {
        _chat.update { it.copy(pendingDocs = it.pendingDocs + entry) }
    }

    fun stop() {
        job?.cancel()
        job = null
        val st = _chat.value
        val liveId = st.liveId
        if (liveId != null) {
            store.updateMessage(
                liveId,
                content = st.liveText,
                thinking = st.liveThinking,
                status = "done"
            )
        }
        _chat.update {
            it.copy(
                streaming = false,
                liveId = null,
                liveText = "",
                liveThinking = ""
            )
        }
        reload()
        refreshList()
    }

    fun dismissError() {
        _chat.update { it.copy(error = null) }
    }

    fun deleteConversation(id: String) {
        store.deleteConversation(id)
        // 顺手把这条会话的图片文件也删掉，不然磁盘会一直涨
        runCatching {
            File(getApplication<Application>().filesDir, "attachments/$id").deleteRecursively()
        }
        if (convId == id) {
            convId = null
            _chat.value = ChatState(thinkingOn = settings.thinking)
        }
        refreshList()
    }

    fun renameConversation(id: String, title: String) {
        val t = title.trim()
        if (t.isNotEmpty()) store.rename(id, t)
        refreshList()
        reload()
    }

    fun setPinned(id: String, pinned: Boolean) {
        store.setPinned(id, pinned)
        refreshList()
    }

    /** 把抓回来的分享内容落成一条会话，按顺序串成一条主链。 */
    fun saveImported(preview: SharePreview): String {
        val conv = store.createConversation(preview.title)
        var parent: String? = null
        val base = System.currentTimeMillis() - preview.turns.size * 1000L
        val pending = ArrayList<Triple<String, List<String>, String>>()

        preview.turns.forEachIndexed { index, turn ->
            val id = UUID.randomUUID().toString()
            store.insertMessage(
                Message(
                    id = id,
                    convId = conv.id,
                    parentId = parent,
                    role = turn.role,
                    content = turn.content,
                    thinking = turn.thinking,
                    status = "done",
                    model = "imported",
                    createdAt = base + index * 1000L
                )
            )
            if (turn.imageUrls.isNotEmpty()) {
                pending.add(Triple(id, turn.imageUrls, conv.id))
            }
            parent = id
        }
        store.setActiveLeaf(conv.id, parent)
        refreshList()

        // 图片在后台下载，下载完再回填，导入本身不卡
        if (pending.isNotEmpty()) {
            viewModelScope.launch {
                pending.forEach { (msgId, urls, convId) ->
                    val local = urls.mapNotNull { downloadAttachment(convId, it) }
                    if (local.isNotEmpty()) store.updateMessageAttachments(msgId, local)
                }
                reload()
                refreshList()
            }
        }
        return conv.id
    }

    private fun toDataUrl(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() > 4 * 1024 * 1024) return null
            val bytes = file.readBytes()
            val mime = when {
                path.endsWith(".png", true) -> "image/png"
                path.endsWith(".webp", true) -> "image/webp"
                else -> "image/jpeg"
            }
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun downloadAttachment(convId: String, url: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(getApplication<Application>().filesDir, "attachments/$convId")
                if (!dir.exists() && !dir.mkdirs()) return@withContext null
                val name = Integer.toHexString(url.hashCode()) + ".img"
                val file = File(dir, name)
                if (file.exists() && file.length() > 0) return@withContext file.absolutePath

                val request = Request.Builder()
                    .url(url)
                    .header("Referer", "https://chat.deepseek.com/")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                    )
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    file.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                if (file.length() > 0) file.absolutePath else null
            } catch (e: Exception) {
                null
            }
        }

    private fun startAssistant(id: String, parentId: String?) {
        val conv = store.conversation(id) ?: return

        val placeholder = Message(
            id = UUID.randomUUID().toString(),
            convId = id,
            parentId = parentId,
            role = "assistant",
            content = "",
            status = "streaming",
            model = settings.model
        )
        store.insertMessage(placeholder)
        store.setActiveLeaf(id, placeholder.id)

        val history = store.path(id).filter { it.id != placeholder.id }
        val basePrompt = conv.systemPrompt?.takeIf { it.isNotBlank() } ?: settings.systemPrompt
        val question = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val systemPrompt = if (conv.isMaster) {
            masterSystemPrompt(question, conv.kbId, conv.systemPrompt, conv.kbScope)
        } else {
            val style = if (settings.thinkingStyle) "\n\n" + Persona.THINKING_STYLE else ""
            // 压缩过的对话：把之前的记忆接在提示词里，不然它会「断片」
            val memory = conv.summary?.takeIf { it.isNotBlank() }
                ?.let { "\n\n## 这段对话之前聊过（已经压缩成摘要，细节可能不全）\n$it" }
                .orEmpty()
            basePrompt + style + memory
        }
        // 读图 + base64 编码放到协程里做，不然发消息会卡一下
        val apiMessages = buildList {
            if (systemPrompt.isNotBlank()) add(ApiMessage("system", systemPrompt))
            history.forEach { m ->
                // 只有 user 消息能带附件；assistant 带图接口会 400
                val localImages = if (m.role == "user") {
                    m.attachments.filter { it.startsWith("/") }
                } else emptyList()
                val imgs = localImages.mapNotNull { toDataUrl(it) }
                val files = if (m.role == "user") {
                    m.attachments.filterNot { it.startsWith("/") }.map { it.substringBefore("|") }
                } else emptyList()

                if (m.content.isNotBlank() || imgs.isNotEmpty() || files.isNotEmpty()) {
                    add(ApiMessage(m.role, m.content, imgs, files))
                }
            }
        }

        val api = ChatApi(settings.baseUrl, settings.apiKey)
        val model = settings.model
        val thinking = _chat.value.thinkingOn
        val searchOn = _chat.value.searchOn
        val effort = settings.effort
        val temperature = settings.temperature
        val textBuf = StringBuilder()
        val thinkBuf = StringBuilder()

        job?.cancel()
        job = viewModelScope.launch {
            _chat.update {
                it.copy(
                    streaming = true,
                    liveId = placeholder.id,
                    liveText = "",
                    liveThinking = "",
                    error = null,
                    searchHint = null,
                    conv = conv,
                    messages = store.path(id)
                )
            }

            var failure: String? = null
            var cancelled = false
            var lastUiAt = 0L
            var citations: List<Pair<String, String>> = emptyList()
            recordDiag(
                "请求 master=${conv.isMaster} model=$model msgs=${apiMessages.size} " +
                    "promptLen=${systemPrompt.length} thinking=$thinking effort=$effort"
            )
            try {
                api.stream(
                    model, apiMessages, thinking, effort, temperature,
                    webSearch = searchOn, webRead = searchOn
                ).collect { ev ->
                    when (ev) {
                        is StreamEvent.Searching -> {
                            val detail = ev.detail.take(40)
                            _chat.update {
                                it.copy(
                                    searchHint = if (detail.isBlank()) "正在联网…"
                                    else "正在搜：$detail"
                                )
                            }
                        }

                        is StreamEvent.Reasoning -> {
                            thinkBuf.append(ev.text)
                            val now = System.currentTimeMillis()
                            if (now - lastUiAt > 90) {
                                lastUiAt = now
                                _chat.update { it.copy(liveThinking = thinkBuf.toString()) }
                            }
                        }

                        is StreamEvent.Delta -> {
                            textBuf.append(ev.text)
                            val now = System.currentTimeMillis()
                            if (now - lastUiAt > 90) {
                                lastUiAt = now
                                _chat.update { it.copy(liveText = textBuf.toString()) }
                            }
                        }

                        is StreamEvent.Citations -> citations = ev.items
                        is StreamEvent.Failed -> failure = ev.message
                        StreamEvent.Done -> Unit
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了停止，或者被新的请求取代。这不是错误，别再弹「出错了」。
                cancelled = true
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                recordDiag("异常 ${e.javaClass.simpleName}: ${e.message}")
            }
            failure?.let { recordDiag("失败: $it  收到字符=${textBuf.length}") }

            val content = textBuf.toString()
            val reasoning = thinkBuf.toString()
            val fail = failure
            if (citations.isNotEmpty()) {
                store.updateMessageCitations(
                    placeholder.id,
                    citations.map { com.zhiwo.app.data.Citation(it.first, it.second) }
                )
            }
            if (fail != null) {
                store.updateMessage(placeholder.id, content, reasoning, "error", fail)
                _chat.update {
                    it.copy(streaming = false, liveId = null, error = fail, searchHint = null)
                }
                // 错误栏有时候被键盘挡住，再弹一次，别让人以为「没反应」
                _notice.value = fail
            } else {
                // 正常结束和被取消都走这里：有内容就存下来，空的标成「已停止」
                store.updateMessage(
                    placeholder.id,
                    content.ifBlank { if (cancelled) "（已停止）" else "（没有内容）" },
                    reasoning,
                    "done",
                    null
                )
                _chat.update {
                    it.copy(
                        streaming = false,
                        liveId = null,
                        liveText = "",
                        liveThinking = "",
                        searchHint = null
                    )
                }
            }
            reload()
            refreshList()
            if (fail == null) autoTitle(id)
            if (conv.isMaster) maybeCompressMaster(id)
            job = null
        }
    }

    /**
     * 总 AI 自己的对话到 40 条以上时，把前半段压成一段记忆再删掉原文。
     * 这样它能一直聊下去，而记忆不会无限膨胀。
     */
    private fun maybeCompressMaster(convId: String) {
        val key = settings.apiKey
        if (key.isBlank()) return
        val msgs = store.messages(convId)
        if (msgs.size < 41) return

        val cut = msgs.size / 2
        val older = msgs.take(cut)
        val boundary = msgs[cut]

        viewModelScope.launch {
            val content = older.joinToString("\n\n") { m ->
                val who = if (m.role == "user") "他" else "我"
                "$who：${m.content.take(800)}"
            }
            val memory = Archiver.compressMaster(
                settings.baseUrl, key, settings.model, content
            ) ?: return@launch
            store.addMasterMemory(memory, weight = 75)
            store.compressBefore(convId, boundary.id)
            reload()
            _notice.value = "把前面聊的压成了一段记忆，它以后还记得"
        }
    }
}

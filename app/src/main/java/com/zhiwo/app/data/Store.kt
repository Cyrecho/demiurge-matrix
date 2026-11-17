package com.zhiwo.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhiwo.app.net.DeepSeekZipImporter
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地存储。整棵树都存在 message.parent_id 里：
 * 重新生成 = 同一个 parent 下多一个兄弟节点；编辑输入 = 从那条消息处分叉。
 */
class Store private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "zhiwo.db", null, 12) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE conversation(" +
                    "id TEXT PRIMARY KEY," +
                    "title TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "active_leaf_id TEXT," +
                    "system_prompt TEXT," +
                    "pinned INTEGER NOT NULL DEFAULT 0," +
                    "kb_id TEXT," +
                    "kb_locked INTEGER NOT NULL DEFAULT 0," +
                    "summary TEXT," +
                    "is_master INTEGER NOT NULL DEFAULT 0," +
                    "is_primary INTEGER NOT NULL DEFAULT 0," +
                    "kb_scope TEXT)"
            )
            db.execSQL(
                "CREATE TABLE message(" +
                    "id TEXT PRIMARY KEY," +
                    "conv_id TEXT NOT NULL," +
                    "parent_id TEXT," +
                    "role TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "thinking TEXT NOT NULL DEFAULT ''," +
                    "status TEXT NOT NULL DEFAULT 'done'," +
                    "model TEXT NOT NULL DEFAULT ''," +
                    "created_at INTEGER NOT NULL," +
                    "error TEXT," +
                    "attachments TEXT," +
                    "citations TEXT)"
            )
            db.execSQL(
                "CREATE TABLE kb(" +
                    "id TEXT PRIMARY KEY," +
                    "name TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "sort INTEGER NOT NULL DEFAULT 0," +
                    "weight INTEGER NOT NULL DEFAULT 50)"
            )
            db.execSQL(
                "CREATE TABLE profile(" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                    "text TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL)"
            )
            // 全新安装也必须建这张表。
            // 之前只在 onUpgrade 的 v8 分支里建，导致新装用户一进总 AI 就崩。
            db.execSQL(
                "CREATE TABLE master_memory(" +
                    "id TEXT PRIMARY KEY," +
                    "text TEXT NOT NULL," +
                    "weight INTEGER NOT NULL DEFAULT 75," +
                    "created_at INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX idx_msg_conv ON message(conv_id)")
            db.execSQL("CREATE INDEX idx_msg_parent ON message(parent_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 -> v2：加知识库。绝不能 DROP，用户已经有真实对话了。
            if (oldVersion < 2) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS kb(" +
                        "id TEXT PRIMARY KEY," +
                        "name TEXT NOT NULL," +
                        "created_at INTEGER NOT NULL," +
                        "sort INTEGER NOT NULL DEFAULT 0," +
                        "weight INTEGER NOT NULL DEFAULT 50)"
                )
                db.execSQL("ALTER TABLE conversation ADD COLUMN kb_id TEXT")
            }
            // v2 -> v3：消息带附件（图片）
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE message ADD COLUMN attachments TEXT")
            }
            // v3 -> v4：手动指定过知识库的会话加锁，一键整理不再动它
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN kb_locked INTEGER NOT NULL DEFAULT 0")
            }
            // v4 -> v5：会话摘要 + 总 AI 标记
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN summary TEXT")
                db.execSQL("ALTER TABLE conversation ADD COLUMN is_master INTEGER NOT NULL DEFAULT 0")
            }
            // v5 -> v6：知识库记忆权重
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE kb ADD COLUMN weight INTEGER NOT NULL DEFAULT 50")
            }
            // v6 -> v7：用户档案
            if (oldVersion < 7) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN in_profile INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profile(" +
                        "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                        "text TEXT NOT NULL," +
                        "updated_at INTEGER NOT NULL)"
                )
            }
            // v7 -> v8：总 AI 自己的对话压缩后留下的记忆
            if (oldVersion < 8) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS master_memory(" +
                        "id TEXT PRIMARY KEY," +
                        "text TEXT NOT NULL," +
                        "weight INTEGER NOT NULL DEFAULT 75," +
                        "created_at INTEGER NOT NULL)"
                )
            }
            // v8 -> v9：联网搜索的引用来源
            if (oldVersion < 9) {
                db.execSQL("ALTER TABLE message ADD COLUMN citations TEXT")
            }
            // v9 -> v10：补齐 master_memory。
            // 从 v9 上来的用户里，有一部分是全新安装的（onCreate 漏建了这张表），
            // 这一步把他们的库补全。
            if (oldVersion < 10) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS master_memory(" +
                        "id TEXT PRIMARY KEY," +
                        "text TEXT NOT NULL," +
                        "weight INTEGER NOT NULL DEFAULT 75," +
                        "created_at INTEGER NOT NULL)"
                )
            }
            // v10 -> v11：总 AI 支持多实例，标记出主实例
            if (oldVersion < 11) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN is_primary INTEGER NOT NULL DEFAULT 0")
                // 把现存最早的那条总 AI 提为主实例
                db.execSQL(
                    "UPDATE conversation SET is_primary = 1 WHERE id = (" +
                        "SELECT id FROM conversation WHERE is_master = 1 AND kb_id IS NULL " +
                        "ORDER BY created_at ASC LIMIT 1)"
                )
            }
            // v11 -> v12：总 AI 实例可以限定只关心哪几个知识库
            if (oldVersion < 12) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN kb_scope TEXT")
            }
        }
    }

    // ---------------- 知识库 ----------------

    fun kbs(): List<Kb> {
        val out = ArrayList<Kb>()
        helper.readableDatabase
            .rawQuery("SELECT * FROM kb ORDER BY sort ASC, created_at ASC", null)
            .use { c ->
                while (c.moveToNext()) {
                    val weightIndex = c.getColumnIndex("weight")
                    out.add(
                        Kb(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                            sort = c.getInt(c.getColumnIndexOrThrow("sort")),
                            weight = if (weightIndex >= 0) c.getInt(weightIndex) else 50
                        )
                    )
                }
            }
        return out
    }

    fun createKb(name: String, weight: Int = 50): Kb {
        val kb = Kb(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAt = System.currentTimeMillis(),
            sort = kbs().size,
            weight = weight.coerceIn(0, 100)
        )
        val cv = ContentValues().apply {
            put("id", kb.id)
            put("name", kb.name)
            put("created_at", kb.createdAt)
            put("sort", kb.sort)
            put("weight", kb.weight)
        }
        helper.writableDatabase.insert("kb", null, cv)
        return kb
    }

    fun setKbWeight(id: String, weight: Int) {
        val cv = ContentValues().apply { put("weight", weight.coerceIn(0, 100)) }
        helper.writableDatabase.update("kb", cv, "id = ?", arrayOf(id))
    }

    fun renameKb(id: String, name: String) {
        val cv = ContentValues().apply { put("name", name.trim()) }
        helper.writableDatabase.update("kb", cv, "id = ?", arrayOf(id))
    }

    fun deleteKb(id: String) {
        val db = helper.writableDatabase
        val cv = ContentValues().apply { putNull("kb_id") }
        db.update("conversation", cv, "kb_id = ?", arrayOf(id))
        db.delete("kb", "id = ?", arrayOf(id))
    }

    fun setConversationKb(convId: String, kbId: String?, manual: Boolean = false) {
        val cv = ContentValues().apply {
            if (kbId == null) putNull("kb_id") else put("kb_id", kbId)
            if (manual) put("kb_locked", 1)
        }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(convId))
    }

    fun clearKbLock(convId: String) {
        val cv = ContentValues().apply { put("kb_locked", 0) }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(convId))
    }

    // ---------------- 会话 ----------------

    fun conversations(): List<Conversation> {
        val out = ArrayList<Conversation>()
        helper.readableDatabase
            .rawQuery(
                "SELECT * FROM conversation WHERE is_master = 0 " +
                    "ORDER BY pinned DESC, updated_at DESC",
                null
            )
            .use { c -> while (c.moveToNext()) out.add(c.toConversation()) }
        return out
    }

    /** 恢复备份前要先放开数据库连接，不然覆盖文件会冲突。 */
    fun close() {
        runCatching { helper.close() }
    }

    /** kbId 为 null 时找总 AI 的主实例；否则找那个知识库的助手。 */
    private fun masterRow(kbId: String?): Conversation? {
        var result: Conversation? = null
        if (kbId == null) {
            helper.readableDatabase
                .rawQuery(
                    "SELECT * FROM conversation WHERE is_master = 1 AND kb_id IS NULL " +
                        "ORDER BY is_primary DESC, created_at ASC LIMIT 1",
                    null
                )
                .use { c -> if (c.moveToFirst()) result = c.toConversation() }
        } else {
            helper.readableDatabase
                .rawQuery(
                    "SELECT * FROM conversation WHERE is_master = 1 AND kb_id = ? LIMIT 1",
                    arrayOf(kbId)
                )
                .use { c -> if (c.moveToFirst()) result = c.toConversation() }
        }
        return result
    }

    fun conversation(id: String): Conversation? {
        helper.readableDatabase
            .rawQuery("SELECT * FROM conversation WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) return c.toConversation() }
        return null
    }

    fun createConversation(title: String = "新对话", systemPrompt: String? = null, kbId: String? = null): Conversation {
        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            activeLeafId = null,
            systemPrompt = systemPrompt,
            pinned = false,
            kbId = kbId
        )
        val cv = ContentValues().apply {
            put("id", conv.id)
            put("title", conv.title)
            put("created_at", conv.createdAt)
            put("updated_at", conv.updatedAt)
            put("system_prompt", conv.systemPrompt)
            put("pinned", 0)
            if (kbId == null) putNull("kb_id") else put("kb_id", kbId)
        }
        helper.writableDatabase.insert("conversation", null, cv)
        return conv
    }

    fun rename(id: String, title: String) {
        val cv = ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(id))
    }

    fun setPinned(id: String, pinned: Boolean) {
        val cv = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(id))
    }

    fun deleteConversation(id: String) {
        val db = helper.writableDatabase
        db.delete("message", "conv_id = ?", arrayOf(id))
        db.delete("conversation", "id = ?", arrayOf(id))
    }

    fun setActiveLeaf(convId: String, leafId: String?) {        val cv = ContentValues().apply {
            put("active_leaf_id", leafId)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(convId))
    }

    // ---------------- 消息 ----------------

    fun messages(convId: String): List<Message> {
        val out = ArrayList<Message>()
        helper.readableDatabase
            .rawQuery(
                "SELECT * FROM message WHERE conv_id = ? ORDER BY created_at ASC",
                arrayOf(convId)
            )
            .use { c -> while (c.moveToNext()) out.add(c.toMessage()) }
        return out
    }

    /** 从 active_leaf 沿着 parent 一路回到根，得到“当前这条分支”的完整对话。 */
    fun path(convId: String): List<Message> {
        val conv = conversation(convId) ?: return emptyList()
        val all = messages(convId).associateBy { it.id }
        var cur: Message? = conv.activeLeafId?.let { all[it] }
        val reversed = ArrayList<Message>()
        var guard = 0
        while (cur != null && guard++ < 20000) {
            reversed.add(cur)
            val pid = cur.parentId
            cur = if (pid == null) null else all[pid]
        }
        reversed.reverse()
        return reversed
    }

    /**
     * 从某个节点往下走到最深的叶子（同层取最新的）。
     * 切换分支时用它决定 active_leaf，切过去就能看到那条分支的完整后续。
     */
    fun deepestLeaf(convId: String, nodeId: String): String {
        val byParent = messages(convId).groupBy { it.parentId ?: "" }
        var cur = nodeId
        var guard = 0
        while (guard++ < 20000) {
            val children = byParent[cur]
            if (children.isNullOrEmpty()) break
            cur = children.maxByOrNull { it.createdAt }!!.id
        }
        return cur
    }

    fun insertMessage(m: Message) {        val cv = ContentValues().apply {
            put("id", m.id)
            put("conv_id", m.convId)
            put("parent_id", m.parentId)
            put("role", m.role)
            put("content", m.content)
            put("thinking", m.thinking)
            put("status", m.status)
            put("model", m.model)
            put("created_at", m.createdAt)
            put("error", m.error)
            put("attachments", encodeAttachments(m.attachments))
            put("citations", encodeCitations(m.citations))
        }
        helper.writableDatabase.insertWithOnConflict(
            "message", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun updateMessage(
        id: String,
        content: String? = null,
        thinking: String? = null,
        status: String? = null,
        error: String? = null
    ) {
        val cv = ContentValues()
        if (content != null) cv.put("content", content)
        if (thinking != null) cv.put("thinking", thinking)
        if (status != null) cv.put("status", status)
        if (error != null) cv.put("error", error)
        if (cv.size() == 0) return
        helper.writableDatabase.update("message", cv, "id = ?", arrayOf(id))
    }

    fun updateMessageAttachments(id: String, attachments: List<String>) {
        val cv = ContentValues().apply { put("attachments", encodeAttachments(attachments)) }
        helper.writableDatabase.update("message", cv, "id = ?", arrayOf(id))
    }

    // ---------------- 官方导出包导入 ----------------

    /**
     * 把解析好的会话整批写进库。
     * 一万多条消息逐条提交会慢到没法用，所以全部包在一个事务里。
     */
    fun importZipSessions(sessions: List<DeepSeekZipImporter.ImportedSession>): Int {
        if (sessions.isEmpty()) return 0
        val db = helper.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            sessions.forEach { s ->
                val convId = UUID.randomUUID().toString()
                db.insert("conversation", null, ContentValues().apply {
                    put("id", convId)
                    put("title", s.title)
                    put("created_at", s.createdAt)
                    put("updated_at", s.updatedAt)
                    put("pinned", 0)
                    put("is_master", 0)
                })

                // 原包里的消息 id 是数字，先整体换成 UUID，再回填 parent
                val idMap = HashMap<Long, String>(s.messages.size * 2)
                s.messages.forEach { m -> idMap[m.oldId] = UUID.randomUUID().toString() }

                s.messages.forEachIndexed { index, m ->
                    val newId = idMap[m.oldId] ?: return@forEachIndexed
                    val attachments = m.fileNames.map { "miss|${m.oldId}|$it" }
                    db.insert("message", null, ContentValues().apply {
                        put("id", newId)
                        put("conv_id", convId)
                        put("parent_id", m.parentOldId?.let { idMap[it] })
                        put("role", m.role)
                        put("content", m.content)
                        put("thinking", m.thinking)
                        put("status", "done")
                        put("model", "imported")
                        put("created_at", s.createdAt + index * 1000L)
                        put("attachments", encodeAttachments(attachments))
                        put("citations", "[]")
                    })
                }

                val leaf = s.leafOldId?.let { idMap[it] }
                    ?: s.messages.lastOrNull()?.oldId?.let { idMap[it] }
                if (leaf != null) {
                    db.update(
                        "conversation",
                        ContentValues().apply { put("active_leaf_id", leaf) },
                        "id = ?",
                        arrayOf(convId)
                    )
                }
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    // ---------------- 映射 ----------------

    private fun Cursor.toConversation(): Conversation {
        val kbIndex = getColumnIndex("kb_id")
        val lockIndex = getColumnIndex("kb_locked")
        val summaryIndex = getColumnIndex("summary")
        val masterIndex = getColumnIndex("is_master")
        val primaryIndex = getColumnIndex("is_primary")
        val scopeIndex = getColumnIndex("kb_scope")
        val profileIndex = getColumnIndex("in_profile")
        return Conversation(
            id = getString(getColumnIndexOrThrow("id")),
            title = getString(getColumnIndexOrThrow("title")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            activeLeafId = getString(getColumnIndexOrThrow("active_leaf_id")),
            systemPrompt = getString(getColumnIndexOrThrow("system_prompt")),
            pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
            kbId = if (kbIndex >= 0) getString(kbIndex) else null,
            kbLocked = lockIndex >= 0 && getInt(lockIndex) != 0,
            summary = if (summaryIndex >= 0) getString(summaryIndex) else null,
            inProfile = profileIndex >= 0 && getInt(profileIndex) != 0,
            isMaster = masterIndex >= 0 && getInt(masterIndex) != 0,
            isPrimary = primaryIndex >= 0 && getInt(primaryIndex) != 0,
            kbScope = if (scopeIndex >= 0) {
                getString(scopeIndex)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            } else {
                emptyList()
            }
        )
    }

    /** 设置总 AI 实例的知识范围。空列表 = 全部。 */
    fun setKbScope(convId: String, kbIds: List<String>) {
        val cv = ContentValues().apply {
            put("kb_scope", if (kbIds.isEmpty()) null else kbIds.joinToString(","))
        }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(convId))
    }

    // ---------------- 用户档案 ----------------

    fun profileText(): String {
        var text = ""
        runCatching {
            helper.readableDatabase
                .rawQuery("SELECT text FROM profile WHERE id = 1", null)
                .use { c -> if (c.moveToFirst()) text = c.getString(0) ?: "" }
        }
        return text
    }

    fun setProfileText(text: String) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("text", text)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict(
            "profile", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun markInProfile(convId: String, inProfile: Boolean) {
        val cv = ContentValues().apply { put("in_profile", if (inProfile) 1 else 0) }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(convId))
    }

    // ---------------- 全文检索（L3） ----------------

    /**
     * 按关键词去原文里翻。多条查询词分别命中，命中越多的排越前。
     * 没用 FTS：中文在 FTS 的默认分词下基本等于整句一个 token，效果很差。
     * 消息量级到几千条时 LIKE 全表扫也就几十毫秒，够用。
     */
    fun searchMessages(
        terms: List<String>,
        perTerm: Int = 8,
        kbId: String? = null
    ): List<Message> {
        if (terms.isEmpty()) return emptyList()
        val scores = LinkedHashMap<String, Int>()
        val found = LinkedHashMap<String, Message>()

        // 知识库的 Demiurge 只在本库的对话里翻
        val (sql, baseArgs) = if (kbId == null) {
            "SELECT * FROM message WHERE content LIKE ? ORDER BY created_at DESC LIMIT $perTerm" to
                emptyArray<String>()
        } else {
            "SELECT m.* FROM message m JOIN conversation c ON m.conv_id = c.id " +
                "WHERE m.content LIKE ? AND c.kb_id = ? ORDER BY m.created_at DESC LIMIT $perTerm" to
                arrayOf(kbId)
        }

        terms.take(6).forEach { term ->
            val pattern = "%" + term.replace("%", "").replace("_", "") + "%"
            if (pattern.length < 4) return@forEach
            helper.readableDatabase.rawQuery(sql, arrayOf(pattern) + baseArgs).use { c ->
                while (c.moveToNext()) {
                    val m = c.toMessage()
                    if (m.content.length < 10) continue
                    found[m.id] = m
                    scores[m.id] = (scores[m.id] ?: 0) + 1
                }
            }
        }
        return found.values
            .sortedWith(compareByDescending<Message> { scores[it.id] ?: 0 }.thenByDescending { it.createdAt })
            .take(6)
    }

    // ---------------- 总 AI 的长期记忆 ----------------

    fun masterMemories(): List<MasterMemory> {
        val out = ArrayList<MasterMemory>()
        // 表万一还不存在（升级链有断档），返回空总比整段记忆读取崩掉好
        runCatching {
            helper.readableDatabase
                .rawQuery("SELECT * FROM master_memory ORDER BY created_at ASC", null)
                .use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            MasterMemory(
                                id = c.getString(c.getColumnIndexOrThrow("id")),
                                text = c.getString(c.getColumnIndexOrThrow("text")),
                                weight = c.getInt(c.getColumnIndexOrThrow("weight")),
                                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                            )
                        )
                    }
                }
        }
        return out
    }

    fun addMasterMemory(text: String, weight: Int = 75) {
        val cv = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("text", text)
            put("weight", weight.coerceIn(0, 100))
            put("created_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insert("master_memory", null, cv)
    }

    /** 压缩之后把旧消息删掉，并把边界那条的 parent 断开，保持链完整。 */
    fun compressBefore(convId: String, keepFromId: String) {
        val db = helper.writableDatabase
        val cutoff = db.rawQuery(
            "SELECT created_at FROM message WHERE id = ?", arrayOf(keepFromId)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return

        val cv = ContentValues().apply { putNull("parent_id") }
        db.update("message", cv, "id = ?", arrayOf(keepFromId))
        db.delete("message", "conv_id = ? AND created_at < ?", arrayOf(convId, cutoff.toString()))
    }

    /** 粗估这段对话占多少 token：中文按 1 token ≈ 1.5 字算。 */
    fun estimateTokens(convId: String): Int {
        var chars = 0
        helper.readableDatabase
            .rawQuery("SELECT content FROM message WHERE conv_id = ?", arrayOf(convId))
            .use { c -> while (c.moveToNext()) chars += (c.getString(0) ?: "").length }
        chars += conversation(convId)?.summary?.length ?: 0
        return (chars / 1.5).toInt() + 600
    }

    fun setSummary(id: String, summary: String) {
        val cv = ContentValues().apply { put("summary", summary) }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(id))
    }

    fun setSystemPrompt(id: String, prompt: String?) {
        val cv = ContentValues().apply {
            put("system_prompt", if (prompt.isNullOrBlank()) null else prompt)
        }
        helper.writableDatabase.update("conversation", cv, "id = ?", arrayOf(id))
    }

    /** 某个知识库的助手会话（没有就是 null）。 */
    fun kbAssistant(kbId: String): Conversation? = masterRow(kbId)

    /**
     * 保证存在一条特殊会话。
     * kbId 为 null 时拿的是总 AI 的**主实例**；给了 kbId 就是那个知识库的助手。
     */
    fun ensureMaster(name: String, kbId: String? = null): Conversation {
        val existing = masterRow(kbId)
        if (existing != null) {
            // 知识库助手和总 AI 主实例都跟着设置里的名字走
            if (existing.title != name && (kbId != null || existing.isPrimary)) {
                rename(existing.id, name)
                return existing.copy(title = name)
            }
            return existing
        }
        return insertMaster(name, kbId, primary = true)
    }

    /** 总 AI 的全部实例，主实例排最前。 */
    fun masterInstances(): List<Conversation> {
        val out = ArrayList<Conversation>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM conversation WHERE is_master = 1 AND kb_id IS NULL " +
                "ORDER BY is_primary DESC, created_at ASC",
            null
        ).use { c -> while (c.moveToNext()) out.add(c.toConversation()) }
        return out
    }

    /** 新建一个额外的总 AI 实例（空白对话，不能当主实例）。 */
    fun createMasterVariant(name: String): Conversation = insertMaster(name, null, primary = false)

    private fun insertMaster(name: String, kbId: String?, primary: Boolean): Conversation {
        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = name,
            createdAt = now,
            updatedAt = now,
            activeLeafId = null,
            isMaster = true,
            kbId = kbId,
            isPrimary = primary
        )
        val cv = ContentValues().apply {
            put("id", conv.id)
            put("title", conv.title)
            put("created_at", conv.createdAt)
            put("updated_at", conv.updatedAt)
            put("pinned", 0)
            put("is_master", 1)
            put("is_primary", if (primary) 1 else 0)
            if (kbId != null) put("kb_id", kbId)
        }
        helper.writableDatabase.insert("conversation", null, cv)
        return conv
    }

    private fun Cursor.toMessage(): Message {
        val attachIndex = getColumnIndex("attachments")
        val citeIndex = getColumnIndex("citations")
        return Message(
            id = getString(getColumnIndexOrThrow("id")),
            convId = getString(getColumnIndexOrThrow("conv_id")),
            parentId = getString(getColumnIndexOrThrow("parent_id")),
            role = getString(getColumnIndexOrThrow("role")),
            content = getString(getColumnIndexOrThrow("content")) ?: "",
            thinking = getString(getColumnIndexOrThrow("thinking")) ?: "",
            status = getString(getColumnIndexOrThrow("status")) ?: "done",
            model = getString(getColumnIndexOrThrow("model")) ?: "",
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            error = getString(getColumnIndexOrThrow("error")),
            attachments = if (attachIndex >= 0) decodeAttachments(getString(attachIndex)) else emptyList(),
            citations = if (citeIndex >= 0) decodeCitations(getString(citeIndex)) else emptyList()
        )
    }

    fun updateMessageCitations(id: String, citations: List<Citation>) {
        val cv = ContentValues().apply { put("citations", encodeCitations(citations)) }
        helper.writableDatabase.update("message", cv, "id = ?", arrayOf(id))
    }

    private fun encodeCitations(list: List<Citation>): String {
        if (list.isEmpty()) return "[]"
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("title", c.title).put("url", c.url))
        }
        return arr.toString()
    }

    private fun decodeCitations(raw: String?): List<Citation> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null else Citation(o.optString("title"), url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeAttachments(list: List<String>): String {
        if (list.isEmpty()) return "[]"
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeAttachments(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context).also { instance = it }
            }
    }
}

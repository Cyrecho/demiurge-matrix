package com.zhiwo.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 完整备份：数据库 + 所有附件文件。
 *
 * 之前只备份了 zhiwo.db，结果卸载重装后消息还在、图片全丢 ——
 * 因为库里存的是本地路径，文件本身没进备份。
 */
object Backup {

    private const val DB = "zhiwo.db"
    private const val DB_WAL = "zhiwo.db-wal"
    private const val DB_SHM = "zhiwo.db-shm"
    private const val FILES = "files/"

    /** 打包到缓存目录，返回 zip 文件。失败返回 null。 */
    suspend fun create(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val dbDir = context.getDatabasePath(DB).parentFile ?: return@withContext null
            val dbFile = File(dbDir, DB)
            if (!dbFile.exists()) return@withContext null

            val out = File(context.cacheDir, "zhiwo-backup.zip")
            if (out.exists()) out.delete()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                // WAL 和 SHM 一起打进去，恢复时 SQLite 自己能接上
                listOf(DB, DB_WAL, DB_SHM).forEach { name ->
                    val f = File(dbDir, name)
                    if (f.exists() && f.length() > 0) {
                        zip.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                val filesDir = context.filesDir
                if (filesDir.exists()) {
                    filesDir.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(filesDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(FILES + rel))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 zip 恢复。成功返回 null，失败返回错误描述。
     * 调用前必须先 close 数据库连接。
     */
    suspend fun restore(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext "读不到这个文件"
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // 防路径穿越
                        if (!entry.isDirectory && !name.contains("..") && !name.startsWith("/")) {
                            val target = File(tempDir, name)
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val dbInZip = File(tempDir, DB)
            if (!dbInZip.exists()) return@withContext "压缩包里没有 zhiwo.db"
            val header = ByteArray(16)
            dbInZip.inputStream().use { it.read(header) }
            if (!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) {
                return@withContext "备份里的数据库文件坏了"
            }

            val dbDir = context.getDatabasePath(DB).parentFile
                ?: return@withContext "找不到数据库目录"
            listOf(DB, DB_WAL, DB_SHM).forEach { name ->
                val dst = File(dbDir, name)
                val src = File(tempDir, name)
                if (src.exists()) {
                    dst.delete()
                    src.copyTo(dst, overwrite = true)
                } else {
                    dst.delete()
                }
            }

            val filesInZip = File(tempDir, "files")
            if (filesInZip.exists()) {
                val filesDir = context.filesDir
                filesDir.deleteRecursively()
                filesDir.mkdirs()
                filesInZip.copyRecursively(filesDir, overwrite = true)
            }

            tempDir.deleteRecursively()
            null
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            e.message ?: "恢复失败"
        }
    }
}

package com.zhiwo.app.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 文档上传：交给 DeepSeek 的 Files API 解析，本地不碰 PDF/Word。
 *
 * 上传时就完成解析，成功才返回 file_id。解析期间没有数据下发，
 * 所以超时要按「上传 + 解析」来设，官方给的是各 120 秒。
 */
object FileUploader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    private const val MAX_BYTES = 20 * 1024 * 1024

    /**
     * @param purpose text_recognition（只要文字，便宜）或 rich_layout_recognition（连图一起，贵但能看图）
     * @return file_id，形如 file-api-xxxx
     */
    suspend fun upload(
        context: Context,
        uri: Uri,
        displayName: String,
        purpose: String,
        baseUrl: String,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) return@withContext Result.failure(Exception("还没有填 API Key"))

            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("读不到这个文件"))
            if (bytes.isEmpty()) return@withContext Result.failure(Exception("文件是空的"))
            if (bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("文件太大了，控制在 20 MB 以内"))
            }

            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // purpose 必须在 file 之前，顺序反了会 400
                .addFormDataPart("purpose", purpose)
                .addFormDataPart("file", displayName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                .build()

            val base = baseUrl.trim().trimEnd('/')
            val request = Request.Builder()
                .url("$base/files")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    val friendly = when (resp.code) {
                        413 -> "文件超过 100 MB"
                        408 -> "上传太慢，超时了"
                        504 -> "解析超时（超过 120 秒）"
                        400 -> msg.ifBlank { "这个文件解析不了，可能加了密码或者格式不支持" }
                        else -> msg.ifBlank { "上传失败（${resp.code}）" }
                    }
                    return@use Result.failure(Exception(friendly))
                }
                val id = try {
                    JSONObject(text).optString("id")
                } catch (e: Exception) {
                    ""
                }
                if (id.isBlank()) Result.failure(Exception("服务端没返回 file_id"))
                else Result.success(id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 按扩展名判断用哪种解析方式。 */
    fun purposeFor(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "text_recognition"
            else -> "user_data"
        }
    }
}

package com.zhiwo.app.data

import android.content.Context
import com.zhiwo.app.prompt.Persona

/** 设置项。全部存在本机，不上传。 */
class Settings private constructor(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("zhiwo_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(value) = sp.edit().putString("base_url", value).apply()

    /**
     * API Key 用 Keystore 加密后再落盘（存 `api_key_enc`）。
     * 旧版本是明文存在 `api_key` 里的，读到就顺手迁移成密文并删掉明文。
     */
    var apiKey: String
        get() {
            val encrypted = sp.getString("api_key_enc", null)
            if (!encrypted.isNullOrBlank()) {
                return SecretStore.decrypt(encrypted) ?: ""
            }
            val legacy = sp.getString("api_key", null)
            if (!legacy.isNullOrBlank()) {
                SecretStore.encrypt(legacy)?.let { cipher ->
                    sp.edit().putString("api_key_enc", cipher).remove("api_key").apply()
                }
                return legacy
            }
            return ""
        }
        set(value) {
            val cipher = SecretStore.encrypt(value)
            if (cipher != null) {
                sp.edit().putString("api_key_enc", cipher).remove("api_key").apply()
            }
        }

    var model: String
        get() = sp.getString("model", "deepseek-flash") ?: "deepseek-flash"
        set(value) = sp.edit().putString("model", value).apply()

    var thinking: Boolean
        get() = sp.getBoolean("thinking", true)
        set(value) = sp.edit().putBoolean("thinking", value).apply()

    var effort: String
        get() = sp.getString("effort", "max") ?: "max"
        set(value) = sp.edit().putString("effort", value).apply()

    var temperature: Float
        get() = sp.getFloat("temperature", 1.0f)
        set(value) = sp.edit().putFloat("temperature", value).apply()

    var systemPrompt: String
        get() = sp.getString("system_prompt", null) ?: Persona.DEFAULT
        set(value) = sp.edit().putString("system_prompt", value).apply()

    var thinkingStyle: Boolean
        get() = sp.getBoolean("thinking_style", true)
        set(value) = sp.edit().putBoolean("thinking_style", value).apply()

    /** 总 AI 的名字，默认 Demiurge。 */
    var masterName: String
        get() = sp.getString("master_name", "Demiurge")?.takeIf { it.isNotBlank() } ?: "Demiurge"
        set(value) = sp.edit().putString("master_name", value.trim()).apply()

    /** system / light / dark */
    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(value) = sp.edit().putString("theme_mode", value).apply()

    fun resetSystemPrompt() {
        sp.edit().remove("system_prompt").apply()
    }

    /**
     * 启动时就把遗留的明文 Key 迁移成密文。
     * 只靠 getter 里的懒迁移不行 —— 用户可能开着 app 但暂时不发消息，
     * 那明文会一直躺在磁盘上。
     */
    fun migrateLegacyKey() {
        val legacy = sp.getString("api_key", null)
        if (legacy.isNullOrBlank()) return
        val cipher = SecretStore.encrypt(legacy) ?: return
        sp.edit().putString("api_key_enc", cipher).remove("api_key").apply()
    }

    companion object {
        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also {
                    instance = it
                    it.migrateLegacyKey()
                }
            }
    }
}

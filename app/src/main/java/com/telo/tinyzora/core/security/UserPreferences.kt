package com.telo.tinyzora.core.security

import android.content.Context
import android.content.SharedPreferences

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN_HASH        = "pin_hash"
        private const val KEY_MODEL_PATH      = "model_path"
        private const val KEY_IMPORTED_MODELS = "imported_models"
        private const val KEY_TEMPERATURE     = "inference_temperature"
        private const val KEY_TOPK            = "inference_topk"
        private const val KEY_TOPP            = "inference_topp"
        private const val KEY_MAX_TOKENS      = "inference_max_tokens"
        private const val KEY_SERVER_URL      = "llama_server_url"
        private const val KEY_CTX_SIZE        = "llama_ctx_size"
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun hashPin(pin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)
    fun setPin(pin: String) = prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return stored == hashPin(pin)
    }
    fun clearPin() = prefs.edit().remove(KEY_PIN_HASH).apply()

    fun getModelPath(): String =
        prefs.getString(KEY_MODEL_PATH, "") ?: ""

    fun setModelPath(path: String) =
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()

    fun getTemperature(): Float = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
    fun setTemperature(v: Float) = prefs.edit().putFloat(KEY_TEMPERATURE, v).apply()

    fun getTopK(): Int = prefs.getInt(KEY_TOPK, 40)
    fun setTopK(v: Int) = prefs.edit().putInt(KEY_TOPK, v).apply()

    fun getTopP(): Float = prefs.getFloat(KEY_TOPP, 0.9f)
    fun setTopP(v: Float) = prefs.edit().putFloat(KEY_TOPP, v).apply()

    fun getMaxTokens(): Int = prefs.getInt(KEY_MAX_TOKENS, 8192)
    fun setMaxTokens(v: Int) = prefs.edit().putInt(KEY_MAX_TOKENS, v).apply()

    fun getServerUrl(): String =
        prefs.getString(KEY_SERVER_URL, "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"

    fun setServerUrl(url: String) =
        prefs.edit().putString(KEY_SERVER_URL, url).apply()

    fun getCtxSize(): Int = prefs.getInt(KEY_CTX_SIZE, 8192)
    fun setCtxSize(v: Int) = prefs.edit().putInt(KEY_CTX_SIZE, v).apply()

    fun getImportedModels(): List<com.telo.tinyzora.core.inference.ImportedModel> {
        val jsonStr = prefs.getString(KEY_IMPORTED_MODELS, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<com.telo.tinyzora.core.inference.ImportedModel>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setImportedModels(models: List<com.telo.tinyzora.core.inference.ImportedModel>) {
        val jsonStr = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                com.telo.tinyzora.core.inference.ImportedModel.serializer()
            ),
            models
        )
        prefs.edit().putString(KEY_IMPORTED_MODELS, jsonStr).apply()
    }
}

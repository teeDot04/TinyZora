package com.telo.tinyzora.core.security

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)

    fun getModelPath(): String = prefs.getString("model_path", "") ?: ""
    fun setModelPath(path: String) {
        prefs.edit().putString("model_path", path).apply()
    }

    fun getCtxSize(): Int = prefs.getInt("ctx_size", 4096)
    fun setCtxSize(size: Int) {
        prefs.edit().putInt("ctx_size", size).apply()
    }

    fun getTopK(): Int = prefs.getInt("top_k", 40)
    fun setTopK(k: Int) {
        prefs.edit().putInt("top_k", k).apply()
    }

    fun getTopP(): Float = prefs.getFloat("top_p", 0.9f)
    fun setTopP(p: Float) {
        prefs.edit().putFloat("top_p", p).apply()
    }

    fun getTemperature(): Float = prefs.getFloat("temperature", 0.7f)
    fun setTemperature(temp: Float) {
        prefs.edit().putFloat("temperature", temp).apply()
    }

    fun getMaxTokens(): Int = prefs.getInt("max_tokens", 512)
    fun setMaxTokens(tokens: Int) {
        prefs.edit().putInt("max_tokens", tokens).apply()
    }
}

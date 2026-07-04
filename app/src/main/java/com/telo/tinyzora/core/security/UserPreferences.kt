package com.telo.tinyzora.core.security

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)

    fun getModelPath(): String = prefs.getString("model_path", "") ?: ""
    
    fun setModelPath(path: String) {
        prefs.edit().putString("model_path", path).apply()
    }

    fun getCtxSize(): Int = prefs.getInt("ctx_size", 4096)
    fun getTopK(): Int = prefs.getInt("top_k", 40)
    fun getTopP(): Float = prefs.getFloat("top_p", 0.9f)
    fun getTemperature(): Float = prefs.getFloat("temperature", 0.7f)
}

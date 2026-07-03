package com.telo.tinyzora.core.security
import android.content.Context
class UserPreferences(context: Context) {
    fun getModelPath(): String = ""
    fun getCtxSize(): Int = 4096
    fun getTopK(): Int = 40
    fun getTopP(): Float = 0.9f
    fun getTemperature(): Float = 0.7f
}

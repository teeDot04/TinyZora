package com.telo.tinyzora.core.security

import android.content.Context
import android.content.SharedPreferences

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
    }

    // Hash the PIN before storing it. A simple SHA-256 hash is sufficient for this lockscreen.
    private fun hashPin(pin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isPinSet(): Boolean {
        return prefs.contains(KEY_PIN_HASH)
    }

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return storedHash == hashPin(pin)
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
    }
}

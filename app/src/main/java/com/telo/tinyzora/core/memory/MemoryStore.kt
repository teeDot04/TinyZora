package com.telo.tinyzora.core.memory
import android.content.Context
class MemoryStore(context: Context) {
    fun buildSystemPrompt(): String = "You are TinyZora, a helpful AI assistant."
    fun merge(entries: List<MemoryEntry>) {}
}

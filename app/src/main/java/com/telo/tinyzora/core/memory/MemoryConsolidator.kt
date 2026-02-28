package com.telo.tinyzora.core.memory

import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate

class MemoryConsolidator(private val memoryStore: MemoryStore) {
    private val TAG = "MemoryConsolidator"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun consolidate(
        transcript: List<Pair<String, String>>,
        generateFn: suspend (String) -> String
    ) {
        if (transcript.isEmpty()) return
        
        val prompt = buildString {
            appendLine("Analyze the following conversation transcript and extract memory entries.")
            appendLine("Return ONLY a raw JSON array of objects. No markdown fences, no explanation.")
            appendLine("Each entry must have: type (\"fact\", \"preference\", \"reminder\", \"correction\", \"trading_signal\"), content (under 20 words), date (today's date ${LocalDate.now()}), and optionally due (if reminder).")
            appendLine("Maximum 8 entries. Return [] if nothing worth remembering.")
            appendLine("Transcript:")
            transcript.forEach { (role, text) ->
                appendLine("${role.uppercase()}: $text")
            }
        }

        try {
            val response = generateFn(prompt)
            val cleaned = response.replace("```json", "").replace("```", "").trim()
            val startIndex = cleaned.indexOf('[')
            val endIndex = cleaned.lastIndexOf(']')
            if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
                val jsonStr = cleaned.substring(startIndex, endIndex + 1)
                val entries = json.decodeFromString<List<MemoryEntry>>(jsonStr)
                if (entries.isNotEmpty()) {
                    memoryStore.merge(entries)
                    Log.d(TAG, "Consolidated ${entries.size} entries.")
                } else {
                    Log.d(TAG, "No entries to consolidate.")
                }
            } else {
                Log.d(TAG, "Failed to find JSON array in response: $cleaned")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during memory consolidation", e)
        }
    }
}

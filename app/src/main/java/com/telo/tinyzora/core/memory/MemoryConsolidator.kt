package com.telo.tinyzora.core.memory

import android.util.Log
import com.telo.tinyzora.util.ConsoleLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MemoryConsolidator(
    private val memoryStore: MemoryStore,
    private val anchorTime: ZonedDateTime? = null
) {
    private val TAG = "MemoryConsolidator"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun consolidate(
        transcript: List<Pair<String, String>>,
        generateFn: suspend (String) -> String
    ) {
        if (transcript.isEmpty()) return
        
        val nairobiZone = ZoneId.of("Africa/Nairobi")
        val now = anchorTime ?: ZonedDateTime.now(nairobiZone)
        val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        val existingEntries = memoryStore.load().entries
            .joinToString("\n") { "- ${it.type}: ${it.content}" }

        val exclusionBlock = if (existingEntries.isNotEmpty())
            "ALREADY KNOWN — do not extract these again:\n$existingEntries\n\n"
        else
            ""

        val prompt = """
            |${exclusionBlock}Extract only NEW facts, preferences, 
            |reminders, or trading signals from this conversation 
            |transcript that are not already listed above.
            |
            |The current precise date and time is: ${now.format(isoFormatter)}
            |Return ONLY a raw JSON array of objects. No markdown fences. Maximum 8 entries. Return [] if empty learned nothing new.
            |Fields: 'type' (fact, preference, reminder, correction, trading_signal), 'content' (under 20 words), 'date' (today's date `${now.toLocalDate()}`).
            |CRITICAL: If type is 'reminder', you MUST add a 'due' field.
            |
            |### EXAMPLES
            |TRANSCRIPT:
            |USER: Remind me to smile in 1 minute
            |OUTPUT: [{"type": "reminder", "content": "Smile", "date": "${now.toLocalDate()}", "due": "+1m"}]
            |### END EXAMPLES
            |
            |TRANSCRIPT:
            |${transcript.joinToString("\n") { (role, text) -> "${role.uppercase()}: $text" }}
            |
            |Return a raw JSON array only. If nothing new was learned return exactly: []
        """.trimMargin()

        try {
            val response = generateFn(prompt)
            val cleaned = response.replace("```json", "").replace("```", "").trim()
            val startIndex = cleaned.indexOf('[')
            val endIndex = cleaned.lastIndexOf(']')
            if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
                val jsonStr = cleaned.substring(startIndex, endIndex + 1)
                val nairobiZone = ZoneId.of("Africa/Nairobi")
                val now = anchorTime ?: ZonedDateTime.now(nairobiZone)
                val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

                val entries = json.decodeFromString<List<MemoryEntry>>(jsonStr).map { rawEntry ->
                    val entry = if (rawEntry.date.isEmpty()) rawEntry.copy(date = now.toLocalDate().toString()) else rawEntry
                    if (entry.type == "reminder" && entry.due?.startsWith("+") == true) {
                        val cleanDue = entry.due.trim()
                        val amountStr = cleanDue.substring(1, cleanDue.length - 1)
                        val amount = amountStr.toLongOrNull() ?: 0L
                        val absoluteDue = when (cleanDue.last().lowercaseChar()) {
                            's' -> now.plusSeconds(amount)
                            'm' -> now.plusMinutes(amount)
                            'h' -> now.plusHours(amount)
                            'd' -> now.plusDays(amount)
                            else -> now
                        }
                        entry.copy(due = absoluteDue.format(isoFormatter))
                    } else {
                        entry
                    }
                }
                
                if (entries.isNotEmpty()) {
                    memoryStore.merge(entries)
                    ConsoleLogger.d(TAG, "Consolidated ${entries.size} entries.")
                } else {
                    ConsoleLogger.d(TAG, "No entries to consolidate.")
                }
            } else {
                ConsoleLogger.d(TAG, "Failed to find JSON array in response: $cleaned")
            }
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Error during memory consolidation", e)
        }
    }
}

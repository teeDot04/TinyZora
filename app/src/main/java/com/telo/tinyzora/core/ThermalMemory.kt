package com.telo.tinyzora.core

import com.telo.tinyzora.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ❄️ THERMAL MEMORY
 * Fetches max 2-3 items from SQLite.
 * Joins them into one flat string.
 * Caches the result (if needed, though here we just return the string for immediate use).
 * Designed to be COLD: No loops, no embeddings, fast reads.
 */
object ThermalMemory {

    suspend fun snapshot(dao: MemoryDao): String = withContext(Dispatchers.IO) {
        // 1. Fetch small random subset (Facts & Prefs)
        val facts = dao.getRandomFacts(limit = 2)
        val prefs = dao.getRandomPrefs(limit = 2)
        
        // 2. Fetch Time-Based Reminders (Only active ones)
        val reminders = dao.getActiveReminders() // Fetch all active, filter by time in logic if needed
        // For simplicity, we just list the top 2 urgent ones or all active ones if list is short
        val urgentReminders = reminders.take(2) 

        // 3. Build the String
        val sb = StringBuilder()
        
        if (facts.isNotEmpty()) {
            sb.append("Facts:\n")
            facts.forEach { sb.append("- ${it.content}\n") }
        }
        
        if (prefs.isNotEmpty()) {
            sb.append("\nPreferences:\n")
            prefs.forEach { sb.append("- ${it.content}\n") }
        }
        
        if (urgentReminders.isNotEmpty()) {
            sb.append("\nActive Reminders:\n")
            urgentReminders.forEach { sb.append("- [DUE ${it.dueTime}] ${it.content}\n") }
        } else {
            // Optional: check calendar logic if we had complex time handling
        }

        return@withContext sb.toString().trim()
    }
}

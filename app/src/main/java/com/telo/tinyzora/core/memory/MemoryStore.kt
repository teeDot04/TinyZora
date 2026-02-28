package com.telo.tinyzora.core.memory

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

class MemoryStore(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "memory.json")

    fun load(): MemoryFile {
        if (!file.exists()) return MemoryFile()
        return try {
            json.decodeFromString<MemoryFile>(file.readText())
        } catch (e: Exception) {
            MemoryFile()
        }
    }

    fun save(memoryFile: MemoryFile) {
        val tmpFile = File(context.filesDir, "memory.json.tmp")
        tmpFile.writeText(json.encodeToString(memoryFile))
        tmpFile.renameTo(file)
    }

    fun merge(newEntries: List<MemoryEntry>) {
        val current = load()
        val merged = current.entries.toMutableList()

        for (newEntry in newEntries) {
            // Deduplicate by exact content match
            if (merged.any { it.content == newEntry.content }) {
                continue
            }
            
            // Corrections override older entries containing same keywords
            if (newEntry.type == "correction") {
                val newWords = newEntry.content.lowercase().split("\\s+".toRegex()).toSet()
                merged.removeAll { oldEntry -> 
                    val oldWords = oldEntry.content.lowercase().split("\\s+".toRegex()).toSet()
                    val overlap = newWords.intersect(oldWords).size
                    overlap > 2
                }
            }
            merged.add(newEntry)
        }
        
        save(MemoryFile(version = 1, entries = merged))
    }

    fun buildSystemPrompt(): String {
        val file = load()
        val facts = file.entries.filter { it.type == "fact" }.takeLast(8)
        val prefs = file.entries.filter { it.type == "preference" }.takeLast(5)
        val reminders = file.entries.filter { it.type == "reminder" && it.due != null }
        
        return buildString {
            appendLine("You are Zora, a private AI companion for Otieno — a psychology student and data scientist.")
            if (facts.isNotEmpty()) {
                appendLine("Recent facts about the user:")
                facts.forEach { appendLine("- ${it.content}") }
            }
            if (prefs.isNotEmpty()) {
                appendLine("User preferences:")
                prefs.forEach { appendLine("- ${it.content}") }
            }
            if (reminders.isNotEmpty()) {
                appendLine("Active reminders:")
                reminders.forEach { appendLine("- ${it.content} (Due: ${it.due})") }
            }
            appendLine("Today's date: ${LocalDate.now()}")
            appendLine("Behaviour rules:")
            appendLine("- Analyse charts: name axes, identify type, state trends, flag outliers")
            appendLine("- Code: use markdown code blocks with language labels")
            appendLine("- Maths: use LaTeX notation (\$\$...\$\$)")
            appendLine("- Be concise unless asked for detail")
            appendLine("- Confirm reminders back to user when set")
        }
    }
}

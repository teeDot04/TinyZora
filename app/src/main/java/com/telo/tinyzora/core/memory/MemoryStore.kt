package com.telo.tinyzora.core.memory

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

    fun clearAllMemories() {
        save(MemoryFile(version = 1, entries = emptyList()))
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
            // 1. Exact match — skip
            if (merged.any { it.content == newEntry.content }) continue

            // 2. Near-duplicate check via Jaccard word overlap (>=55% shared words = duplicate)
            val newWords = newEntry.content.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            val isDuplicate = merged.any { existing ->
                val existingWords = existing.content.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                if (existingWords.isEmpty() || newWords.isEmpty()) false
                else {
                    val intersection = newWords.intersect(existingWords).size
                    val union = newWords.union(existingWords).size
                    intersection.toFloat() / union.toFloat() >= 0.55f
                }
            }
            if (isDuplicate) continue

            if (newEntry.type == "correction") {
                val newWords2 = newEntry.content.lowercase().split("\\s+".toRegex()).toSet()
                merged.removeAll { oldEntry ->
                    val oldWords = oldEntry.content.lowercase().split("\\s+".toRegex()).toSet()
                    newWords2.intersect(oldWords).size > 2
                }
            }

            if (newEntry.type == "delete_reminder") {
                val keyword = newEntry.content.lowercase()
                merged.removeAll { it.type == "reminder" && it.content.lowercase().contains(keyword) }
                continue
            }

            merged.add(newEntry)
        }

        save(MemoryFile(version = 1, entries = merged))
    }
    
    fun deleteReminderByContent(content: String) {
        val current = load()
        val merged = current.entries.toMutableList()
        merged.removeAll { it.type == "reminder" && it.content == content }
        save(MemoryFile(version = 1, entries = merged))
    }

    fun buildSystemPrompt(): String {
        val file = load()
        val facts = file.entries.filter { it.type == "fact" }.takeLast(8)
        val prefs = file.entries.filter { it.type == "preference" }.takeLast(5)
        val reminders = file.entries.filter { it.type == "reminder" && it.due != null }
        
        return buildString {
            val now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"))
            val timeString = now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy, HH:mm"))
            
            appendLine("Time: $timeString. You are Zora, Otieno's private AI.")
            appendLine("Nairobi-based psychologist, data scientist, trader.")
            appendLine("Sharp, direct, no filler.")
            appendLine()
            appendLine("MEMORY PROTOCOL:")
            appendLine("At the very END of your reply (after all visible text), append a hidden block ONLY if this conversation contains information you did NOT already know.")
            appendLine("Return [] if nothing new was learned — do NOT repeat facts listed under 'What you already know' below.")
            appendLine("```memory")
            appendLine("[{\"type\":\"fact\",\"content\":\"short summary under 20 words\",\"date\":\"${ZonedDateTime.now(ZoneId.of("Africa/Nairobi")).toLocalDate()}\"}]")
            appendLine("```")
            appendLine("Types: fact | preference | reminder. For reminders add \"due\": \"+5m\", \"+2h\", \"+1d\".")
            appendLine("CRITICAL: this block is INVISIBLE to Otieno — the app strips it. NEVER mention it.")
            appendLine("If unsure, say so plainly.")
            
            if (facts.isNotEmpty() || prefs.isNotEmpty() || reminders.isNotEmpty()) {
                appendLine()
                appendLine("--- What you already know (DO NOT re-save any of these) ---")
                if (facts.isNotEmpty()) {
                    appendLine("Facts:")
                    facts.forEach { appendLine("- ${it.content}") }
                }
                if (prefs.isNotEmpty()) {
                    appendLine("Prefs:")
                    prefs.forEach { appendLine("- ${it.content}") }
                }
                if (reminders.isNotEmpty()) {
                    appendLine("Reminders:")
                    reminders.forEach { appendLine("- ${it.content} (Due: ${it.due})") }
                }
                appendLine("--- End of existing memory ---")
            }

            appendLine()
            appendLine("Responses: 2-3 sentences unless asked for detail.")
            appendLine("CRITICAL RULE: DO NOT start your responses with 'Zora:', 'Assistant:', or any other name label. Just output the answer directly.")
            appendLine("Code: fenced blocks with language label.")
            appendLine("Display maths: \$\$...\$\$ blocks only.")
            appendLine("Inline maths: NEVER write raw LaTeX commands like \\hat{w} or \\alpha directly in text. Either wrap in \$\$...\$\$ for a formula block, or write plain English: \"w-hat\", \"alpha\", \"the gradient\". Never mix LaTeX syntax into prose without delimiters.")
            appendLine("Tables: Markdown tables.")

        }
    }
}

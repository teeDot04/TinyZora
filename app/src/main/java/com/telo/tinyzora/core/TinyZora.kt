package com.telo.tinyzora.core

import android.content.Context
import android.util.Log
// import com.telo.tinyzora.inference.InferenceModel // Removed
import com.telo.tinyzora.inference.InferenceManager
import com.telo.tinyzora.actions.UniversalExecutor
import com.telo.tinyzora.bridge.TinyBridge
import com.telo.tinyzora.data.MemoryDatabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * 🧠 TINY ZORA ORCHESTRATOR
 * Connects the Brain (LLM), The Hands (UniversalExecutor), and The Bridge (External Apps).
 */
class TinyZora(
    private val context: Context,
    private val bridge: TinyBridge
) {
    private val domainDetector = DomainDetector()
    private val universalExecutor = UniversalExecutor(context)
    private val memoryDao = MemoryDatabase.getDatabase(context).memoryDao()

    data class Response(
        val text: String,
        val actionResult: String? = null,
        val domain: LifeChapter
    )

    suspend fun processImage(bitmap: android.graphics.Bitmap, prompt: String): Response = withContext(Dispatchers.IO) {
        // Optimize: Resize to max 512px (AI Edge Gallery technique)
        val ratio = kotlin.math.min(512.0f / bitmap.width, 512.0f / bitmap.height)
        val resized = if (ratio < 1.0f) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else {
            bitmap
        }

        // Send to InferenceModel
        val responseText = InferenceManager.generateResponseWithImages(prompt, listOf(resized))
        
        return@withContext Response(
            text = responseText,
            actionResult = null,
            domain = LifeChapter.PERSONAL
        )
    }

    suspend fun process(userInput: String, chatHistory: String = "", mode: ChatMode = ChatMode.FAST_CHAT): Response {
        // 1. THERMAL MEMORY INJECTION (Read-Only, Cached-like)
        val memoryBlock = ThermalMemory.snapshot(memoryDao)
        val fullMemory = memoryBlock + "\n\nRecent Chat:\n$chatHistory"

        // 2. PROMPT CONSTRUCTION
        val systemPrompt = PromptBuilder.build(userInput, fullMemory, mode)

        // 3. GENERATE (Blocking)
        val rawResponse = InferenceManager.generateResponse(systemPrompt)
        
        val (cleanText, toolJson) = extractJson(rawResponse)
        var executionLog: String? = null

        if (toolJson != null) {
            val action = toolJson.optString("action")
            
            // INTERNAL ACTION: Save Reminder + Schedule Alarm
            if (action == "save_reminder") {
                 val content = toolJson.optString("content")
                 val time = toolJson.optString("time") // Expected HH:mm
                 
                 // 1. Save to Memory DB (Long-term)
                 memoryDao.insertReminder(com.telo.tinyzora.data.ReminderEntity(
                     content = content,
                     dueTime = time
                 ))
                 
                 // 2. Trigger System Alarm (Notification)
                 // We reuse UniversalExecutor's logic or call it directly? 
                 // Let's assume time is HH:mm.
                 try {
                     if (time.contains(":")) {
                         val (h, m) = time.split(":").map { it.trim().toInt() }
                         val alarmJson = JSONObject().put("time", time).put("label", content)
                         universalExecutor.execute("set_alarm", alarmJson)
                         executionLog = "Saved memory & Set Alarm for $time"
                     } else {
                         executionLog = "Saved memory (No time parsed for alarm)."
                     }
                 } catch (e: Exception) {
                     executionLog = "Saved memory but failed to set alarm: ${e.message}"
                 }
            }
            // INTERNAL ACTION: Save Fact
            else if (action == "save_fact") {
                 val content = toolJson.optString("content")
                 memoryDao.insertFact(com.telo.tinyzora.data.FactEntity(content = content))
                 executionLog = "Memorized Fact: $content"
            }
            // INTERNAL ACTION: Save Preference
            else if (action == "save_preference") {
                 val content = toolJson.optString("content")
                 memoryDao.insertPref(com.telo.tinyzora.data.PrefEntity(content = content))
                 executionLog = "Memorized Preference: $content"
            }
        }

        // Improved Response Construction
        val finalText = if (cleanText.isNotBlank()) {
            cleanText
        } else {
            executionLog?.let { "Done. $it" } ?: "I've processed that."
        }
        
        return Response(
            text = finalText,
            actionResult = executionLog,
            domain = com.telo.tinyzora.core.LifeChapter.PERSONAL
        )
    }

    private fun extractJson(response: String): Pair<String, JSONObject?> {
        // Regex to find JSON block, optionally wrapped in markdown code fence
        val jsonRegex = Regex("```json\\s*(\\{.*?\\})\\s*```|(\\{.*\\})", RegexOption.DOT_MATCHES_ALL)
        val match = jsonRegex.find(response)

        if (match != null) {
            try {
                // Group 1 (fenced) or Group 2 (raw)
                val jsonStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val json = JSONObject(jsonStr)
                
                // Remove the FULL match (including backticks) from response to get clean text
                var cleanText = response.replace(match.value, "")
                
                // Extra cleanup for any leftover artifacts or excessive newlines
                cleanText = cleanText.replace("```json", "").replace("```", "").trim()
                
                return Pair(cleanText, json)
            } catch (e: Exception) {
                Log.e("TinyZora", "JSON match found but parsing failed: ${e.message}")
            }
        }
        
        // Fallback: Try manual bracket finding if regex failed (e.g. malformed markdown)
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
             try {
                val mixedStr = response.substring(start, end + 1)
                val json = JSONObject(mixedStr)
                val cleanText = response.replace(mixedStr, "").trim()
                return Pair(cleanText, json)
             } catch (e: Exception) {
                // Ignore, was not valid JSON
             }
        }

        return Pair(response, null)
    }

    private fun isSystemTool(tool: String): Boolean {
        val systemTools = setOf(
            "set_alarm", "set_timer", "add_calendar", "flashlight", 
            "volume", "vibrate", "read_notifications", "read_call_logs", "dial", "whatsapp", "email", 
            "map", "search", "open_url", "launch_app", 
            "wifi_settings", "bluetooth_settings", "battery_settings"
        )
        return systemTools.contains(tool.lowercase())
    }
}

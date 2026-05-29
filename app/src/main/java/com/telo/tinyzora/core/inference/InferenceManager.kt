package com.telo.tinyzora.core.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.telo.tinyzora.util.ConsoleLogger
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.memory.MemoryConsolidator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class InferenceManager(private val context: Context, private val memoryStore: MemoryStore) {

    private val TAG = "InferenceManager"
    private val mutex = Mutex()
    private val userPrefs = com.telo.tinyzora.core.security.UserPreferences(context)

    private val history = mutableListOf<JSONObject>()
    private var systemPrompt: String = ""
    private var isInitialized = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val serverUrl: String
        get() = userPrefs.getServerUrl().ifBlank { "http://127.0.0.1:8080" }

    suspend fun initialise(chatContext: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val pendingFile = File(context.filesDir, "pending_transcript.json")
            if (pendingFile.exists()) {
                try {
                    val transcriptJson = pendingFile.readText()
                    val jsonParser = Json { ignoreUnknownKeys = true }
                    val transcript: List<Pair<String, String>> =
                        jsonParser.decodeFromString(transcriptJson)
                    val fileTime = ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(pendingFile.lastModified()),
                        ZoneId.of("Africa/Nairobi")
                    )
                    val consolidator = MemoryConsolidator(memoryStore, fileTime)
                    consolidator.consolidate(transcript, this@InferenceManager::generateOnce)
                } catch (e: Exception) {
                    ConsoleLogger.e(TAG, "Failed to process pending transcript: ${e.message}")
                } finally {
                    pendingFile.delete()
                }
            }

            rebuildHistory(chatContext)
            com.telo.tinyzora.core.notifications.ReminderScheduler
                .scheduleAllReminders(context, memoryStore)

            isInitialized = true
            ConsoleLogger.d(TAG, "InferenceManager initialized -> $serverUrl")
            true
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Failed to initialize: ${e.message}", e)
            false
        }
    }

    suspend fun ensureModeIs(mode: String, chatContext: String? = null) {
        ConsoleLogger.d(TAG, "ensureModeIs($mode) - HTTP backend, no swap needed")
        if (!isInitialized) initialise(chatContext)
    }

    suspend fun resetConversation(chatContext: String? = null) {
        mutex.withLock {
            rebuildHistory(chatContext)
            ConsoleLogger.d(TAG, "Conversation reset.")
        }
    }

    suspend fun resetConversation() = resetConversation(null)

    private fun rebuildHistory(chatContext: String? = null) {
        systemPrompt = memoryStore.buildSystemPrompt()
        if (!chatContext.isNullOrBlank()) {
            systemPrompt += "\n\n=== RECENT CONVERSATION CONTEXT ===\n$chatContext\n==================================="
        }
        history.clear()
    }

    fun sendMessage(text: String): Flow<InferenceResult> = flow {
        mutex.withLock {
            history.add(userMessage(text))
            val results = mutableListOf<InferenceResult>()
            streamRequest(buildPayload(history, stream = true)) { results.add(it) }
            val fullText = results.filter { !it.isDone }.joinToString("") { it.partialText ?: "" }
            history.add(assistantMessage(fullText))
            results.forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithImage(text: String, bitmap: Bitmap): Flow<InferenceResult> = flow {
        mutex.withLock {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val content = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$b64")
                    })
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
            }
            val msg = JSONObject().apply {
                put("role", "user")
                put("content", content)
            }
            history.add(msg)

            val results = mutableListOf<InferenceResult>()
            streamRequest(buildPayload(history, stream = true)) { results.add(it) }
            val fullText = results.filter { !it.isDone }.joinToString("") { it.partialText ?: "" }
            history.add(assistantMessage(fullText))
            results.forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithAudio(text: String, audioBytes: ByteArray): Flow<InferenceResult> = flow {
        mutex.withLock {
            if (audioBytes.isEmpty()) {
                emit(InferenceResult("Error: Received empty audio buffer.", true))
                return@withLock
            }
            val fallbackText = text.ifBlank {
                "[Audio received but transcription not supported by current model]"
            }
            history.add(userMessage(fallbackText))
            val results = mutableListOf<InferenceResult>()
            streamRequest(buildPayload(history, stream = true)) { results.add(it) }
            val fullText = results.filter { !it.isDone }.joinToString("") { it.partialText ?: "" }
            history.add(assistantMessage(fullText))
            results.forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val messages = mutableListOf(userMessage(prompt))
            val payload = buildPayload(messages, stream = false)
            try {
                val request = Request.Builder()
                    .url("$serverUrl/v1/chat/completions")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext ""
                    val body = response.body?.string() ?: return@withContext ""
                    JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "generateOnce error: ${e.message}")
                ""
            }
        }
    }

    fun close() {
        history.clear()
        isInitialized = false
        ConsoleLogger.d(TAG, "InferenceManager closed.")
    }

    private fun streamRequest(payload: JSONObject, onResult: (InferenceResult) -> Unit) {
        val request = Request.Builder()
            .url("$serverUrl/v1/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onResult(InferenceResult("\n[Server error: ${response.code}]", true))
                    return
                }
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var inThink = false

                reader.forEachLine { line ->
                    if (!line.startsWith("data: ")) return@forEachLine
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        onResult(InferenceResult("", true))
                        return@forEachLine
                    }
                    try {
                        val chunk = JSONObject(data)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("delta")
                            .optString("content", "")

                        if (chunk.isEmpty()) return@forEachLine

                        var remaining = chunk
                        while (remaining.isNotEmpty()) {
                            if (!inThink) {
                                val thinkStart = remaining.indexOf("<think>")
                                if (thinkStart == -1) {
                                    onResult(InferenceResult(remaining, false, null))
                                    remaining = ""
                                } else {
                                    if (thinkStart > 0) {
                                        onResult(InferenceResult(remaining.substring(0, thinkStart), false))
                                    }
                                    remaining = remaining.substring(thinkStart + 7)
                                    inThink = true
                                }
                            } else {
                                val thinkEnd = remaining.indexOf("</think>")
                                if (thinkEnd == -1) {
                                    onResult(InferenceResult("", false, remaining))
                                    remaining = ""
                                } else {
                                    val thinkContent = remaining.substring(0, thinkEnd)
                                    if (thinkContent.isNotEmpty()) {
                                        onResult(InferenceResult("", false, thinkContent))
                                    }
                                    remaining = remaining.substring(thinkEnd + 8)
                                    inThink = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ConsoleLogger.e(TAG, "Chunk parse error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Stream request failed: ${e.message}")
            onResult(InferenceResult("\n[Connection failed - is llama-server running?]", true))
        }
    }

    private fun buildPayload(messages: List<JSONObject>, stream: Boolean): JSONObject {
        val fullMessages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            for (msg in messages) {
                put(msg)
            }
        }
        return JSONObject().apply {
            put("model", "local")
            put("messages", fullMessages)
            put("max_tokens", userPrefs.getMaxTokens())
            put("temperature", userPrefs.getTemperature().toDouble())
            put("top_p", userPrefs.getTopP().toDouble())
            put("stream", stream)
        }
    }

    private fun userMessage(text: String) = JSONObject().apply {
        put("role", "user")
        put("content", text)
    }

    private fun assistantMessage(text: String) = JSONObject().apply {
        put("role", "assistant")
        put("content", text)
    }
}

package com.telo.tinyzora.core.inference

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.telo.tinyzora.util.ConsoleLogger
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.memory.MemoryConsolidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

data class InferenceResult(
    val partialText: String = "",
    val isDone: Boolean = false,
    val partialThinking: String? = null
)

class InferenceManager(private val context: Context, private val memoryStore: MemoryStore) {
    private val TAG = "InferenceManager"
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val userPrefs = com.telo.tinyzora.core.security.UserPreferences(context)

    private val engine: InferenceEngine = InferenceEngineImpl.getInstance(context)

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)
    private var systemPrompt = ""
    private var isInitialized = false
    private var currentModelPath: String = ""

    private val modelPathListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "model_path") {
            scope.launch { reloadModel() }
        }
    }

    suspend fun initialise(chatContext: String? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val modelPath = userPrefs.getModelPath()
                if (modelPath.isBlank()) {
                    ConsoleLogger.e(TAG, "No model selected")
                    isInitialized = false
                    return@withLock false
                }

                // Only reload if not initialized or model path changed
                if (!isInitialized || modelPath != currentModelPath) {
                    systemPrompt = memoryStore.buildSystemPrompt()
                    ConsoleLogger.d(TAG, "System prompt length: ${systemPrompt.length}")
                    if (systemPrompt.isBlank()) {
                        ConsoleLogger.e(TAG, "WARNING: System prompt is empty!")
                    }

                    if (isInitialized) {
                        engine.cleanUp()
                        isInitialized = false
                    }

                    // Load model
                    engine.loadModel(modelPath)

                    // Set system prompt only if not empty
                    if (systemPrompt.isNotBlank()) {
                        engine.setSystemPrompt(systemPrompt)
                    }

                    currentModelPath = modelPath
                    isInitialized = true

                    // Process pending transcript if exists
                    val pendingFile = File(context.filesDir, "pending_transcript.json")
                    if (pendingFile.exists()) {
                        try {
                            val transcript: List<Pair<String, String>> =
                                Json { ignoreUnknownKeys = true }.decodeFromString(pendingFile.readText())
                            val fileTime = ZonedDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(pendingFile.lastModified()),
                                ZoneId.systemDefault()
                            )
                            MemoryConsolidator(memoryStore, fileTime)
                                .consolidate(transcript) { prompt ->
                                    generateOnceUnlocked(prompt)
                                }
                        } catch (e: Exception) {
                            ConsoleLogger.e(TAG, "Failed to process pending transcript", e)
                        } finally {
                            pendingFile.delete()
                        }
                    }

                    com.telo.tinyzora.core.notifications.ReminderScheduler
                        .scheduleAllReminders(context, memoryStore)
                    sharedPrefs.registerOnSharedPreferenceChangeListener(modelPathListener)

                    ConsoleLogger.d(TAG, "InferenceManager initialized successfully")
                }
                return@withLock true
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "Failed to initialize: ${e.message}", e)
                isInitialized = false
                currentModelPath = ""
                return@withLock false
            }
        }
    }

    suspend fun ensureModeIs(mode: String, chatContext: String? = null) {
        if (!isInitialized) initialise()
    }

    suspend fun resetConversation(chatContext: String? = null) {
        mutex.withLock {
            systemPrompt = memoryStore.buildSystemPrompt()
            ConsoleLogger.d(TAG, "Conversation state refreshed.")
        }
    }

    suspend fun resetConversation() = resetConversation(null)

    // NEW: Benchmark through InferenceManager
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
        if (!isInitialized) {
            throw RuntimeException("Model not loaded: Please import a model in Settings")
        }
        return engine.bench(pp, tg, pl, nr)
    }

    fun sendMessage(text: String, contextHistory: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            streamText(text)
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithImage(text: String, imageUri: Uri, contextHistory: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            send(InferenceResult("Image support coming soon!", true))
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithAudio(text: String, audioBytes: ByteArray, contextHistory: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            if (audioBytes.isEmpty()) {
                send(InferenceResult("Error: Received empty audio buffer.", true))
                return@withLock
            }
            streamText(text)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { generateOnceUnlocked(prompt) }
    }

    private suspend fun generateOnceUnlocked(prompt: String): String {
        val sb = StringBuilder()
        engine.sendUserPrompt(prompt).collect { token ->
            sb.append(token)
        }
        return sb.toString().trim()
    }

    fun close() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(modelPathListener)
        scope.launch {
            mutex.withLock {
                engine.cleanUp()
                isInitialized = false
                currentModelPath = ""
                cancel()
                ConsoleLogger.d(TAG, "InferenceManager closed cleanly.")
            }
        }
    }

    private suspend fun reloadModel() {
        mutex.withLock {
            val modelPath = userPrefs.getModelPath()
            if (modelPath.isBlank()) {
                isInitialized = false
                return@withLock
            }

            if (modelPath != currentModelPath) {
                engine.cleanUp()
                isInitialized = false

                try {
                    engine.loadModel(modelPath)
                    systemPrompt = memoryStore.buildSystemPrompt()
                    if (systemPrompt.isNotBlank()) {
                        engine.setSystemPrompt(systemPrompt)
                    }
                    currentModelPath = modelPath
                    isInitialized = true
                    ConsoleLogger.d(TAG, "Model reloaded successfully.")
                } catch (e: Exception) {
                    ConsoleLogger.e(TAG, "Failed to reload model", e)
                    isInitialized = false
                }
            }
        }
    }

    private suspend fun ProducerScope<InferenceResult>.streamText(userMessage: String) {
        if (!engine.state.value.let { it is InferenceEngine.State.ModelReady }) {
            send(InferenceResult("Model not loaded. Please select a model in Settings.", true))
            return
        }

        val responseBuilder = StringBuilder()
        var inThink = false

        engine.sendUserPrompt(userMessage).collect { token ->
            var remaining = token
            while (remaining.isNotEmpty()) {
                if (!inThink) {
                    val start = remaining.indexOf("<think>")
                    if (start == -1) {
                        responseBuilder.append(remaining)
                        trySend(InferenceResult(remaining, false))
                        remaining = ""
                    } else {
                        if (start > 0) {
                            val t = remaining.substring(0, start)
                            responseBuilder.append(t)
                            trySend(InferenceResult(t, false))
                        }
                        remaining = remaining.substring(start + 7)
                        inThink = true
                    }
                } else {
                    val end = remaining.indexOf("</think>")
                    if (end == -1) {
                        trySend(InferenceResult("", false, remaining))
                        remaining = ""
                    } else {
                        val thinking = remaining.substring(0, end)
                        if (thinking.isNotEmpty()) trySend(InferenceResult("", false, thinking))
                        remaining = remaining.substring(end + 8)
                        inThink = false
                    }
                }
            }
        }
        send(InferenceResult("", true))
    }
}

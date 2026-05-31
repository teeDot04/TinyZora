package com.telo.tinyzora.core.inference

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
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

class InferenceManager(private val context: Context, private val memoryStore: MemoryStore) {

    private val TAG = "InferenceManager"
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val userPrefs = com.telo.tinyzora.core.security.UserPreferences(context)
    private val llama = LlamaAndroid()
    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)

    private val history = mutableListOf<Pair<String, String>>()
    private var systemPrompt = ""
    private var isInitialized = false

    private val nThreads: Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

    private val modelPathListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "model_path" && isInitialized) {
            scope.launch { reloadModel() }
        }
    }

    suspend fun initialise(chatContext: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            rebuildHistory(chatContext)

            val modelPath = userPrefs.getModelPath()
            if (modelPath.isBlank()) {
                ConsoleLogger.e(TAG, "No model selected")
                return@withContext false
            }

            val loaded = llama.loadModel(
                path     = modelPath,
                nCtx     = userPrefs.getCtxSize(),
                nThreads = nThreads,
                topK     = userPrefs.getTopK(),
                topP     = userPrefs.getTopP(),
                temp     = userPrefs.getTemperature()
            )
            if (!loaded) {
                ConsoleLogger.e(TAG, "Failed to load model")
                return@withContext false
            }

            val pendingFile = File(context.filesDir, "pending_transcript.json")
            if (pendingFile.exists()) {
                try {
                    val transcript: List<Pair<String, String>> =
                        Json { ignoreUnknownKeys = true }
                            .decodeFromString(pendingFile.readText())
                    val fileTime = ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(pendingFile.lastModified()),
                        ZoneId.of("Africa/Nairobi")
                    )
                    MemoryConsolidator(memoryStore, fileTime)
                        .consolidate(transcript, this@InferenceManager::generateOnce)
                } catch (e: Exception) {
                    ConsoleLogger.e(TAG, "Failed to process pending transcript: ${e.message}")
                } finally {
                    pendingFile.delete()
                }
            }

            com.telo.tinyzora.core.notifications.ReminderScheduler
                .scheduleAllReminders(context, memoryStore)

            sharedPrefs.registerOnSharedPreferenceChangeListener(modelPathListener)
            isInitialized = true
            ConsoleLogger.d(TAG, "InferenceManager initialized (JNI, $nThreads threads)")
            true
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Failed to initialize: ${e.message}", e)
            false
        }
    }

    suspend fun ensureModeIs(mode: String, chatContext: String? = null) {
      ConsoleLogger.d(TAG, "ensureModeIs: isInitialized=$isInitialized modelLoaded=${llama.isModelLoaded()}")
        if (!isInitialized || !llama.isModelLoaded()) {
            initialise(chatContext)
        }
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

    fun sendMessage(text: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            streamText(text)
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithImage(text: String, bitmap: Bitmap): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            streamText("[Image attached]\n$text")
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithAudio(text: String, audioBytes: ByteArray): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            if (audioBytes.isEmpty()) {
                send(InferenceResult("Error: Received empty audio buffer.", true))
                return@withLock
            }
            val effectiveText = text.ifBlank { "[Audio received but transcription unavailable]" }
            streamText(effectiveText)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sb = StringBuilder()
            llama.sendMessageBlocking(prompt) { token -> sb.append(token) }
            sb.toString().trim()
        }
    }

    fun close() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(modelPathListener)
        llama.stopGeneration()
        llama.unloadModel()
        history.clear()
        isInitialized = false
        scope.cancel()
        ConsoleLogger.d(TAG, "InferenceManager closed.")
    }

    private suspend fun reloadModel() {
        mutex.withLock {
            val modelPath = userPrefs.getModelPath()
            if (modelPath.isBlank()) return
            ConsoleLogger.d(TAG, "Model path changed, reloading: $modelPath")
            llama.stopGeneration()
            llama.unloadModel()
            val loaded = llama.loadModel(
                path     = modelPath,
                nCtx     = userPrefs.getCtxSize(),
                nThreads = nThreads,
                topK     = userPrefs.getTopK(),
                topP     = userPrefs.getTopP(),
                temp     = userPrefs.getTemperature()
            )
            if (loaded) {
                rebuildHistory()
                ConsoleLogger.d(TAG, "Model reloaded successfully.")
            } else {
                ConsoleLogger.e(TAG, "Failed to reload model: $modelPath")
            }
        }
    }

    private suspend fun ProducerScope<InferenceResult>.streamText(userText: String) {
        if (!llama.isModelLoaded()) {
            send(InferenceResult("Model not loaded. Please select a model in Settings > AI Config.", true))
            return
        }
        history.add("user" to userText)
        val responseBuilder = StringBuilder()
        var inThink = false

        try {
            llama.sendMessageBlocking(buildPrompt()) { token ->
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
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Generation error: ${e.message}")
        }

        history.add("assistant" to responseBuilder.toString())
        send(InferenceResult("", true))
    }

    private fun buildPrompt(): String = buildString {
        append("<|im_start|>system\n").append(systemPrompt).append("\n<|im_end|>\n")
        for ((role, content) in history) {
            append("<|im_start|>$role\n").append(content).append("\n<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}


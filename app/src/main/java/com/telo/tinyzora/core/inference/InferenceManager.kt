
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

class InferenceManager(private val context: Context, private val memoryStore: MemoryStore) {

    private val TAG = "InferenceManager"
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val userPrefs = com.telo.tinyzora.core.security.UserPreferences(context)
    private val llama = LlamaAndroid()
    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("tinyzora_prefs", Context.MODE_PRIVATE)

    private var systemPrompt = ""
    private var isInitialized = false

    private val nThreads: Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

    // FIX 4: Unregister listener properly on teardown to prevent scope leaks
    private val modelPathListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "model_path" && isInitialized) {
            scope.launch { reloadModel() }
        }
    }

    suspend fun initialise(chatContext: String? = null): Boolean = withContext(Dispatchers.IO) {
        // FIX 3: Secure initialization state with mutex to prevent race conditions
        mutex.withLock {
            try {
                if (isInitialized) return@withLock true

                systemPrompt = memoryStore.buildSystemPrompt()
                val modelPath = userPrefs.getModelPath()
                if (modelPath.isBlank()) {
                    ConsoleLogger.e(TAG, "No model selected")
                    return@withLock false
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
                    return@withLock false
                }

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
                            .consolidate(transcript, this@InferenceManager::generateOnceUnlocked)
                    } catch (e: Exception) {
                        ConsoleLogger.e(TAG, "Failed to process pending transcript", e)
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

    fun sendMessage(text: String, contextHistory: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            streamText(contextHistory)
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithImage(text: String, imageUri: Uri, contextHistory: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            streamText(contextHistory)
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithAudio(text: String, audioBytes: ByteArray, contextHistory: String): Flow<InferenceResult> = channelFlow {
        mutex.withLock {
            if (audioBytes.isEmpty()) {
                send(InferenceResult("Error: Received empty audio buffer.", true))
                return@withLock
            }
            streamText(contextHistory)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { generateOnceUnlocked(prompt) }
    }

    // Extracted to avoid deadlocking when called from within an already-locked initialize block
    private fun generateOnceUnlocked(prompt: String): String {
        val sb = StringBuilder()
        llama.sendMessageBlocking(prompt) { token -> sb.append(token) }
        return sb.toString().trim()
    }

    fun close() {
        // FIX 1: JNI Teardown Race Condition.
        // Stop generation immediately OUTSIDE the lock to break the C++ loop if it's running.
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(modelPathListener)
        llama.stopGeneration() 
        
        // Launch the destructive teardown safely so we don't deadlock waiting for streamText to finish
        scope.launch {
            mutex.withLock {
                llama.unloadModel()
                isInitialized = false
                cancel() // Kills the scope entirely
                ConsoleLogger.d(TAG, "InferenceManager closed cleanly.")
            }
        }
    }

    private suspend fun reloadModel() {
        mutex.withLock {
            val modelPath = userPrefs.getModelPath()
            if (modelPath.isBlank()) return
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
                systemPrompt = memoryStore.buildSystemPrompt()
                ConsoleLogger.d(TAG, "Model reloaded successfully.")
            }
        }
    }

    private suspend fun ProducerScope<InferenceResult>.streamText(contextHistory: String) {
        if (!llama.isModelLoaded()) {
            send(InferenceResult("Model not loaded. Please select a model in Settings.", true))
            return
        }
        
        val finalPrompt = buildPrompt(contextHistory)
        val responseBuilder = StringBuilder()
        var inThink = false

        llama.sendMessageBlocking(finalPrompt) { token ->
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

    // FIX 2 & 5: Fixed ChatML formatting and removed redundant user message appending
    private fun buildPrompt(contextHistory: String): String = buildString {
        append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        
        // Reformat the raw ViewModel context into strict ChatML syntax
        if (contextHistory.isNotBlank()) {
            val formattedHistory = contextHistory
                .replace("User: ", "<|im_start|>user\n")
                .replace("Assistant: ", "<|im_start|>assistant\n")
                .replace(Regex("\n(?=<\\|im_start\\|>)"), "<|im_end|>\n") 
            
            append(formattedHistory).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}


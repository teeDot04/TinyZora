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
    private var currentModelPath: String = ""  // <-- FIX: Track current model

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

                // FIX: Reload if model path changed, don't block on isInitialized
                if (!isInitialized || modelPath != currentModelPath) {
                    systemPrompt = memoryStore.buildSystemPrompt()

                    if (isInitialized) {
                        engine.cleanUp()
                    }

                    engine.loadModel(modelPath)
                    engine.setSystemPrompt(systemPrompt)

                    currentModelPath = modelPath
                    isInitialized = true

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

                    ConsoleLogger.d(TAG, "InferenceManager initialized")
                }
                true
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "Failed to initialize: ${e.message}", e)
                isInitialized = false
                false
            }
        }
    }

    //

    private suspend fun reloadModel() {
        mutex.withLock {
            val modelPath = userPrefs.getModelPath()
            if (modelPath.isBlank()) {
                isInitialized = false
                return@withLock
            }

            engine.cleanUp()
            engine.loadModel(modelPath)
            systemPrompt = memoryStore.buildSystemPrompt()
            engine.setSystemPrompt(systemPrompt)
            currentModelPath = modelPath
            isInitialized = true
            ConsoleLogger.d(TAG, "Model reloaded successfully.")
        }
    }
}

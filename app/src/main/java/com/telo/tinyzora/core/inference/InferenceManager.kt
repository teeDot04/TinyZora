package com.telo.tinyzora.core.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.telo.tinyzora.util.ConsoleLogger
import com.google.ai.edge.litertlm.*
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.memory.MemoryConsolidator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class InferenceManager(private val context: Context, private val memoryStore: MemoryStore) {
    private val TAG = "InferenceManager"
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val mutex = Mutex()
    private val modelPath = "/data/local/tmp/gemma-3-2b-it-cpu-int4.litertlm"
    private var activeMode: String = "text"

    suspend fun initialise(chatContext: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val visionB = if (activeMode == "image") Backend.GPU else null
            val audioB = if (activeMode == "audio") Backend.CPU else null

            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,
                visionBackend = visionB,
                audioBackend = audioB,
                maxNumTokens = 4096,
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(config)
            engine?.initialize()

            // Check for pending transcript for memory consolidation
            val pendingFile = File(context.filesDir, "pending_transcript.json")
            if (pendingFile.exists()) {
                try {
                    val transcriptJson = pendingFile.readText()
                    val jsonParser = Json { ignoreUnknownKeys = true }
                    val transcript: List<Pair<String, String>> = jsonParser.decodeFromString(transcriptJson)

                    val fileTime = ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(pendingFile.lastModified()), 
                        ZoneId.of("Africa/Nairobi")
                    )
                    val consolidator = MemoryConsolidator(memoryStore, fileTime)
                    
                    // We run this using the same engine instance right when it turns on
                    consolidator.consolidate(transcript, this@InferenceManager::generateOnce)
                } catch (e: Exception) {
                    ConsoleLogger.e(TAG, "Failed to process pending transcript: ${e.message}")
                } finally {
                    pendingFile.delete() // Guarantee we never get stuck in a bootloop
                }
            }

            var systemInstruction = memoryStore.buildSystemPrompt()
            if (!chatContext.isNullOrBlank()) {
                systemInstruction += "\n\n=== RECENT CONVERSATION CONTEXT ===\n$chatContext\n==================================="
            }
            
            conversation = engine?.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                )
            )
            
            // Re-sync all AlarmManager intents against the latest MemoryStore JSON
            com.telo.tinyzora.core.notifications.ReminderScheduler.scheduleAllReminders(context, memoryStore)
            
            true
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Failed to initialize engine. Reason: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    suspend fun ensureModeIs(mode: String, chatContext: String? = null) {
        if (activeMode != mode) {
            ConsoleLogger.d(TAG, "Hot-Swapping Backend: $activeMode -> $mode")
            activeMode = mode
            try {
                conversation?.close()
                engine?.close()
            } catch (e: Exception) {
                ConsoleLogger.i(TAG, "Error closing old engine: ${e.message}")
            }
            conversation = null
            engine = null
            initialise(chatContext)
        }
    }

    suspend fun resetConversation(chatContext: String? = null) {
        mutex.withLock {
            try {
                conversation?.close()
                conversation = null
                
                var systemInstruction = memoryStore.buildSystemPrompt()
                if (!chatContext.isNullOrBlank()) {
                    systemInstruction += "\n\n=== RECENT CONVERSATION CONTEXT ===\n$chatContext\n==================================="
                }
                
                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                    )
                )
                ConsoleLogger.d(TAG, "Conversation context wiped and rebuilt.")
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "Failed to reset conversation: ${e.message}")
            }
        }
    }

    fun sendMessage(text: String): Flow<String> = flow {
        mutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Conversation not initialized")
            val contents = Contents.of(listOf(Content.Text(text)))
            conv.sendMessageAsync(contents).collect { message ->
                emit(message.toString())
            }
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithImage(text: String, bitmap: Bitmap): Flow<String> = flow {
        mutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Conversation not initialized")
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            
            val contents = Contents.of(listOf(Content.ImageBytes(byteArray), Content.Text(text)))
            conv.sendMessageAsync(contents).collect { message ->
                emit(message.toString())
            }
        }
    }.flowOn(Dispatchers.IO)
    
    // Kept audio capability active to preserve established multimodal logic implicitly requested yesterday
    fun sendMessageWithAudio(text: String, audioBytes: ByteArray): Flow<String> = flow {
        mutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Conversation not initialized")
            if (audioBytes.isEmpty()) {
                emit("Error: Received empty audio buffer.")
                return@withLock
            }
            try {
                val contents = Contents.of(listOf(Content.AudioBytes(audioBytes), Content.Text(text)))
                conv.sendMessageAsync(contents).collect { message ->
                    emit(message.toString())
                }
            } catch (e: Exception) {
                emit("\n[Audio processing failed: ${e.message}. The active model might not support audio input.]")
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        ConsoleLogger.d(TAG, "Entering generateOnce mutex lock")
        mutex.withLock {
            ConsoleLogger.d(TAG, "Acquired mutex lock. Engine check...")
            val eng = engine ?: throw IllegalStateException("Engine not initialized")
            var tempConv: Conversation? = null
            try {
                ConsoleLogger.d(TAG, "Creating temp conversation for generateOnce...")
                tempConv = eng.createConversation(ConversationConfig())
                ConsoleLogger.d(TAG, "Content pushing...")
                val sb = StringBuilder()
                val textContent = Contents.of(listOf(Content.Text(prompt)))
                tempConv.sendMessageAsync(textContent).collect { message ->
                    sb.append(message.toString())
                }
                ConsoleLogger.d(TAG, "generateOnce successfully collected ${sb.length} chars.")
                sb.toString()
            } finally {
                try {
                    ConsoleLogger.d(TAG, "Closing temp conversation.")
                    tempConv?.close()
                } catch (e: Exception) {
                    ConsoleLogger.e(TAG, "Error closing temp conversation", e)
                }
            }
        }
    }

    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                conversation?.close()
                val systemInstruction = memoryStore.buildSystemPrompt()
                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                    )
                )
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "Error resetting conversation", e)
            }
        }
    }

    fun close() {
        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Error closing engine/conversation", e)
        } finally {
            conversation = null
            engine = null
        }
    }
}

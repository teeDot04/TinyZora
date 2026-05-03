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
    private val userPrefs = com.telo.tinyzora.core.security.UserPreferences(context)
    private var activeMode: String = "text"

    suspend fun initialise(chatContext: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val visionB = if (activeMode == "image") Backend.GPU() else null
            val audioB = if (activeMode == "audio") Backend.CPU() else null

            val modelPath = userPrefs.getModelPath()
            val maxTokens = userPrefs.getMaxTokens()

            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = visionB,
                audioBackend = audioB,
                maxNumTokens = maxTokens,
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
            
            val samplerConfig = SamplerConfig(
                topK = userPrefs.getTopK(),
                topP = userPrefs.getTopP().toDouble(),
                temperature = userPrefs.getTemperature().toDouble()
            )

            conversation = engine?.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction))),
                    samplerConfig = samplerConfig
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
                
                val samplerConfig = SamplerConfig(
                    topK = userPrefs.getTopK(),
                    topP = userPrefs.getTopP().toDouble(),
                    temperature = userPrefs.getTemperature().toDouble()
                )

                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(listOf(Content.Text(systemInstruction))),
                        samplerConfig = samplerConfig
                    )
                )
                ConsoleLogger.d(TAG, "Conversation context wiped and rebuilt.")
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "Failed to reset conversation: ${e.message}")
            }
        }
    }

    fun sendMessage(text: String): Flow<InferenceResult> = flow {
        mutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Conversation not initialized")
            val contents = Contents.of(listOf(Content.Text(text)))
            
            kotlinx.coroutines.channels.Channel<InferenceResult>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { channel ->
                conv.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val thought = message.channels["thought"]
                        channel.trySend(InferenceResult(partialText = message.toString(), isDone = false, partialThinking = thought))
                    }
                    override fun onDone() {
                        channel.trySend(InferenceResult(partialText = "", isDone = true))
                        channel.close()
                    }
                    override fun onError(throwable: Throwable) {
                        channel.close(throwable)
                    }
                }, mapOf("enable_thinking" to "true"))
                
                for (result in channel) {
                    emit(result)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun sendMessageWithImage(text: String, bitmap: Bitmap): Flow<InferenceResult> = flow {
        mutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Conversation not initialized")
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            
            val contents = Contents.of(listOf(Content.ImageBytes(byteArray), Content.Text(text)))
            
            kotlinx.coroutines.channels.Channel<InferenceResult>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { channel ->
                conv.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val thought = message.channels["thought"]
                        channel.trySend(InferenceResult(partialText = message.toString(), isDone = false, partialThinking = thought))
                    }
                    override fun onDone() {
                        channel.trySend(InferenceResult(partialText = "", isDone = true))
                        channel.close()
                    }
                    override fun onError(throwable: Throwable) {
                        channel.close(throwable)
                    }
                }, mapOf("enable_thinking" to "true"))
                
                for (result in channel) {
                    emit(result)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
    
    // Kept audio capability active to preserve established multimodal logic implicitly requested yesterday
    fun sendMessageWithAudio(text: String, audioBytes: ByteArray): Flow<InferenceResult> = flow {
        mutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Conversation not initialized")
            if (audioBytes.isEmpty()) {
                emit(InferenceResult("Error: Received empty audio buffer.", true))
                return@withLock
            }
            try {
                val contents = Contents.of(listOf(Content.AudioBytes(audioBytes), Content.Text(text)))
                
                kotlinx.coroutines.channels.Channel<InferenceResult>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { channel ->
                    conv.sendMessageAsync(contents, object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val thought = message.channels["thought"]
                            channel.trySend(InferenceResult(partialText = message.toString(), isDone = false, partialThinking = thought))
                        }
                        override fun onDone() {
                            channel.trySend(InferenceResult(partialText = "", isDone = true))
                            channel.close()
                        }
                        override fun onError(throwable: Throwable) {
                            channel.close(throwable)
                        }
                    }, mapOf("enable_thinking" to "true"))
                    
                    for (result in channel) {
                        emit(result)
                    }
                }
            } catch (e: Exception) {
                emit(InferenceResult("\n[Audio processing failed: ${e.message}. The active model might not support audio input.]", true))
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val eng = engine ?: throw IllegalStateException("Engine not initialized")
            var tempConv: Conversation? = null
            try {
                tempConv = eng.createConversation(ConversationConfig())
                val sb = StringBuilder()
                val textContent = Contents.of(listOf(Content.Text(prompt)))
                
                kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { channel ->
                    tempConv.sendMessageAsync(textContent, object : MessageCallback {
                        override fun onMessage(message: Message) {
                            channel.trySend(message.toString())
                        }
                        override fun onDone() {
                            channel.close()
                        }
                        override fun onError(throwable: Throwable) {
                            channel.close(throwable)
                        }
                    })
                    for (token in channel) {
                        sb.append(token)
                    }
                }
                sb.toString()
            } finally {
                try {
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
                var systemInstruction = memoryStore.buildSystemPrompt()
                
                val samplerConfig = SamplerConfig(
                    topK = userPrefs.getTopK(),
                    topP = userPrefs.getTopP().toDouble(),
                    temperature = userPrefs.getTemperature().toDouble()
                )

                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(listOf(Content.Text(systemInstruction))),
                        samplerConfig = samplerConfig
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

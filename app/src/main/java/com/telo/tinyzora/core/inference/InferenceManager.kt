package com.telo.tinyzora.core.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.telo.tinyzora.core.memory.MemoryStore
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
    private val modelPath = "/sdcard/Projects/gemma3-2b-it-int4.litertlm"

    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,
                visionBackend = Backend.GPU,
                maxNumTokens = 4096,
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(config)
            engine?.initialize()

            val systemInstruction = memoryStore.buildSystemPrompt()
            
            conversation = engine?.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize engine", e)
            false
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
            val contents = Contents.of(listOf(Content.AudioBytes(audioBytes), Content.Text(text)))
            conv.sendMessageAsync(contents).collect { message ->
                emit(message.toString())
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(prompt: String): String = withContext(Dispatchers.IO) {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        var tempConv: Conversation? = null
        try {
            tempConv = eng.createConversation(ConversationConfig())
            val sb = StringBuilder()
            val textContent = Contents.of(listOf(Content.Text(prompt)))
            tempConv.sendMessageAsync(textContent).collect { message ->
                sb.append(message.toString())
            }
            sb.toString()
        } finally {
            try {
                tempConv?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing temp conversation", e)
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
                Log.e(TAG, "Error resetting conversation", e)
            }
        }
    }

    fun close() {
        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine/conversation", e)
        } finally {
            conversation = null
            engine = null
        }
    }
}

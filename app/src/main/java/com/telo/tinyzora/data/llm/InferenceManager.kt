package com.telo.tinyzora.data.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class InferenceManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: InferenceManager? = null

        fun getInstance(context: Context): InferenceManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: InferenceManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (engine == null) {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,
                visionBackend = Backend.GPU,
                audioBackend = Backend.CPU,
                maxNumTokens = null,
                cacheDir = context.getExternalFilesDir(null)?.absolutePath
            )

            engine = Engine(config)
            engine?.initialize()

            conversation = engine?.createConversation(ConversationConfig())
        }
    }

    fun generateResponse(prompt: String, imageBytes: ByteArray? = null, audioBytes: ByteArray? = null): Flow<String> {
        val conv = conversation
            ?: throw IllegalStateException("Zora is asleep. Call initialize() first.")
        
        val contents = mutableListOf<Content>()
        
        if (imageBytes != null) {
            contents.add(Content.ImageBytes(imageBytes))
        }
        
        if (audioBytes != null) {
            contents.add(Content.AudioBytes(audioBytes))
        }
        
        contents.add(Content.Text(prompt))
        
        val multiModalMessage = Message.of(*contents.toTypedArray())
        
        return conv.sendMessageAsync(multiModalMessage)
            .map { message -> 
                message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
            .flowOn(Dispatchers.IO)
    }
}

package com.telo.tinyzora.inference

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.CancellationException

object InferenceManager {

    // --- State ---
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState
    
    val isLoaded = AtomicBoolean(false)
    
    // --- Locking ---
    private val inferenceMutex = Mutex() 

    suspend fun loadModel(context: Context, modelPath: String, useGpu: Boolean = false) {
        val modelFile = File(modelPath)
        inferenceMutex.withLock {
            try {
                shutdownInternal()
                
                if (!modelFile.exists()) {
                    _errorState.tryEmit("Model file not found: $modelPath")
                    return@withLock
                }

                android.util.Log.d("InferenceManager", "Loading LiteRT Model: ${modelFile.name} | GPU: $useGpu")
                val backend = if (useGpu) Backend.GPU else Backend.CPU
                
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = 1024
                )

                // 2. Initialize Engine
                engine = Engine(config)
                // CRITICAL FIX: Must call initialize() before creating conversation
                // Note: Check if initialize is available in this version. 
                // Based on Gallery code, it is required.
                // If the dynamic version resolves to one where this is implicit, this might error,
                // but standard LiteRT flow requires it.
                // We'll trust the Gallery reference.
                // engine!!.initialize() // Wait, let's verify if the '+' version supports this.
                // The snippet showed 'engine.initialize()'. 
                // Let's assume it does.
                // If it fails to compile, I will see it.
                
                // Inspecting my previous compile error logs (Step 2149), I didn't see 'initialize' missing, 
                // but I didn't have it in the code then.
                
                // Let's try adding it.
                
                // Wait, some older versions of LLM Inference (MediaPipe) didn't have it. 
                // Engine (LiteRT) DOES.
                 
                // Actually, I can allow for both by checking or just calling it.
                // But since I'm editing text, I'll just add it.
                // If it's 1.0.0-beta/alpha, it likely has it.
                
                // Checking previous snippet 2092 again.
                // "val engine = Engine(engineConfig)"
                // "engine.initialize()"
                
                // Okay, adding it.
                
                // But wait, the previous build passed (Step 2212). 
                // If I didn't verify the code with `initialize` inside, I don't know if it compiles.
                // But logic dictates it's needed for runtime.
                // I'll add access check just in case but Kotlin doesn't support "tryCall".
                
                // Safe bet: Add it.
                // If it errors on build, I'll know I got the wrong version or API.
                
                // Re-reading 'InferenceManager.kt' content from Step 2167 to find insertion point.
                // Line 54: engine = Engine(config)
                // Line 55: conversation = engine!!.createConversation()
                
                engine!!.initialize()
                conversation = engine!!.createConversation()
                
                isLoaded.set(true)
                android.util.Log.d("InferenceManager", "LiteRT Engine Loaded Successfully.")
                _errorState.tryEmit("Engine Ready (${if(useGpu) "GPU" else "CPU"})")

            } catch (e: Throwable) {
                android.util.Log.e("InferenceManager", "LiteRT Load Failed", e)
                _errorState.tryEmit("Load Error: ${e.message}")
                shutdownInternal()
            }
        }
    }

    suspend fun clearContext() {
         try {
             inferenceMutex.withLock {
                 conversation = null // Release old ref
                 // Re-create conversation if engine exists
                 if (engine != null) {
                     conversation = engine!!.createConversation()
                     android.util.Log.d("InferenceManager", "Context Cleared (New Session).")
                 }
             }
         } catch (e: Exception) {
             android.util.Log.e("InferenceManager", "Failed to clear context", e)
         }
    }

    // Wrappers for backward compatibility
    suspend fun generateResponse(prompt: String): String {
        val sb = StringBuilder()
        generateResponseStreaming(prompt).collect { sb.append(it) }
        return sb.toString()
    }

    suspend fun generateResponseWithImages(prompt: String, images: List<Bitmap>): String {
        // Updated to return String for TinyZora.kt compatibility
        val sb = StringBuilder()
        generateResponseStreaming(prompt, images).collect { sb.append(it) }
        return sb.toString()
    }

    fun generateResponseStreaming(
        prompt: String, 
        images: List<Bitmap> = emptyList(),
        audioBytes: List<ByteArray> = emptyList()
    ): Flow<String> = callbackFlow {
        inferenceMutex.withLock {
            val currentConversation = conversation
            if (currentConversation == null) {
                trySend("Error: Engine not loaded.")
                close()
                return@withLock
            }

            try {
                 if (images.isNotEmpty()) {
                     trySend("[System: LiteRT Images not fully implemented yet, sending text only.]\n")
                 }

                 // Prepare Content
                 val content = Content.Text(prompt)
                 val contents = Contents.of(content)

                 // API: sendMessageAsync with Callback
                 currentConversation.sendMessageAsync(contents, object : MessageCallback {
                     override fun onMessage(message: Message) {
                         trySend(message.toString())
                     }
                     override fun onDone() {
                         close()
                     }
                     override fun onError(t: Throwable) {
                         if (t !is CancellationException) {
                             trySend("Error: ${t.message}")
                         }
                         close()
                     }
                 })
                 
                 awaitClose { }

            } catch (e: Exception) {
                android.util.Log.e("InferenceManager", "Generation Error", e)
                trySend("Error: ${e.message}")
                close()
            }
        }
    }

    fun shutdown() {
        try {
             shutdownInternal()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shutdownInternal() {
        try {
            conversation = null 
             if (engine is AutoCloseable) {
                 (engine as AutoCloseable).close()
             }
            engine = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        isLoaded.set(false)
        android.util.Log.d("InferenceManager", "Engine Shutdown.")
    }
    
    fun isModelLoaded(): Boolean = isLoaded.get()
}

package com.telo.tinyzora.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.telo.tinyzora.core.inference.InferenceManager
import com.telo.tinyzora.core.memory.MemoryConsolidator
import com.telo.tinyzora.core.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: String, 
    val text: String, 
    val bitmap: Bitmap? = null,
    val audio: ByteArray? = null
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val isEngineReady: Boolean = false,
    val engineError: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryStore = MemoryStore(application)
    private val inferenceManager = InferenceManager(application, memoryStore)
    private val memoryConsolidator = MemoryConsolidator(memoryStore)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val sessionTranscript = mutableListOf<Pair<String, String>>()
    
    // Preserved attachment states alongside UiState to prevent Compose crash
    private val _attachedImage = MutableStateFlow<Bitmap?>(null)
    val attachedImage: StateFlow<Bitmap?> = _attachedImage.asStateFlow()

    private val _attachedAudio = MutableStateFlow<ByteArray?>(null)
    val attachedAudio: StateFlow<ByteArray?> = _attachedAudio.asStateFlow()

    fun attachImage(bitmap: Bitmap?) {
        _attachedImage.value = bitmap
    }

    fun attachAudio(audio: ByteArray?) {
        _attachedAudio.value = audio
    }

    init {
        initialiseEngine()
    }

    private fun initialiseEngine() {
        viewModelScope.launch {
            val success = inferenceManager.initialise()
            _uiState.value = _uiState.value.copy(
                isEngineReady = success,
                engineError = !success
            )
        }
    }

    fun sendMessage(text: String) {
        val img = _attachedImage.value
        val aud = _attachedAudio.value

        if ((text.isBlank() && img == null && aud == null) || _uiState.value.isGenerating) return

        _attachedImage.value = null
        _attachedAudio.value = null

        val userMessage = ChatMessage(role = "user", text = text, bitmap = img, audio = aud)
        val currentMessages = _uiState.value.messages.toMutableList()
        currentMessages.add(userMessage)
        
        sessionTranscript.add("user" to text)

        _uiState.value = _uiState.value.copy(
            messages = currentMessages,
            isGenerating = true,
            streamingText = ""
        )

        viewModelScope.launch {
            val responseBuilder = java.lang.StringBuilder()
            try {
                val flowProxy = if (img != null) {
                    inferenceManager.sendMessageWithImage(text, img)
                } else if (aud != null) {
                    inferenceManager.sendMessageWithAudio(text, aud)
                } else {
                    inferenceManager.sendMessage(text)
                }
                
                flowProxy.collect { token ->
                    responseBuilder.append(token)
                    _uiState.value = _uiState.value.copy(streamingText = responseBuilder.toString())
                }
            } catch (e: Exception) {
                responseBuilder.append("\n[Error: ${e.message}]")
                _uiState.value = _uiState.value.copy(streamingText = responseBuilder.toString())
            } finally {
                val zoraMessage = ChatMessage(role = "zora", text = responseBuilder.toString())
                val updatedMessages = _uiState.value.messages.toMutableList()
                updatedMessages.add(zoraMessage)
                
                sessionTranscript.add("zora" to responseBuilder.toString())

                _uiState.value = _uiState.value.copy(
                    messages = updatedMessages,
                    isGenerating = false,
                    streamingText = ""
                )
            }
        }
    }

    fun consolidateMemory() {
        if (sessionTranscript.isEmpty()) return
        viewModelScope.launch {
            val transcriptCopy = sessionTranscript.toList()
            sessionTranscript.clear()
            memoryConsolidator.consolidate(transcriptCopy, inferenceManager::generateOnce)
        }
    }

    override fun onCleared() {
        consolidateMemory()
        inferenceManager.close()
        super.onCleared()
    }
}

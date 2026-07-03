package com.telo.tinyzora.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.telo.tinyzora.core.chat.ChatRepository
import com.telo.tinyzora.core.inference.InferenceManager
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.util.ConsoleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@Immutable
data class AudioHolder(val data: ByteArray) {
    override fun equals(other: Any?) = other is AudioHolder && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
}

@Immutable
data class ImageHolder(val uriString: String, val id: String = UUID.randomUUID().toString()) {
    override fun equals(other: Any?) = other is ImageHolder && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val thinking: String? = null,
    val isThinkingDone: Boolean = false,
    val image: ImageHolder? = null,
    val audio: AudioHolder? = null,
    val audioAmplitudes: List<Float> = emptyList(),
    val documentName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isEngineReady: Boolean = false,
    val engineError: Boolean = false
)

data class StreamingState(
    val streamingText: String = "",
    val streamingThinking: String? = null,
    val isThinking: Boolean = false,
    val isGenerating: Boolean = false
)

// Helper for UI grouping
data class GroupedMessage(val dateLabel: String, val messages: List<ChatMessage>)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryStore = MemoryStore(application)
    private val inferenceManager = InferenceManager(application, memoryStore)
    private val chatRepo = ChatRepository(application)
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Optimized grouped messages to prevent UI jank
    private val _groupedMessages = MutableStateFlow<List<GroupedMessage>>(emptyList())
    val groupedMessages: StateFlow<List<GroupedMessage>> = _groupedMessages.asStateFlow()

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private var generationJob: kotlinx.coroutines.Job? = null
    private val sessionTranscript = CopyOnWriteArrayList<Pair<String, String>>()

    private val _attachedImage = MutableStateFlow<Uri?>(null)
    val attachedImage: StateFlow<Uri?> = _attachedImage.asStateFlow()
    private val _attachedAudio = MutableStateFlow<ByteArray?>(null)
    val attachedAudio: StateFlow<ByteArray?> = _attachedAudio.asStateFlow()
    private val _attachedDocumentText = MutableStateFlow<String?>(null)
    val attachedDocumentText: StateFlow<String?> = _attachedDocumentText.asStateFlow()
    private val _attachedDocumentName = MutableStateFlow<String?>(null)
    val attachedDocumentName: StateFlow<String?> = _attachedDocumentName.asStateFlow()

    fun attachImage(uri: Uri?) { _attachedImage.value = uri }
    fun attachAudio(audio: ByteArray?) { _attachedAudio.value = audio }
    fun clearDocument() { _attachedDocumentText.value = null; _attachedDocumentName.value = null }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val history = chatRepo.loadMessages()
            withContext(Dispatchers.Main) {
                if (history.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(messages = history.reversed())
                    updateGroupedMessages(history.reversed())
                }
            }
            val success = inferenceManager.initialise()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isEngineReady = success, engineError = !success)
            }
        }
    }

    fun sendMessage(text: String) {
        val imgUri = _attachedImage.value
        val aud = _attachedAudio.value
        val doc = _attachedDocumentText.value
        
        if ((text.isBlank() && imgUri == null && aud == null && doc == null) || _streamingState.value.isGenerating) return

        val userMessage = ChatMessage(
            role = "user", text = text,
            image = if (imgUri != null) ImageHolder(imgUri.toString()) else null,
            audio = if (aud != null) AudioHolder(aud) else null,
            documentName = _attachedDocumentName.value
        )

        val newMessages = listOf(userMessage) + _uiState.value.messages
        _uiState.value = _uiState.value.copy(messages = newMessages)
        updateGroupedMessages(newMessages)
        
        _streamingState.value = StreamingState(isGenerating = true, streamingText = "")
        _attachedImage.value = null; _attachedAudio.value = null
        _attachedDocumentText.value = null; _attachedDocumentName.value = null

        sessionTranscript.add("user" to text)
        generationJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { chatRepo.addMessage(userMessage) }
                generateResponse(text, imgUri, aud)
            } finally {
                _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
            }
        }
    }

    private suspend fun generateResponse(prompt: String, imgUri: Uri?, aud: ByteArray?) {
        val responseBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()

        try {
            val flowProxy = inferenceManager.sendMessage(prompt, "") // History handled internally by new engine
            
            flowProxy.collect { result ->
                if (result.isDone) return@collect
                if (result.partialThinking != null) {
                    thinkingBuilder.append(result.partialThinking)
                    _streamingState.value = _streamingState.value.copy(streamingThinking = thinkingBuilder.toString(), isThinking = true)
                }
                if (result.partialText.isNotEmpty()) {
                    responseBuilder.append(result.partialText)
                    _streamingState.value = _streamingState.value.copy(
                        streamingText = responseBuilder.toString().trimEnd(),
                        isThinking = false, isGenerating = true
                    )
                }
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                ConsoleLogger.e("ChatViewModel", "Inference Crash", e)
                responseBuilder.append("\n[System Error: Engine halted unexpectedly.]")
                _streamingState.value = _streamingState.value.copy(streamingText = responseBuilder.toString())
            } else throw e
        } finally {
            val displayText = responseBuilder.toString().trimEnd()
            if (displayText.isNotBlank() || thinkingBuilder.isNotEmpty()) {
                val finalMessage = ChatMessage(
                    role = "zora", text = displayText,
                    thinking = if (thinkingBuilder.isNotEmpty()) thinkingBuilder.toString() else null,
                    isThinkingDone = true
                )
                val newMessages = listOf(finalMessage) + _uiState.value.messages
                _uiState.value = _uiState.value.copy(messages = newMessages)
                updateGroupedMessages(newMessages)
                withContext(Dispatchers.IO) { chatRepo.addMessage(finalMessage) }
                sessionTranscript.add("zora" to displayText)
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.deleteMessagePair(id)
            val history = chatRepo.loadMessages()
            withContext(Dispatchers.Main) {
                val reversed = history.reversed()
                _uiState.value = _uiState.value.copy(messages = reversed)
                updateGroupedMessages(reversed)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        inferenceManager.close()
    }

    // Optimized grouping logic
    private fun updateGroupedMessages(messages: List<ChatMessage>) {
        val today = java.time.LocalDate.now()
        val grouped = messages.groupBy { msg ->
            val date = java.time.Instant.ofEpochMilli(msg.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            when {
                date == today -> "Today"
                date == today.minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
            }
        }.map { (label, msgs) -> GroupedMessage(label, msgs) }
        _groupedMessages.value = grouped
    }
}

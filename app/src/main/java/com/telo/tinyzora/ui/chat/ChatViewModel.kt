package com.telo.tinyzora.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import com.telo.tinyzora.core.chat.ChatRepository
import com.telo.tinyzora.core.inference.InferenceManager
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.util.ConsoleLogger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException

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

@Stable
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

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private val MEMORY_REGEX = Regex("""```memory\s*([\s\S]*?)```""", RegexOption.MULTILINE)
        private val CLEAN_TEXT_REGEX = Regex("(?<!\\\\)\\\\([a-zA-Z]{2,})(?:\\{([^}]+)\\})?")
    }

    private val memoryStore = MemoryStore(application)
    private val inferenceManager = InferenceManager(application, memoryStore)
    private val chatRepo = ChatRepository(application)
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

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
    
    private val _pendingAudioAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val pendingAudioAmplitudes: StateFlow<List<Float>> = _pendingAudioAmplitudes.asStateFlow()

    fun attachImage(uri: Uri?) { _attachedImage.value = uri }
    fun attachAudio(audio: ByteArray?) { _attachedAudio.value = audio }
    fun setAudioAmplitudeSnapshot(amplitudes: List<Float>) { _pendingAudioAmplitudes.value = amplitudes }

    fun attachFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = com.telo.tinyzora.util.DocumentParser.parseFromUri(getApplication(), uri)
                val cursor = getApplication<Application>().contentResolver
                    .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                val name = cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment ?: "Document"
                
                withContext(Dispatchers.Main) {
                    _attachedDocumentText.value = text
                    _attachedDocumentName.value = name
                }
            } catch (e: Exception) {
                ConsoleLogger.e("ChatViewModel", "File read error", e)
            }
        }
    }

    fun clearDocument() {
        _attachedDocumentText.value = null
        _attachedDocumentName.value = null
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val history = chatRepo.loadMessages()
            withContext(Dispatchers.Main) {
                if (history.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(messages = history.reversed())
                }
            }
            val success = inferenceManager.initialise(buildRecentHistoryContext())
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    isEngineReady = success,
                    engineError = !success
                )
            }
        }
    }

    fun sendMessage(text: String) {
        val imgUri = _attachedImage.value
        val aud = _attachedAudio.value
        val doc = _attachedDocumentText.value
        
        if ((text.isBlank() && imgUri == null && aud == null && doc == null) || _streamingState.value.isGenerating) return

        val userMessage = ChatMessage(
            role = "user",
            text = text,
            image = if (imgUri != null) ImageHolder(imgUri.toString()) else null,
            audio = if (aud != null) AudioHolder(aud) else null,
            audioAmplitudes = if (aud != null) _pendingAudioAmplitudes.value else emptyList(),
            documentName = _attachedDocumentName.value
        )

        _uiState.value = _uiState.value.copy(messages = listOf(userMessage) + _uiState.value.messages)
        _streamingState.value = StreamingState(isGenerating = true, streamingText = "")
        
        _attachedImage.value = null
        _attachedAudio.value = null
        _pendingAudioAmplitudes.value = emptyList()
        _attachedDocumentText.value = null
        _attachedDocumentName.value = null

        val timeStr = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val docContext = if (doc != null) "\n\n[Attached File Content: $doc]\n" else ""
        val engineText = "(Time: $timeStr) $text$docContext"
        
        sessionTranscript.add("user" to text)

        generationJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { chatRepo.addMessage(userMessage) }
                generateResponse(engineText, imgUri, aud, userMessage)
            } finally {
                _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
            }
        }
    }

    private suspend fun generateResponse(
        prompt: String,
        imgUri: Uri?,
        aud: ByteArray?,
        userMessage: ChatMessage
    ) {
        val responseBuilder = java.lang.StringBuilder()
        val thinkingBuilder = java.lang.StringBuilder()
        val currentContext = buildRecentHistoryContext()

        try {
            val flowProxy = if (imgUri != null) {
                inferenceManager.ensureModeIs("image", currentContext)
                // TODO: Update InferenceManager.sendMessageWithImage to accept the history parameter.
                // inferenceManager.sendMessageWithImage(prompt, imgUri, currentContext)
                throw NotImplementedError("Update InferenceManager to accept Uri")
            } else if (aud != null) {
                inferenceManager.ensureModeIs("audio", currentContext)
                inferenceManager.sendMessageWithAudio(prompt, aud, currentContext)
            } else {
                inferenceManager.ensureModeIs("text", currentContext)
                inferenceManager.sendMessage(prompt, currentContext)
            }

            withContext(Dispatchers.Default) {
                var tokenCount = 0
                flowProxy.collect { result ->
                    if (result.isDone) return@collect

                    if (result.partialThinking != null) {
                        thinkingBuilder.append(result.partialThinking)
                        withContext(Dispatchers.Main) {
                            _streamingState.value = _streamingState.value.copy(
                                streamingThinking = thinkingBuilder.toString(),
                                isThinking = true
                            )
                        }
                    }

                    if (result.partialText.isNotEmpty()) {
                        responseBuilder.append(result.partialText)
                        tokenCount++
                        if (tokenCount % 8 == 0) {
                            val streamDisplay = responseBuilder.toString().replace(MEMORY_REGEX, "").trimEnd()
                            withContext(Dispatchers.Main) {
                                _streamingState.value = _streamingState.value.copy(
                                    streamingText = streamDisplay,
                                    isThinking = false,
                                    isGenerating = true
                                )
                            }
                        } else {
                            val currentText = responseBuilder.toString().trimEnd()
                            withContext(Dispatchers.Main) {
                                _streamingState.value = _streamingState.value.copy(
                                    streamingText = currentText,
                                    isThinking = false,
                                    isGenerating = true
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                ConsoleLogger.e("ChatViewModel", "Inference Crash", e)
                responseBuilder.append("\n[System Error: Engine halted unexpectedly.]")
                _streamingState.value = _streamingState.value.copy(streamingText = responseBuilder.toString())
            } else throw e
        } finally {
            val rawText = responseBuilder.toString()
            val memoryMatch = MEMORY_REGEX.find(rawText)
            val displayText = rawText.replace(MEMORY_REGEX, "").trimEnd()

            if (memoryMatch != null) {
                val jsonStr = memoryMatch.groupValues[1].trim()
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val now = java.time.ZonedDateTime.now(ZoneId.systemDefault())
                        val isoFmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        val rawEntries = json.decodeFromString<List<com.telo.tinyzora.core.memory.MemoryEntry>>(jsonStr)
                        val entries = rawEntries.map { rawEntry ->
                            val entry = if (rawEntry.date.isEmpty()) rawEntry.copy(date = now.toLocalDate().toString()) else rawEntry
                            if (entry.type == "reminder" && entry.due?.startsWith("+") == true) {
                                val clean = entry.due.trim()
                                val amount = clean.substring(1, clean.length - 1).toLongOrNull() ?: 0L
                                val abs = when (clean.last().lowercaseChar()) {
                                    's' -> now.plusSeconds(amount)
                                    'm' -> now.plusMinutes(amount)
                                    'h' -> now.plusHours(amount)
                                    'd' -> now.plusDays(amount)
                                    else -> now
                                }
                                entry.copy(due = abs.format(isoFmt))
                            } else entry
                        }
                        val trivialPhrases = setOf("goodnight", "bye", "hello", "hi", "ok", "thanks", "yes", "no")
                        val qualityEntries = entries.filter { entry ->
                            val normalized = entry.content.trim().lowercase().trimEnd('.')
                            val wordCount = normalized.split(Regex("\\s+")).filter { it.length > 2 }.size
                            val isTrivial = trivialPhrases.contains(normalized)
                            val hasContent = wordCount >= 4 || entry.type == "reminder"
                            !isTrivial && hasContent
                        }
                        if (qualityEntries.isNotEmpty()) {
                            val store = com.telo.tinyzora.core.memory.MemoryStore(getApplication())
                            store.merge(qualityEntries)
                            com.telo.tinyzora.core.notifications.ReminderScheduler.scheduleAllReminders(getApplication(), store)
                        }
                    } catch (e: Exception) {
                        ConsoleLogger.e("ZoraMemory", "Failed to parse memory", e)
                    }
                }
            }

            if (displayText.isNotBlank() || thinkingBuilder.isNotEmpty()) {
                val finalMessage = ChatMessage(
                    role = "zora",
                    text = displayText,
                    thinking = if (thinkingBuilder.isNotEmpty()) thinkingBuilder.toString() else null,
                    isThinkingDone = true
                )
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(messages = listOf(finalMessage) + _uiState.value.messages)
                }
                viewModelScope.launch(Dispatchers.IO) { chatRepo.addMessage(finalMessage) }
                sessionTranscript.add("zora" to displayText)
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun consolidateMemory() {
        if (sessionTranscript.isEmpty()) return
        val transcriptSnapshot = sessionTranscript.toList()
        sessionTranscript.clear()
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val file = File(getApplication<Application>().filesDir, "pending_transcript.json")
                val jsonStr = Json.encodeToString(
                    ListSerializer(PairSerializer(String.serializer(), String.serializer())), 
                    transcriptSnapshot
                )
                file.writeText(jsonStr)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to write transcript", e)
            }
        }
    }

    fun resetStreamingState() {
        if (generationJob?.isActive == true) return
        _streamingState.value = StreamingState()
    }

    fun injectReminderContext(reminder: String) {
        viewModelScope.launch {
            val prompt = "The user just tapped a notification for this reminder: \"$reminder\". Acknowledge it naturally and ask how you can help."
            sendMessage(prompt)
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.deleteMessagePair(id)
            val history = chatRepo.loadMessages()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(messages = history.reversed())
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        GlobalScope.launch(Dispatchers.IO) {
            consolidateMemory()
            inferenceManager.close()
        }
        super.onCleared()
    }

    private fun buildRecentHistoryContext(): String {
        val msgs = _uiState.value.messages.take(10).reversed()
        if (msgs.isEmpty()) return ""
        return buildString {
            msgs.forEach { msg ->
                if (msg.role == "user") {
                    appendLine("User: ${msg.text}")
                    if (msg.image != null) appendLine("[User Attached Image]")
                    if (msg.audio != null) appendLine("[User Attached Audio]")
                } else if (msg.role == "zora") {
                    val cleanText = msg.text.removePrefix("Zora:").removePrefix("Zora").trimStart()
                        .replace(CLEAN_TEXT_REGEX) { match ->
                            val base = match.groups[1]?.value ?: ""
                            val arg = match.groups[2]?.value
                            if (arg != null) "$arg-$base" else base
                        }
                    appendLine("Assistant: $cleanText")
                }
            }
        }
    }
}

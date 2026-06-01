package com.telo.tinyzora.ui.chat

import com.telo.tinyzora.util.ConsoleLogger
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Stable
import com.telo.tinyzora.core.chat.ChatRepository
import com.telo.tinyzora.core.inference.InferenceManager
import com.telo.tinyzora.core.memory.MemoryStore
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
import kotlinx.serialization.json.Json
import java.util.UUID

import androidx.compose.runtime.Immutable

/**
 * Wraps a ByteArray with content-based equality so Jetpack Compose's stability
 * system can correctly determine that a ChatMessage has not changed between
 * recompositions. Without this, ByteArray's referential equality check causes
 * the entire LazyColumn to recompose on every streaming token.
 */
@Immutable
data class AudioHolder(val data: ByteArray) {
    override fun equals(other: Any?) = other is AudioHolder && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHash()
}

private fun ByteArray.contentHash(): Int = this.fold(1) { acc, b -> 31 * acc + b }

@Stable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val thinking: String? = null,
    val isThinkingDone: Boolean = false,
    val bitmap: Bitmap? = null,
    val audio: AudioHolder? = null,
    val audioAmplitudes: List<Float> = emptyList(),
    val documentName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Only holds the persistent message list and engine state. Streaming state is separate. */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isEngineReady: Boolean = false,
    val engineError: Boolean = false
)

/** Holds real-time streaming state in its own flow so token emissions do NOT
 *  invalidate the messages list reference and force the whole LazyColumn to diff. */
data class StreamingState(
    val streamingText: String = "",
    val streamingThinking: String? = null,
    val isThinking: Boolean = false,
    val isGenerating: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryStore = MemoryStore(application)
    private val inferenceManager = InferenceManager(application, memoryStore)
    private val chatRepo = ChatRepository(application)
    
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Separated so that per-token emissions don't trigger LazyColumn diffing on the full message list
    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private var generationJob: kotlinx.coroutines.Job? = null


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

    private val _attachedDocumentText = MutableStateFlow<String?>(null)
    val attachedDocumentText: StateFlow<String?> = _attachedDocumentText.asStateFlow()

    private val _attachedDocumentName = MutableStateFlow<String?>(null)
    val attachedDocumentName: StateFlow<String?> = _attachedDocumentName.asStateFlow()

    fun attachFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val text = com.telo.tinyzora.util.DocumentParser.parseFromUri(getApplication(), uri)
            _attachedDocumentText.value = text
            // Extract display name from URI
            val cursor = getApplication<android.app.Application>().contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            val name = cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "Document"
            _attachedDocumentName.value = name
        }
    }

    fun clearDocument() {
        _attachedDocumentText.value = null
        _attachedDocumentName.value = null
    }

    private val _pendingAudioAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val pendingAudioAmplitudes: StateFlow<List<Float>> = _pendingAudioAmplitudes.asStateFlow()

    fun setAudioAmplitudeSnapshot(amplitudes: List<Float>) {
        _pendingAudioAmplitudes.value = amplitudes
    }

    init {
        viewModelScope.launch {
            val history = chatRepo.loadMessages()
            if (history.isNotEmpty()) {
                // Store in display order: [newest, ..., oldest]
                _uiState.value = _uiState.value.copy(messages = history.reversed())
            } else {
                // Intro message
                val intro = ChatMessage(role = "zora", text = "Hi, I'm tinyZora! Let's chat.")
                _uiState.value = _uiState.value.copy(messages = listOf(intro))
                chatRepo.addMessage(intro)
            }
            
            ConsoleLogger.d("ChatViewModel", "init block running")
            val success = inferenceManager.initialise(buildRecentHistoryContext())
            _uiState.value = _uiState.value.copy(
                isEngineReady = success,
                engineError = !success
            )
        }
    }

    fun sendMessage(text: String) {
        val img = _attachedImage.value
        val aud = _attachedAudio.value

        val doc = _attachedDocumentText.value
        if ((text.isBlank() && img == null && aud == null && doc == null) || _streamingState.value.isGenerating) return

        
        val userMessage = ChatMessage(
            role = "user",
            text = text,
            bitmap = img,
            audio = if (aud != null) AudioHolder(aud) else null,
            audioAmplitudes = if (aud != null) _pendingAudioAmplitudes.value else emptyList(),
            documentName = _attachedDocumentName.value
        )
        
        _uiState.value = _uiState.value.copy(
            messages = listOf(userMessage) + _uiState.value.messages
        )
        _streamingState.value = StreamingState(isGenerating = true, streamingText = "")
        _attachedImage.value = null
        _attachedAudio.value = null
        _pendingAudioAmplitudes.value = emptyList()

        val nairobiZone = ZoneId.of("Africa/Nairobi")
        val now = ZonedDateTime.now(nairobiZone)
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        
        val docContext = if (_attachedDocumentText.value != null) {
            "\n\n[Attached File Content: ${_attachedDocumentText.value}]\n"
        } else {
            ""
        }
        val engineText = "(Time: $timeStr) $text$docContext"
        _attachedDocumentText.value = null // clear after attaching

        sessionTranscript.add("user" to text)
        generationJob = viewModelScope.launch {
            chatRepo.addMessage(userMessage)
            generateResponse(engineText, img, aud, userMessage)
        }
    }

    private suspend fun generateResponse(
        prompt: String,
        img: Bitmap?,
        aud: ByteArray?,
        userMessage: ChatMessage
    ) {
        val responseBuilder = java.lang.StringBuilder()
        val thinkingBuilder = java.lang.StringBuilder()
        val currentContext = buildRecentHistoryContext()
        val memoryRegex = Regex("""```memory\s*([\s\S]*?)```""", RegexOption.MULTILINE)
        
        try {
            val flowProxy = if (img != null) {
                inferenceManager.ensureModeIs("image", currentContext)
                inferenceManager.sendMessageWithImage(prompt, img)
            } else if (aud != null) {
                inferenceManager.ensureModeIs("audio", currentContext)
                inferenceManager.sendMessageWithAudio(prompt, aud)
            } else {
                inferenceManager.ensureModeIs("text", currentContext)
                inferenceManager.sendMessage(prompt)
            }
            
            var tokenCount = 0
            flowProxy.collect { result ->
                if (result.isDone) return@collect

                if (result.partialThinking != null) {
                    thinkingBuilder.append(result.partialThinking)
                    _streamingState.value = _streamingState.value.copy(
                        streamingThinking = thinkingBuilder.toString(),
                        isThinking = true
                    )
                }

                if (result.partialText.isNotEmpty()) {
                    responseBuilder.append(result.partialText)
                    tokenCount++
                    // Only strip the hidden memory block regex every 8 tokens to avoid
                    // running a full-string regex sweep on every single token arrival.
                    if (tokenCount % 8 == 0) {
                        val streamDisplay = responseBuilder.toString().replace(memoryRegex, "").trimEnd()
                        _streamingState.value = _streamingState.value.copy(
                            streamingText = streamDisplay,
                            isThinking = false
                        )
                    } else {
                        _streamingState.value = _streamingState.value.copy(
                            streamingText = responseBuilder.toString().trimEnd(),
                            isThinking = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                responseBuilder.append("\n[Error: ${e.message}]")
                _streamingState.value = _streamingState.value.copy(streamingText = responseBuilder.toString())
            } else {
                _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false) 
                throw e
            }
        } finally {
            val rawText = responseBuilder.toString()

            // ── Strip hidden memory block ─────────────────────────────────
            val memoryMatch = memoryRegex.find(rawText)
            val displayText = rawText.replace(memoryRegex, "").trimEnd()

            if (memoryMatch != null) {
                val jsonStr = memoryMatch.groupValues[1].trim()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Nairobi"))
                        val isoFmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
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
                        // Quality guard: filter trivial/social entries before saving
                        val trivialPhrases = setOf(
                            "goodnight", "good night", "goodbye", "good bye", "bye", "hello",
                            "hi", "hey", "ok", "okay", "sure", "thanks", "thank you",
                            "good morning", "good evening", "good afternoon", "see you",
                            "later", "yes", "no", "alright", "cool", "nice", "great",
                            "sounds good", "got it", "noted"
                        )
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
                            com.telo.tinyzora.core.notifications.ReminderScheduler
                                .scheduleAllReminders(getApplication(), store)
                            ConsoleLogger.d("ZoraMemory", "Saved ${qualityEntries.size}/${entries.size} entries (${entries.size - qualityEntries.size} filtered as trivial).")
                        }
                    } catch (e: Exception) {
                        ConsoleLogger.e("ZoraMemory", "Failed to parse inline memory block: $jsonStr", e)
                    }
                }
            }
            // ─────────────────────────────────────────────────────────────

            if (displayText.isNotBlank() || thinkingBuilder.isNotEmpty()) {
                val finalMessage = ChatMessage(
                    role = "zora", 
                    text = displayText,
                    thinking = if (thinkingBuilder.isNotEmpty()) thinkingBuilder.toString() else null,
                    isThinkingDone = true
                )
                val updatedMessages = listOf(finalMessage) + _uiState.value.messages
                _uiState.value = _uiState.value.copy(
                    messages = updatedMessages
                )
                _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
                chatRepo.addMessage(finalMessage)
                sessionTranscript.add("zora" to displayText)
            } else {
                // Memory-only reply: no bubble, but clear generating state
                _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        val currentMessages = _uiState.value.messages
        val streamingText = _streamingState.value.streamingText
        val streamingThinking = _streamingState.value.streamingThinking
        if (streamingText.isNotBlank() || streamingThinking != null) {
            val partial = ChatMessage(
                role = "zora", 
                text = streamingText,
                thinking = streamingThinking,
                isThinkingDone = true
            )
            viewModelScope.launch {
                chatRepo.addMessage(partial)
            }
            _uiState.value = _uiState.value.copy(
                messages = listOf(partial) + currentMessages
            )
        }
        _streamingState.value = StreamingState(isGenerating = false, streamingText = "", streamingThinking = null, isThinking = false)
    }

    fun consolidateMemory() {
        if (sessionTranscript.isEmpty()) return

        // Write synchronously — must complete before Android
        // can kill the process on app close
        try {
            val file = File(
                getApplication<Application>().filesDir,
                "pending_transcript.json"
            )
            val json = Json.encodeToString(
                ListSerializer(
                    PairSerializer(String.serializer(), String.serializer())
                ),
                sessionTranscript.toList()
            )
            file.writeText(json)
            sessionTranscript.clear()
        } catch (e: Exception) {
            // Log but never crash — this is best-effort
            Log.e("ChatViewModel", "Failed to write transcript", e)
        }
    }

    fun injectReminderContext(reminder: String) {
        viewModelScope.launch {
            val prompt = "The user just tapped a notification for this reminder: " +
                         "\"$reminder\". Acknowledge it naturally and ask how you can help."
            sendMessage(prompt)
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch {
            chatRepo.deleteMessagePair(id)
            val history = chatRepo.loadMessages()
            _uiState.value = _uiState.value.copy(messages = history.reversed())
        }
    }

    override fun onCleared() {
        consolidateMemory()
        inferenceManager.close()
        super.onCleared()
    }

    private fun buildRecentHistoryContext(): String {
        val msgs: List<ChatMessage> = _uiState.value.messages.take(10).reversed()
        if (msgs.isEmpty()) return ""
        return buildString {
            msgs.forEach { msg: ChatMessage ->
                if (msg.role == "user") {
                    appendLine("User: ${msg.text}")
                    if (msg.bitmap != null) appendLine("[User Attached Image]")
                    if (msg.audio != null) appendLine("[User Attached Audio]")
                } else if (msg.role == "zora") {
                    var cleanText = msg.text.removePrefix("Zora:").removePrefix("Zora").trimStart()
                    cleanText = cleanText.replace(Regex("(?<!\\\\)\\\\([a-zA-Z]{2,})(?:\\{([^}]+)\\})?")) { match ->
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

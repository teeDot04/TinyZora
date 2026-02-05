package com.telo.tinyzora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
// import com.telo.tinyzora.inference.InferenceModel // Removed
import com.telo.tinyzora.inference.InferenceManager
import com.telo.tinyzora.core.TinyZora
import com.telo.tinyzora.core.LifeChapter
import com.telo.tinyzora.core.ChatMode
import com.telo.tinyzora.data.MemoryDatabase
import com.telo.tinyzora.data.MemorySeeder
import com.telo.tinyzora.bridge.TinyBridge
import com.telo.tinyzora.data.ChatSession
import com.telo.tinyzora.core.PromptBuilder
import com.telo.tinyzora.core.ThermalMemory
import org.json.JSONObject
// import com.telo.tinyzora.data.MemoryEntity // Removed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.provider.OpenableColumns

data class ModelInfo(val name: String, val isMultimodal: Boolean)

class TinyViewModel(application: Application) : AndroidViewModel(application) {

    // Dependencies
    // private val inferenceModel = InferenceModel(application) // Removed
    private val bridge = TinyBridge(application)
    private val tinyZora = TinyZora(application, bridge)
    private val memoryDao = MemoryDatabase.getDatabase(application).memoryDao()
    private val chatDao = com.telo.tinyzora.data.ChatDatabase.getDatabase(application).chatDao()

    // UI State
    // UI State
    private val _uiState = MutableStateFlow("Idle")
    val uiState = _uiState.asStateFlow()
    // pendingImages moved to specific section below
    
    // Active Conversation State
    private var currentSessionId: Long? = null
    private var lastInteractionTime: Long = System.currentTimeMillis() // Idle tracking
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    val availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    
    // Session List (from DB)
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()
    
    val appStatus = MutableStateFlow(AppStatus.ACTIVE)
    val isGenerating = MutableStateFlow(false)
    val currentMode = MutableStateFlow(ChatMode.FAST_CHAT) // Default
    val systemLogs = MutableStateFlow<List<String>>(emptyList())
    private var generationJob: kotlinx.coroutines.Job? = null
    
    // CONTEXT MANAGEMENT: Prevents double-injection of system prompt
    private var isContextFresh = true

    init {
        viewModelScope.launch {
            // Auto-Delete Expired Reminders
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val nowStr = sdf.format(java.util.Date())
            memoryDao.deleteExpiredReminders(nowStr)
            systemLogs.value += "Cleanup: Removed reminders before $nowStr"
            
            // Collect errors
            launch {
                InferenceManager.errorState.collect { msg ->
                    if (msg == null) return@collect
                    
                    val isCritError = msg.contains("Error", ignoreCase = true) || msg.contains("Failed", ignoreCase = true)
                    
                    // Always Log
                    systemLogs.value += "${if(isCritError) "❌" else "ℹ️"} $msg"
                    
                    if (isCritError) {
                        _toastEvent.emit("Error: $msg") // Simple Popup
                        appStatus.value = com.telo.tinyzora.ui.AppStatus.ERROR
                    } else {
                        // Just Info (e.g. Engine Ready)
                        // Do NOT show in Chat.
                        if (msg.contains("Ready", ignoreCase = true)) {
                             appStatus.value = com.telo.tinyzora.ui.AppStatus.ACTIVE
                        }
                    }
                }
            }
            
            // Collect Sessions
            launch {
                chatDao.getAllSessions().collect { dbSessions ->
                    _sessions.value = dbSessions
                }
            }

            refreshAvailableModels()
            refreshMemories() // Load saved memories on startup

            // Auto load if possible
             val prefs = application.getSharedPreferences("tiny_prefs", android.content.Context.MODE_PRIVATE)
             val lastModel = prefs.getString("last_model_path", null)
             val lastUseGpu = prefs.getBoolean("last_use_gpu", false) // Default false
             
            if (lastModel != null && File(lastModel).exists()) {
                loadAi("auto", lastModel, lastUseGpu)
            } else {
                 loadAi("auto", null, false) // Default to CPU for stability
            }
        }
    }


    // State for pending attachments
    private val _pendingImages = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val pendingImages: StateFlow<List<android.net.Uri>> = _pendingImages
    
    // Pending Audio
    private val _pendingAudio = MutableStateFlow<List<com.telo.tinyzora.util.AudioClip>>(emptyList())
    val pendingAudio: StateFlow<List<com.telo.tinyzora.util.AudioClip>> = _pendingAudio
    
    fun handleImages(uris: List<android.net.Uri>) {
        _pendingImages.value = uris
    }
    
    fun handleAudio(clip: com.telo.tinyzora.util.AudioClip) {
        _pendingAudio.value += clip
    }
    
    fun processRecordedAudio(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val clip = com.telo.tinyzora.util.AudioUtils.convertWavToMonoWithMaxSeconds(context, uri)
            if (clip != null) {
                withContext(Dispatchers.Main) {
                    handleAudio(clip)
                }
            } else {
                withContext(Dispatchers.Main) {
                    _messages.value += ChatMessage(text = "Error: Failed to process audio.", isUser = true)
                }
            }
        }
    }
    
    fun clearPendingImages() {
        _pendingImages.value = emptyList()
        _pendingAudio.value = emptyList() // Clear both on "Clear"
    }

    fun sendCommand(prompt: String) {
        if (prompt.isBlank() && _pendingImages.value.isEmpty() && _pendingAudio.value.isEmpty()) return
        
        /*
        // Safety: Prevent sending images to text-only models (Causes Crash)
        if (_pendingImages.value.isNotEmpty() && !isCurrentModelMultimodal) {
             viewModelScope.launch { 
                 _toastEvent.emit("Text-Only Model! Images ignored.") 
                 _messages.value += ChatMessage(text = "System: Images rejected. Current model is Text-Only.", isUser = false)
             }
             // Clear unsupported images so user can chat comfortably
             clearPendingImages()
             // Proceed with text only? Or return?
             // User likely wants text response. Proceed.
        }
        */
        
        // STRIPPED DOWN MODE: Ignore all multimedia
        val currentImages = emptyList<android.net.Uri>() 
        val currentAudio = emptyList<com.telo.tinyzora.util.AudioClip>()
        
        // Clear pending immediately (optimistic)
        clearPendingImages()
        
        // CRITICAL: Cancel previous generation to prevent deadlock/queueing
        // This releases the Mutex lock in InferenceManager immediately.
        generationJob?.cancel()
        
        generationJob = viewModelScope.launch {
            val userMsg = ChatMessage(
                text = prompt, 
                isUser = true,
                imageUris = currentImages.map { it.toString() },
                audioClips = currentAudio
            )
            _messages.value += userMsg
            
            // Save to DB (async) -- omitted for brevity in this patch
            
            _uiState.value = "Thinking..."
            isGenerating.value = true
            
            var safeSessionId = currentSessionId ?: 0L
            
            try {
                // 1. Ensure Session & Save User Message
                if (currentSessionId == null) {
                     val newSession = ChatSession(title = if (prompt.length > 30) prompt.take(30) + "..." else prompt)
                     currentSessionId = chatDao.insertSession(newSession)
                }
                safeSessionId = currentSessionId!!
    
                viewModelScope.launch {
                    chatDao.insertMessage(com.telo.tinyzora.data.ChatMessageEntity(
                        sessionId = safeSessionId,
                        text = prompt,
                        isUser = true,
                        timestamp = System.currentTimeMillis(),
                        imageUris = currentImages.joinToString(",")
                    ))
                }
            } catch (e: Exception) {
                // Prevent crash if DB fails
                val msg = "History Error: ${e.message}"
                _toastEvent.emit(msg)
                systemLogs.value += msg
                e.printStackTrace()
            }
            
            try {
                // 2. Prepare Data (Background)
                val (bitmaps, audioBytes) = withContext(Dispatchers.IO) {
                     val context = getApplication<Application>()
                     val bmp = currentImages.mapNotNull { uri ->
                         // 512x512 is safer for on-device VRAM and prevents OOM/Native Crashes
                         com.telo.tinyzora.util.ImageUtils.decodeSampledBitmapFromUri(context, uri, 512, 512)
                     }
                     val wavs = currentAudio.map { it.genByteArrayForWav() }
                     Pair(bmp, wavs)
                }
                
                // 3. Inject Memory & Prompt Build
                val memorySnapshot = withContext(Dispatchers.IO) { ThermalMemory.snapshot(memoryDao) }
                
                // CONTEXT LOGIC:
                // If Fresh -> Send Full System Prompt + User Query
                // If Continued -> Send ONLY User Query (Engine has history)
                val finalPrompt = if (isContextFresh) {
                    isContextFresh = false
                    PromptBuilder.build(prompt, memorySnapshot, currentMode.value)
                } else {
                    prompt
                }
                
                var fullResponse = ""
                var isFirstToken = true

                InferenceManager.generateResponseStreaming(finalPrompt, bitmaps, audioBytes)
                    .collect { partial ->
                        fullResponse += partial
                        if (fullResponse.endsWith("\n\n\n")) {
                             fullResponse = fullResponse.substring(0, fullResponse.length - 1)
                        }
                        
                        // Hide JSON artifacts from live stream (prevents flashing)
                        val displayText = if (fullResponse.contains("```json")) {
                             fullResponse.substringBefore("```json").trim()
                        } else {
                             fullResponse
                        }

                        if (isFirstToken) {
                            _uiState.value = "Streaming"
                            _messages.value += ChatMessage(text = displayText, isUser = false)
                            isFirstToken = false
                        } else {
                            _messages.value = _messages.value.mapIndexed { index, msg ->
                                if (index == _messages.value.lastIndex && !msg.isUser) {
                                    msg.copy(text = displayText)
                                } else msg
                            }
                        }
                    }
                
                // 4. Final Polish & Action Extraction
                // Extract JSON / Memory Actions from fullResponse
                val (cleanedText, actionsLog) = extractAndAct(fullResponse)
                val finalText = if (cleanedText.isNotBlank()) cleanedText else (actionsLog ?: "")

                _messages.value = _messages.value.mapIndexed { index, msg ->
                    if (index == _messages.value.lastIndex && !msg.isUser) {
                        msg.copy(text = finalText)
                    } else msg
                }
                
                // 5. Save AI Message to DB
                // We use currentSessionId!! because we ensured it at start of method
                val confirmSessionId = currentSessionId!!
                viewModelScope.launch {
                    chatDao.insertMessage(com.telo.tinyzora.data.ChatMessageEntity(
                        sessionId = confirmSessionId,
                        text = finalText,
                        isUser = false,
                        timestamp = System.currentTimeMillis(),
                        imageUris = ""
                    ))
                }
                
                refreshMemories() // Reload memories if changed
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                 // User cancelled explicitly. No error message needed.
                 _uiState.value = "Idle"
                 isGenerating.value = false
            } catch (e: Exception) {
                 _messages.value += ChatMessage(text = "Error: ${e.message}", isUser = false)
                 _uiState.value = "Idle"
                 isGenerating.value = false
                 e.printStackTrace()
            } finally {
                isGenerating.value = false
                if (_uiState.value == "Streaming" || _uiState.value == "Thinking...") {
                    _uiState.value = "Idle"
                }
            }
        }
    }
    
    // Legacy single image handler (removed or redirected)
    fun handleImage(uri: android.net.Uri) {
        handleImages(listOf(uri))
    }

    // --- HELPER: Memory & Action Extraction (Ported from TinyZora.kt) ---
    private suspend fun extractAndAct(response: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        // Regex to find JSON block (Supports ```json, ```, and just { })
        // We prioritize explicit code blocks
        val codeBlockRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
        var match = codeBlockRegex.find(response)
        
        // Fallback: literal JSON object if strict block not found
        if (match == null) {
             val looseRegex = Regex("(\\{[\\s\\S]*?\\})")
             // Only if it looks like a valid tool action
             val potentialMatch = looseRegex.find(response)
             if (potentialMatch != null && potentialMatch.value.contains("\"action\"")) {
                 match = potentialMatch
             }
        }
        
        var cleanText = response
        var executionLog: String? = null

        if (match != null) {
            try {
                // Group 1 is the inner content for code blocks, Group 1 for loose regex too (via parens)
                val jsonStr = match.groupValues[1] 
                systemLogs.value += "Found Action JSON: $jsonStr"
                
                val toolJson = JSONObject(jsonStr)
                
                // Aggressive Cleanup: Remove the entire matched block
                cleanText = response.replace(match.value, "").trim()
                // Also clean up any lingering "json" or backticks if they were outside the match (rare but possible)
                cleanText = cleanText.replace("```json", "").replace("```", "").trim()

                
                val action = toolJson.optString("action")
                if (action == "save_reminder") {
                     val content = toolJson.optString("content")
                     var time = toolJson.optString("time")
                     
                     // Patch: If LLM returns only HH:mm, prepend Today's Date
                     if (time.length < 10 && time.contains(":")) {
                         val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                         time = "$today $time"
                     }
                     
                     memoryDao.insertReminder(com.telo.tinyzora.data.ReminderEntity(content = content, dueTime = time))
                     executionLog = "Saved reminder: $content at $time"
                     // Alarm logic omitted for brevity, handled by UniversalExecutor usually
                } else if (action == "save_fact") {
                     val content = toolJson.optString("content")
                     memoryDao.insertFact(com.telo.tinyzora.data.FactEntity(content = content))
                     executionLog = "Memorized: $content"
                } else if (action == "save_preference") {
                     val content = toolJson.optString("content")
                     memoryDao.insertPref(com.telo.tinyzora.data.PrefEntity(content = content))
                     executionLog = "Noted preference: $content"
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
        return@withContext Pair(cleanText, executionLog)
    }
    
    fun stopGeneration() {
        generationJob?.cancel()
        isGenerating.value = false
        _uiState.value = "Idle"
    }

    // --- MANAGEMENT ---
    
    // Toast Events (One-off)
    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    private var isCurrentModelMultimodal = false
    private var loadingJob: kotlinx.coroutines.Job? = null

    fun loadAi(mode: String, path: String?, useGpu: Boolean) {
        // Cancel previous load if user switches quickly
        loadingJob?.cancel()
        
        loadingJob = viewModelScope.launch(Dispatchers.IO) {
            val finalPath = path ?: findFirstModel()
            if (finalPath != null) {
                appStatus.value = com.telo.tinyzora.ui.AppStatus.LOADING
                systemLogs.value += "Loading model: ${File(finalPath).name}"
                
                // Determine Capabilities & Size
                val file = File(finalPath)
                val name = file.name
                val sizeInGb = file.length() / (1024.0 * 1024 * 1024)
                
                isCurrentModelMultimodal = name.contains("3n", ignoreCase = true) || 
                                          name.contains("clip", ignoreCase = true) || 
                                          name.contains("vision", ignoreCase = true)
                
                val isDeepSeek = name.contains("deepseek", ignoreCase = true) || name.contains("qwen", ignoreCase = true)

                // Lower threshold to 1.5GB to catch Q4 1.8GB models
                if (sizeInGb > 1.5 || isDeepSeek) {
                    val reason = if (isDeepSeek) "Complex Architecture" else "Large Size"
                    _toastEvent.emit("Warning: Heavy Model ($reason). Expect Heat/Lag.")
                    systemLogs.value += "Warning: High loads detected ($reason). Try CPU mode if unstable."
                }
                
                InferenceManager.loadModel(getApplication(), finalPath, useGpu)
                
                if (InferenceManager.isModelLoaded()) {
                     appStatus.value = com.telo.tinyzora.ui.AppStatus.ACTIVE
                     _toastEvent.emit("Brain Loaded (${if(isCurrentModelMultimodal) "Vision" else "Text"})")
                     lastInteractionTime = System.currentTimeMillis()
                } else {
                     appStatus.value = com.telo.tinyzora.ui.AppStatus.ERROR
                 }
// ...
// In sendCommand, around line 143:
//                 // --- GENERATION BRANCH ---
//                 val responseText = if (images.isNotEmpty()) {
//                      if (isCurrentModelMultimodal) {
//                          processImagesInternal(images, llmPrompt)
//                      } else {
//                          "Error: This model is Text-Only! Please switch to a Vision model (e.g. '3n' or 'vision') to analyze images."
//                      }
//                 } else {
// ...
                
                getApplication<Application>().getSharedPreferences("tiny_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_model_path", finalPath)
                    .putBoolean("last_use_gpu", useGpu)
                    .apply()
            } else {
                systemLogs.value += "No model found."
                _toastEvent.emit("No model found!")
            }
        }
    }

    fun unloadAi() {
        viewModelScope.launch(Dispatchers.IO) {
            InferenceManager.shutdown() // Add shutdown method to InferenceManager if missing, or use new one
            // Ideally InferenceManager logic handles nulling engine
            appStatus.value = com.telo.tinyzora.ui.AppStatus.IDLE
            _toastEvent.emit("Brain Unloaded (Cooling) ❄️")
        }
    }
    
    private fun findFirstModel(): String? {
       val app = getApplication<Application>()
       val files = app.filesDir.listFiles { _, name -> name.endsWith(".bin") || name.endsWith(".litertlm") }
       return files?.firstOrNull()?.absolutePath
    }

    fun refreshAvailableModels() {
         viewModelScope.launch(Dispatchers.IO) {
           val app = getApplication<Application>()
           val files = app.filesDir.listFiles { _, name -> 
               (name.endsWith(".bin") || name.endsWith(".litertlm")) && !name.matches(Regex(".*_\\d+\\.(bin|litertlm)$"))
           }
           // Deduplicate just in case
           val modelFiles = files?.map { it.name }?.distinct() ?: emptyList()
           
           availableModels.value = modelFiles.map { name ->
               val isVision = name.contains("3n", ignoreCase = true) || name.contains("clip", ignoreCase = true) || name.contains("vision", ignoreCase = true)
               ModelInfo(name, isVision)
           }
         }
    }

    fun deleteModel(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val file = File(app.filesDir, fileName)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) refreshAvailableModels()
            }
        }
    }
    
    fun loadModelFromUri(uri: android.net.Uri, useGpu: Boolean) {
         viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val contentResolver = context.contentResolver
                var name = "imported_${System.currentTimeMillis()}.bin" // Default fallback

                // 1. Try to get real filename
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) name = cursor.getString(index)
                    }
                }
                
                // Sanitize name
                if (!name.endsWith(".bin") && !name.endsWith(".litertlm") && !name.endsWith(".tflite")) {
                    name += ".bin"
                }

                systemLogs.value += "Importing: $name..."

                val destFile = File(context.filesDir, name)
                
                // 2. Copy File
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                
                if (destFile.exists() && destFile.length() > 0) {
                     systemLogs.value += "Import success: ${destFile.length() / 1024 / 1024} MB"
                     refreshAvailableModels()
                     loadAi("auto", destFile.absolutePath, useGpu)
                } else {
                     systemLogs.value += "Import failed: File empty or not created."
                }
                
            } catch(e: Exception) {
                e.printStackTrace()
                systemLogs.value += "Import Error: ${e.message}"
            }
         }
    }
    
    // private val _allMemories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    // val allMemories: StateFlow<List<MemoryEntity>> = _allMemories.asStateFlow()



    fun setMode(mode: ChatMode) {
        currentMode.value = mode
    }

    fun loadModelByName(name: String, useGpu: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val internalFile = File(app.filesDir, name)
            
            // Try internal first
            if (internalFile.exists()) {
                loadAi("auto", internalFile.absolutePath, useGpu)
            } else {
                 systemLogs.value += "Model $name not found internally."
            }
        }
    }
    
    fun startNewChat() { 
        currentSessionId = null
        _messages.value = emptyList() 
        // Reset Context for new conversation
        isContextFresh = true
        viewModelScope.launch { 
            try {
                InferenceManager.clearContext()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadChat(session: ChatSession) {
        currentSessionId = session.id
        // Reset Context when loading historical chat (Engine starts fresh)
        isContextFresh = true
        viewModelScope.launch { 
            try {
                InferenceManager.clearContext()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Note: We don't re-feed history to Engine here. 
            // The model will see the visual history but its KV cache is empty.
            // This is preferred over context overflow.
        }
        viewModelScope.launch {
            try {
                val dbMessages = chatDao.getMessagesForSession(session.id)
                 
                 // SAFEGUARD: Aggressive truncation for UI Stability (5000 chars)
                 _messages.value = dbMessages.map { entity ->
                     val safeText = if (entity.text.length > 5000) {
                         entity.text.take(5000) + "\n\n... [Message Truncated for Performance]"
                     } else {
                         entity.text
                     }
                     
                     ChatMessage(
                         text = safeText,
                         isUser = entity.isUser,
                         timestamp = entity.timestamp,
                         imageUris = entity.imageUris?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                     )
                 }
                 
                 // Check total size to warn user
                 val totalChars = dbMessages.sumOf { it.text.length }
                 if (totalChars > 20000) {
                     systemLogs.value += "⚠️ Chat is getting long (${totalChars} chars). Consider starting a new one."
                     _toastEvent.emit("Chat is long. Models may degrade. Suggest 'New Chat'.")
                 }
             } catch (e: Exception) {
                 e.printStackTrace()
                 _toastEvent.emit("Error loading chat: ${e.message}")
             }
        }
    }

    fun deleteChat(session: ChatSession) {
        viewModelScope.launch {
            chatDao.deleteSession(session)
            if (currentSessionId == session.id) {
                startNewChat()
            }
        }
    }

    fun renameSession(session: ChatSession, newTitle: String) {
        viewModelScope.launch {
            chatDao.updateSessionTitle(session.id, newTitle)
        }
    }

    private val _facts = MutableStateFlow<List<com.telo.tinyzora.data.FactEntity>>(emptyList())
    val facts: StateFlow<List<com.telo.tinyzora.data.FactEntity>> = _facts.asStateFlow()

    private val _prefs = MutableStateFlow<List<com.telo.tinyzora.data.PrefEntity>>(emptyList())
    val prefs: StateFlow<List<com.telo.tinyzora.data.PrefEntity>> = _prefs.asStateFlow()

    private val _reminders = MutableStateFlow<List<com.telo.tinyzora.data.ReminderEntity>>(emptyList())
    val reminders: StateFlow<List<com.telo.tinyzora.data.ReminderEntity>> = _reminders.asStateFlow()

    fun refreshMemories() {
        viewModelScope.launch(Dispatchers.IO) {
            _facts.value = memoryDao.getAllFacts()
            _prefs.value = memoryDao.getAllPrefs()
            _reminders.value = memoryDao.getAllReminders()
        }
    }
    
    fun deleteMemory(mem: Any) {
         viewModelScope.launch(Dispatchers.IO) {
             when(mem) {
                 is com.telo.tinyzora.data.FactEntity -> memoryDao.deleteFact(mem)
                 is com.telo.tinyzora.data.PrefEntity -> memoryDao.deletePref(mem)
                 is com.telo.tinyzora.data.ReminderEntity -> memoryDao.deleteReminder(mem)
             }
             refreshMemories()
         }
    }
}

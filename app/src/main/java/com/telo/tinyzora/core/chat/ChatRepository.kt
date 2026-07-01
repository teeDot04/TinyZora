package com.telo.tinyzora.core.chat

import android.content.Context
import com.telo.tinyzora.ui.chat.AudioHolder
import com.telo.tinyzora.ui.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessageEntity(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val thinking: String? = null,
    val bitmapPath: String? = null,
    val audioPath: String? = null,
    val documentName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatRepository(private val context: Context) {
    private val chatFile = File(context.filesDir, "chat_history.json")
    private val mediaDir = File(context.filesDir, "chat_media").apply { mkdirs() }
    
    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    suspend fun loadMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (!chatFile.exists()) return@withContext emptyList()
        try {
            val entities: List<ChatMessageEntity> = jsonFormat.decodeFromString(chatFile.readText())
            entities.map { entity ->
                val imageHolder = entity.bitmapPath?.let { uriString ->
                    com.telo.tinyzora.ui.chat.ImageHolder(uriString)
                }
                
                val audio = entity.audioPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) AudioHolder(file.readBytes()) else null
                }
                
                ChatMessage(
                    id = entity.id,
                    role = entity.role,
                    text = entity.text,
                    thinking = entity.thinking,
                    isThinkingDone = entity.thinking != null,
                    image = imageHolder,
                    audio = audio,
                    documentName = entity.documentName,
                    timestamp = entity.timestamp
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun addMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        // Load existing
        val currentEntities = if (chatFile.exists()) {
            try {
                jsonFormat.decodeFromString<List<ChatMessageEntity>>(chatFile.readText())
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
        
        // Save media to disk if exists
        val savedBitmapPath = message.image?.uriString
        
        val savedAudioPath = message.audio?.let { holder ->
            val file = File(mediaDir, "aud_${UUID.randomUUID()}.wav")
            file.writeBytes(holder.data)
            file.absolutePath
        }
        
        val newEntity = ChatMessageEntity(
            id = message.id,
            role = message.role,
            text = message.text,
            thinking = message.thinking,
            bitmapPath = savedBitmapPath,
            audioPath = savedAudioPath,
            documentName = message.documentName,
            timestamp = message.timestamp
        )
        
        // Save
        val updated = currentEntities + newEntity
        chatFile.writeText(jsonFormat.encodeToString(updated))
    }
    
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        if (chatFile.exists()) chatFile.delete()
        if (mediaDir.exists()) mediaDir.deleteRecursively()
        mediaDir.mkdirs()
    }
    
    suspend fun deleteMessagePair(targetId: String) = withContext(Dispatchers.IO) {
        if (!chatFile.exists()) return@withContext
        try {
            val currentEntities = jsonFormat.decodeFromString<MutableList<ChatMessageEntity>>(chatFile.readText())
            val targetIndex = currentEntities.indexOfFirst { it.id == targetId }
            if (targetIndex != -1) {
                // Remove the targeted message
                currentEntities.removeAt(targetIndex)
                
                // If it was a pair (Zora replied), remove her reply as well (it slides into the same index)
                if (targetIndex < currentEntities.size) {
                    val nextMessage = currentEntities[targetIndex]
                    if (nextMessage.role == "zora" || nextMessage.role == "system") {
                        currentEntities.removeAt(targetIndex)
                    }
                }
                
                // Overwrite the file with the stripped array
                chatFile.writeText(jsonFormat.encodeToString(currentEntities))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getChatHistoryFile(): File {
        return chatFile
    }
}

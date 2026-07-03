package com.telo.tinyzora.core.chat
import android.content.Context
import com.telo.tinyzora.ui.chat.ChatMessage
class ChatRepository(context: Context) {
    fun loadMessages(): List<ChatMessage> = emptyList()
    fun addMessage(message: ChatMessage) {}
    fun deleteMessagePair(id: String) {}
}

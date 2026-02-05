package com.telo.tinyzora.ui

import com.telo.tinyzora.core.LifeChapter

data class ChatMessage(
    val id: Long = 0,
    val text: String,
    val isUser: Boolean,
    val domain: LifeChapter? = null,
    val isCode: Boolean = false,
    val imageUris: List<String> = emptyList(),
    val audioClips: List<com.telo.tinyzora.util.AudioClip> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

package com.telo.tinyzora.core.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryEntry(
    val type: String,
    val content: String,
    val date: String = "",
    val due: String? = null
)

@Serializable
data class MemoryFile(
    val version: Int = 1,
    val entries: List<MemoryEntry> = emptyList()
)

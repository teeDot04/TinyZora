package com.telo.tinyzora.core.memory
import kotlinx.serialization.Serializable
@Serializable
data class MemoryEntry(val type: String = "fact", val content: String, val date: String = "", val due: String? = null)

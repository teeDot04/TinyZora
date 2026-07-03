package com.telo.tinyzora.core.memory
import java.time.ZonedDateTime
class MemoryConsolidator(private val store: MemoryStore, private val time: ZonedDateTime) {
    fun consolidate(transcript: List<Pair<String, String>>, generateOnce: suspend (String) -> String) {}
}

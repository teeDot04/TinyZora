package com.telo.tinyzora.core.inference

import kotlinx.serialization.Serializable

@Serializable
data class ImportedModel(
    val name: String,
    val path: String,
    val fileSize: Long,
    val dateImported: Long = System.currentTimeMillis(),
    val config: LlmConfig = LlmConfig()
)

@Serializable
data class LlmConfig(
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val supportThinking: Boolean = true
)

data class InferenceResult(
    val partialText: String,
    val isDone: Boolean,
    val partialThinking: String? = null
)

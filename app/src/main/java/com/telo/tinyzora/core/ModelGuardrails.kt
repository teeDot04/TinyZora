package com.telo.tinyzora.core

/**
 * COPYING EDGE GALLERY ARCHITECTURE:
 * These are the exact guardrails used to keep the model stable on mobile.
 */
data class ModelGuardrails(
    val maxTokens: Int = 1024,      // Don't let it write a novel
    val temperature: Float = 0.8f,  // Creativity (Lower = more robotic/precise)
    val topK: Int = 40,             // Limits vocabulary to top 40 likely words
    val topP: Float = 0.95f,        // Nucleus sampling
    val randomSeed: Int = 42        // Consistency
)

object AgentConfig {
    // For TinyZora (Agent Mode), we want strictness.
    val STRICT_MODE = ModelGuardrails(
        maxTokens = 512,
        temperature = 0.2f, // Very low temp for JSON accuracy
        topK = 20,
        topP = 0.8f
    )
    
    // For Chat Mode (Daffy Duck style)
    val CREATIVE_MODE = ModelGuardrails(
        maxTokens = 1024,
        temperature = 0.8f,
        topK = 40,
        topP = 0.95f
    )
}

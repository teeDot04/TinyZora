package com.telo.tinyzora.core

enum class ChatMode(val label: String, val systemInstruction: String) {
    FAST_CHAT("Fast", "Respond in 1-2 sentences. Be concise and conversational."),
    THINKING_MODE("Thinking", "Provide a comprehensive, well-structured response. Explain your reasoning clearly, like a standard helpful AI.")
}

package com.telo.tinyzora.core.inference

object ChatMLFormatter {
    private const val SYSTEM_TOKEN = "<|im_start|>system\n"
    private const val USER_TOKEN = "<|im_start|>user\n"
    private const val ASSISTANT_TOKEN = "<|im_start|>assistant\n"
    private const val END_TOKEN = "<|im_end|>\n"

    fun formatSystemPrompt(prompt: String): String {
        return "$SYSTEM_TOKEN$prompt$END_TOKEN"
    }

    fun formatUserPrompt(prompt: String): String {
        return "$USER_TOKEN$prompt$END_TOKEN"
    }

    fun formatAssistantPrompt(): String {
        return ASSISTANT_TOKEN
    }

    fun formatFullPrompt(systemPrompt: String, userPrompt: String): String {
        return buildString {
            append(formatSystemPrompt(systemPrompt))
            append(formatUserPrompt(userPrompt))
            append(formatAssistantPrompt())
        }
    }
}
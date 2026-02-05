package com.telo.tinyzora.core

/**
 * Classifies user input into specific Life Chapters.
 * Acts as the "Router" before the LLM sees the text.
 */
enum class LifeChapter {
    PSYCHOLOGY,      // Studies, theories, exam prep
    DATA_SCIENCE,    // Code, formulas, projects
    TRADING,         // Trades, patterns, journal
    BUSINESS,        // Orlix clients, payments, lending
    PERSONAL,        // General life, reminders
    INSIGHTS         // Cross-domain connections
}

class DomainDetector {

    fun detect(input: String): LifeChapter {
        val lower = input.lowercase()

        return when {
            // 1. Trading
            lower.containsAny("trade", "btc", "usd", "profit", "loss", "journal", "pattern", "candle", "bearish", "bullish") -> 
                LifeChapter.TRADING

            // 2. Business / Orlix
            lower.containsAny("client", "payment", "lend", "loan", "orlix", "interest", "shilling", "bob", "repay") -> 
                LifeChapter.BUSINESS

            // 3. Psychology (Uni)
            lower.containsAny("freud", "jung", "cognitive", "bias", "psych", "theory", "exam", "assignment", "cat", "lecture", "class") -> 
                LifeChapter.PSYCHOLOGY

            // 4. Data Science
            lower.containsAny("model", "dataset", "algorithm", "python", "regression", "cluster", "pandas", "tensor", "numpy", "ml") -> 
                LifeChapter.DATA_SCIENCE

            // Default
            else -> LifeChapter.PERSONAL
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
}

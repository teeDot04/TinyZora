package com.telo.tinyzora.core.richtext
sealed class MessageBlock {
    data class CodeBlock(val language: String, val code: String) : MessageBlock()
    data class LatexBlock(val formula: String) : MessageBlock()
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MessageBlock()
}

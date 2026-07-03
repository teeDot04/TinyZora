package com.telo.tinyzora.core.richtext
import androidx.compose.ui.text.AnnotatedString
object MessageParser {
    fun buildMergedLayoutBlocks(text: String): List<Any> = listOf(AnnotatedString(text))
}

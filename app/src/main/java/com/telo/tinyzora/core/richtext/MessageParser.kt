package com.telo.tinyzora.core.richtext

import android.util.LruCache
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

sealed class MessageBlock {
    data class TextBlock(val rawText: String, val annotated: AnnotatedString) : MessageBlock()
    data class CodeBlock(val language: String, val code: String) : MessageBlock()
    data class LatexBlock(val formula: String) : MessageBlock()
    data class LatexInlineBlock(val formula: String) : MessageBlock()
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MessageBlock()
}

object MessageParser {
    private val parseCache = LruCache<String, List<MessageBlock>>(200)
    private val markdownCache = LruCache<String, AnnotatedString>(500)
    private val uiLayoutCache = LruCache<String, List<Any>>(200)

    fun buildMergedLayoutBlocks(rawInput: String): List<Any> {
        uiLayoutCache.get(rawInput)?.let { return it }

        val blocks = parse(rawInput)
        val items = mutableListOf<Any>()
        var currentAnnotated = buildAnnotatedString {}
        var hasPendingText = false

        fun flushText() {
            if (hasPendingText) {
                // We no longer manually split by \n\n. We let Jetpack Compose handle the monolithic paragraphs natively.
                if (currentAnnotated.text.isNotBlank()) {
                    items.add(currentAnnotated)
                }
                currentAnnotated = buildAnnotatedString {}
                hasPendingText = false
            }
        }

        for (block in blocks) {
            when (block) {
                is MessageBlock.TextBlock -> {
                    currentAnnotated = buildAnnotatedString {
                        append(currentAnnotated)
                        append(block.annotated)
                    }
                    hasPendingText = true
                }
                is MessageBlock.LatexInlineBlock -> {
                    currentAnnotated = buildAnnotatedString {
                        append(currentAnnotated)
                        withStyle(SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = Color.Unspecified, // Inherit from Text()
                            fontFamily = FontFamily.Serif,
                            fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)
                        )) {
                            append(block.formula)
                        }
                    }
                    hasPendingText = true
                }
                else -> {
                    flushText()
                    items.add(block)
                }
            }
        }
        flushText()

        uiLayoutCache.put(rawInput, items)
        return items
    }

    fun parse(rawInput: String): List<MessageBlock> {
        parseCache.get(rawInput)?.let { return it }
        val cleanRaw = rawInput.replace(Regex("(?s)```(markdown|md)\\s*(.*?)```", RegexOption.IGNORE_CASE), "$2")
        val blocks = mutableListOf<MessageBlock>()
        var cursor = 0
        val textAccumulator = StringBuilder()
        
        fun flushText() {
            if (textAccumulator.isNotEmpty()) {
                val text = textAccumulator.toString().trimEnd()
                if (text.isNotBlank()) {
                    blocks.add(MessageBlock.TextBlock(text, parseMarkdown(text)))
                }
                textAccumulator.clear()
            }
        }
        
        while (cursor < cleanRaw.length) {
            var matchedSpecial = false
            
            if (cleanRaw.startsWith("```", cursor)) {
                val endIdx = cleanRaw.indexOf("```", cursor + 3)
                if (endIdx != -1) {
                    flushText()
                    val fullCode = cleanRaw.substring(cursor + 3, endIdx)
                    val lines = fullCode.lines()
                    val firstLine = lines.firstOrNull()?.trim() ?: ""
                    var language = "code"
                    var codeContent = fullCode
                    
                    if (firstLine.isNotEmpty() && !firstLine.contains(' ')) {
                        language = firstLine
                        codeContent = fullCode.substringAfter('\n').trim()
                    } else {
                        codeContent = fullCode.trim()
                    }
                    if (codeContent.isNotEmpty()) {
                        blocks.add(MessageBlock.CodeBlock(language, codeContent))
                    }
                    cursor = endIdx + 3
                    matchedSpecial = true
                }
            } else if (cleanRaw.startsWith("$$", cursor)) {
                val endIdx = cleanRaw.indexOf("$$", cursor + 2)
                if (endIdx != -1) {
                    flushText()
                    val rawFormula = cleanRaw.substring(cursor + 2, endIdx).trim()
                    val formula = rawFormula.replace(Regex("^(?i)latex\\s*"), "").trim()
                    if (formula.isNotEmpty()) {
                        blocks.add(MessageBlock.LatexBlock(formula))
                    }
                    cursor = endIdx + 2
                    matchedSpecial = true
                }
            } else if (cleanRaw.startsWith("$", cursor)) {
                val endIdx = cleanRaw.indexOf("$", cursor + 1)
                if (endIdx != -1 && endIdx > cursor + 1) {
                    val content = cleanRaw.substring(cursor + 1, endIdx)
                    if (content.isNotBlank()) {
                        flushText()
                        blocks.add(MessageBlock.LatexInlineBlock(content.trim()))
                        cursor = endIdx + 1
                        matchedSpecial = true
                    }
                }
            } 
            
            if (!matchedSpecial) {
                textAccumulator.append(cleanRaw[cursor])
                cursor++
            }
        }
        
        flushText()
        
        val finalBlocks = mutableListOf<MessageBlock>()
        for (block in blocks) {
            if (block is MessageBlock.TextBlock) {
                val parsedBlocks = extractTablesFromTextBlock(block.rawText)
                finalBlocks.addAll(parsedBlocks)
            } else {
                finalBlocks.add(block)
            }
        }
        
        parseCache.put(rawInput, finalBlocks)
        return finalBlocks
    }
    
    private fun extractTablesFromTextBlock(text: String): List<MessageBlock> {
        val dividerRegex = Regex("(?m)^\\s*\\|?\\s*?:?-+:?\\s*?\\|.*$")
        val blocks = mutableListOf<MessageBlock>()
        
        var currentIndex = 0
        while (currentIndex < text.length) {
            val dividerMatch = dividerRegex.find(text, currentIndex)
            if (dividerMatch != null) {
                val textBeforeDivider = text.substring(currentIndex, dividerMatch.range.first).trimEnd()
                val lastNewline = textBeforeDivider.lastIndexOf('\n')
                val startOfHeader = if (lastNewline != -1) currentIndex + lastNewline + 1 else currentIndex
                
                val precedingText = text.substring(currentIndex, startOfHeader)
                if (precedingText.isNotEmpty() && precedingText.trim().isNotEmpty()) {
                    blocks.add(MessageBlock.TextBlock(precedingText, parseMarkdown(precedingText)))
                } else if (precedingText.isNotEmpty() && blocks.isNotEmpty()) {
                    blocks.add(MessageBlock.TextBlock(precedingText, parseMarkdown(precedingText)))
                }
                
                var tableEnd = dividerMatch.range.first
                while (tableEnd < text.length) {
                    val nextLineBreak = text.indexOf('\n', tableEnd)
                    val endOfSearch = if (nextLineBreak != -1) nextLineBreak else text.length
                    val line = text.substring(tableEnd, endOfSearch)
                    
                    if (line.trim().isEmpty() || !line.contains("|")) {
                        break 
                    }
                    tableEnd = endOfSearch + 1
                }
                
                val tableRaw = text.substring(startOfHeader, minOf(tableEnd, text.length)).trim()
                val tableLines = tableRaw.lines().filter { it.isNotBlank() }
                
                if (tableLines.size >= 2) {
                    val headerLine = tableLines[0].trim().removePrefix("|").removeSuffix("|")
                    val headers = headerLine.split("|").map { it.trim() }
                    val rows = mutableListOf<List<String>>()
                    for (i in 2 until tableLines.size) {
                        val rowLine = tableLines[i].trim().removePrefix("|").removeSuffix("|")
                        val rowCols = rowLine.split("|").map { it.trim() }
                        rows.add(rowCols)
                    }
                    blocks.add(MessageBlock.TableBlock(headers, rows))
                } else {
                    blocks.add(MessageBlock.TextBlock(tableRaw, parseMarkdown(tableRaw)))
                }
                
                currentIndex = tableEnd
            } else {
                val remaining = text.substring(currentIndex)
                if (remaining.isNotEmpty() && remaining.trim().isNotEmpty()) {
                    blocks.add(MessageBlock.TextBlock(remaining, parseMarkdown(remaining)))
                } else if (remaining.isNotEmpty() && blocks.isNotEmpty()) {
                    blocks.add(MessageBlock.TextBlock(remaining, parseMarkdown(remaining)))
                }
                break
            }
        }
        return blocks
    }

    fun parseMarkdown(text: String): AnnotatedString {
        markdownCache.get(text)?.let { return it }
        val sanitizedText = text.replace(Regex("(?<!\\\\)\\\\([a-zA-Z]{2,})(?:\\{([^}]+)\\})?")) { match ->
            val base = match.groups[1]?.value ?: ""
            val arg = match.groups[2]?.value
            if (arg != null) "$arg-$base" else base
        }
        
        val result = buildAnnotatedString {
            val lines = sanitizedText.trimEnd().split("\n")
            lines.forEachIndexed { index, line ->
                val trimmedLine = line.trimEnd()
                when {
                    trimmedLine.startsWith("# ") -> {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp))) {
                            appendInlineStyles(trimmedLine.removePrefix("# ").trimStart())
                        }
                    }
                    trimmedLine.startsWith("## ") -> {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp))) {
                            appendInlineStyles(trimmedLine.removePrefix("## ").trimStart())
                        }
                    }
                    trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                        append("  • ")
                        appendInlineStyles(trimmedLine.substring(2).trimStart())
                    }
                    else -> {
                        // Keep trailing spaces 
                        appendInlineStyles(line)
                    }
                }
                if (index < lines.size - 1) {
                    append("\n")
                }
            }
        }
        markdownCache.put(text, result)
        return result
    }
    
    private fun AnnotatedString.Builder.appendInlineStyles(line: String) {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) -> {
                    val endToken = line.indexOf("**", i + 2)
                    if (endToken != -1) {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(line.substring(i + 2, endToken))
                        }
                        i = endToken + 2
                    } else {
                        append(line[i])
                        i++
                    }
                }
                line.startsWith("*", i) -> {
                    val endToken = line.indexOf("*", i + 1)
                    if (endToken != -1 && endToken > i && !line.startsWith("**", i)) {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(line.substring(i + 1, endToken))
                        }
                        i = endToken + 1
                    } else {
                        append(line[i])
                        i++
                    }
                }
                line.startsWith("`", i) -> {
                    val endToken = line.indexOf("`", i + 1)
                    if (endToken != -1) {
                        withStyle(style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.LightGray.copy(alpha = 0.3f)
                        )) {
                            append(line.substring(i + 1, endToken))
                        }
                        i = endToken + 1
                    } else {
                        append(line[i])
                        i++
                    }
                }
                else -> {
                    append(line[i])
                    i++
                }
            }
        }
    }
}

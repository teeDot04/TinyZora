package com.telo.tinyzora.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.Text

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    val annotatedString = buildAnnotatedString {
        var currentText = text
        var index = 0
        
        while (index < currentText.length) {
            when {
                // Bold: **text**
                currentText.substring(index).startsWith("**") -> {
                    val endIndex = currentText.indexOf("**", index + 2)
                    if (endIndex != -1) {
                        val boldText = currentText.substring(index + 2, endIndex)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(boldText)
                        }
                        index = endIndex + 2
                    } else {
                        append(currentText[index])
                        index++
                    }
                }
                // Italic: *text* (but not **)
                currentText.substring(index).startsWith("*") && 
                !currentText.substring(index).startsWith("**") -> {
                    val endIndex = currentText.indexOf("*", index + 1)
                    if (endIndex != -1 && !currentText.substring(endIndex).startsWith("**")) {
                        val italicText = currentText.substring(index + 1, endIndex)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(italicText)
                        }
                        index = endIndex + 1
                    } else {
                        append(currentText[index])
                        index++
                    }
                }
                // Code: `text`
                currentText.substring(index).startsWith("`") -> {
                    val endIndex = currentText.indexOf("`", index + 1)
                    if (endIndex != -1) {
                        val codeText = currentText.substring(index + 1, endIndex)
                        withStyle(SpanStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            background = MaterialTheme.colorScheme.surfaceVariant
                        )) {
                            append(codeText)
                        }
                        index = endIndex + 1
                    } else {
                        append(currentText[index])
                        index++
                    }
                }
                // Strikethrough: ~~text~~
                currentText.substring(index).startsWith("~~") -> {
                    val endIndex = currentText.indexOf("~~", index + 2)
                    if (endIndex != -1) {
                        val strikeText = currentText.substring(index + 2, endIndex)
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(strikeText)
                        }
                        index = endIndex + 2
                    } else {
                        append(currentText[index])
                        index++
                    }
                }
                else -> {
                    append(currentText[index])
                    index++
                }
            }
        }
    }
    
    Text(
        text = annotatedString,
        modifier = modifier,
        style = style,
        color = color
    )
}

package com.telo.tinyzora.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CodeBlockCard(language: String, code: String) {
    var copied by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), // Top bar header background
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) // Subtle divider border
    ) {
        Column {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant 
                )
                Row(
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Copied Code", code)
                        clipboard.setPrimaryClip(clip)
                        copied = true
                        coroutineScope.launch {
                            delay(2000)
                            copied = false
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant 
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (copied) "Copied!" else "Copy",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            // Code body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) // Used for both light/dark, making it uniform
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val highlightedCode = remember(code, darkTheme) {
                    syntaxHighlight(code, darkTheme)
                }
                Text(
                    text = highlightedCode,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

// Simple regex-based syntax highlighter for GitHub style aesthetics
fun syntaxHighlight(code: String, isDark: Boolean) = buildAnnotatedString {
    val keywords = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
        "while", "val", "var", "fun", "object", "typealias", "constructor", "def", "let", "const"
    )
    
    val stringPattern = "\"(.*?)\"|'(.*?)'".toRegex()
    val commentPattern = "//.*|/\\*[\\s\\S]*?\\*/".toRegex()
    val numberPattern = "\\b\\d+(\\.\\d+)?\\b".toRegex()
    val keywordPattern = "\\b(${keywords.joinToString("|")})\\b".toRegex()

    val defaultColor = if (isDark) Color(0xFFE5E7EB) else Color(0xFF24292E)
    val keywordColor = if (isDark) Color(0xFFFF7B72) else Color(0xFFD73A49)
    val numberColor = if (isDark) Color(0xFF79C0FF) else Color(0xFF005CC5)
    val stringColor = if (isDark) Color(0xFFA5D6FF) else Color(0xFF032F62)
    val commentColor = if (isDark) Color(0xFF8B949E) else Color(0xFF6A737D)

    // Default color
    pushStyle(SpanStyle(color = defaultColor))
    append(code)
    pop()

    // Apply syntax highlighting overrides
    val styles = mutableListOf<Pair<IntRange, SpanStyle>>()

    keywordPattern.findAll(code).forEach { match ->
        styles.add(match.range to SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold))
    }

    numberPattern.findAll(code).forEach { match ->
        styles.add(match.range to SpanStyle(color = numberColor))
    }

    stringPattern.findAll(code).forEach { match ->
        styles.add(match.range to SpanStyle(color = stringColor))
    }

    commentPattern.findAll(code).forEach { match ->
        styles.add(match.range to SpanStyle(color = commentColor))
    }

    // Apply styles resolving overlaps (later applied rules win, so comments and strings override keywords)
    styles.forEach { (range, style) ->
        addStyle(style, range.first, range.last + 1)
    }
}

package com.telo.tinyzora.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telo.tinyzora.core.richtext.MessageParser

@Composable
fun TableCard(
    headers: List<String>,
    rows: List<List<String>>
) {
    if (headers.isEmpty()) return

    // Calculate dynamic column weights based on content or split evenly
    val colWeight = 1f / headers.size.toFloat()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent, 
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) // Blue tint for header
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                headers.forEach { header ->
                    val parsedHeader = remember(header) { MessageParser.parseMarkdown(header) }
                    Text(
                        text = parsedHeader,
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface 
                    )
                }
            }

            // Data Rows (Zebra Striping, No Dividers)
            rows.forEachIndexed { index, row ->
                val rowBackgroundColor = if (index % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.4f)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBackgroundColor)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    row.forEachIndexed { colIndex, cell ->
                        // Fill missing cells if row is shorter than headers
                        if (colIndex < headers.size) {
                            val parsedCell = remember(cell) { MessageParser.parseMarkdown(cell) }
                            Text(
                                text = parsedCell,
                                modifier = Modifier.weight(1f).padding(end = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

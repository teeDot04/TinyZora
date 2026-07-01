package com.telo.tinyzora.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.telo.tinyzora.ui.chat.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    isPlayingAudio: Boolean = false,
    onPlayAudio: ((ByteArray) -> Unit)? = null,
    onStopAudio: (() -> Unit)? = null
) {
    val bg = if (message.role == "user") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.role == "user") "You" else "Zora",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                message.image?.uriString?.let { uriString ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = uriString,
                        contentDescription = "Attached image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 320.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                if (message.audio != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        if (isPlayingAudio) {
                            Text("Playing...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(onClick = { onStopAudio?.invoke() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop", tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Text("Audio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(onClick = { message.audio?.data?.let { onPlayAudio?.invoke(it) } }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.wrapContentWidth()) {
                onDelete?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

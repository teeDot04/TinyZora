package com.telo.tinyzora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val streamingState by viewModel.streamingState.collectAsState()
    val groupedMessages by viewModel.groupedMessages.collectAsState()
    
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // imePadding() here pushes the whole layout up when keyboard opens
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                groupedMessages.forEach { group ->
                    item(key = "header_${group.dateLabel}") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(text = group.dateLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                    items(group.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) { 
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary) 
            }

            // Input Area
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment Icons
                    Icon(Icons.Default.AttachFile, contentDescription = "File", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).padding(8.dp))
                    Icon(Icons.Default.Image, contentDescription = "Image", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).padding(8.dp))
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).padding(8.dp))
                    Icon(Icons.Default.Mic, contentDescription = "Audio", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).padding(8.dp))

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        placeholder = { Text("Ask anything") },
                        maxLines = 4
                    )

                    // Send Button (Always visible, changes icon based on state)
                    IconButton(onClick = {
                        if (streamingState.isGenerating) {
                            viewModel.cancelGeneration()
                        } else {
                            val text = inputText.text.trim()
                            if (text.isNotEmpty()) {
                                viewModel.sendMessage(text)
                                inputText = TextFieldValue("")
                            }
                        }
                    }) {
                        Icon(
                            if (streamingState.isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
    
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).background(backgroundColor).padding(16.dp)
        ) {
            Text(text = message.text, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

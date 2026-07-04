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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val streamingState by viewModel.streamingState.collectAsState()
    val groupedMessages by viewModel.groupedMessages.collectAsState()
    
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(bottom = 90.dp).padding(horizontal = 16.dp),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (streamingState.isGenerating && streamingState.streamingText.isEmpty()) {
                    item(key = "thinking_indicator") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("Thinking...", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                groupedMessages.forEach { group ->
                    item(key = "header_${group.dateLabel}") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                                Text(text = group.dateLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(group.messages, key = { it.id }) { message ->
                        MessageBubble(message = message, onDelete = { viewModel.deleteMessage(message.id) })
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

            // Input Area at bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .imePadding()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Attachment menu icons
                        IconButton(onClick = { /* File picker */ }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        
                        IconButton(onClick = { /* Image picker */ }) {
                            Icon(Icons.Default.Image, contentDescription = "Attach Image", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        
                        IconButton(onClick = { /* Camera */ }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        
                        IconButton(onClick = { /* Audio recording */ }) {
                            Icon(Icons.Default.Mic, contentDescription = "Record Audio", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }

                        // Text input
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            placeholder = { Text("Ask anything", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            maxLines = 4
                        )

                        // Send button - only visible when text exists
                        val canSend = inputText.text.trim().isNotEmpty()
                        if (canSend || streamingState.isGenerating) {
                            IconButton(
                                onClick = {
                                    if (streamingState.isGenerating) {
                                        viewModel.cancelGeneration()
                                    } else {
                                        val text = inputText.text.trim()
                                        if (text.isNotEmpty()) {
                                            viewModel.sendMessage(text)
                                            inputText = TextFieldValue("")
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    if (streamingState.isGenerating) Icons.Rounded.Stop else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = if (streamingState.isGenerating) "Stop" else "Send",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, onDelete: () -> Unit = {}) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete message?") },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
        )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        val bubbleShape = RoundedCornerShape(16.dp)
        val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
        val contentColor = MaterialTheme.colorScheme.onBackground

        Box(
            modifier = Modifier.widthIn(max = 280.dp).clip(bubbleShape).background(backgroundColor).padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                if (message.text.isNotBlank()) {
                    Text(text = message.text, color = contentColor, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    if (!isUser) {
                        IconButton(onClick = { clipboardManager.setText(buildAnnotatedString { append(message.text) }) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                        }
                    }
                    IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

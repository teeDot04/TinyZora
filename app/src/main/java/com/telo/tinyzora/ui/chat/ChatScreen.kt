package com.telo.tinyzora.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telo.tinyzora.core.richtext.MessageBlock
import com.telo.tinyzora.core.richtext.MessageParser
import com.telo.tinyzora.ui.chat.components.CodeBlockCard
import com.telo.tinyzora.ui.chat.components.LatexCard
import com.telo.tinyzora.ui.chat.components.TableCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        repeat(3) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 0f,
                animationSpec = infiniteRepeatable(animation = keyframes {
                    durationMillis = 1000
                    0f at 0 using LinearEasing
                    -10f at (200 + index * 120) using FastOutSlowInEasing
                    0f at (400 + index * 120) using FastOutSlowInEasing
                    0f at 1000 using LinearEasing
                }, repeatMode = RepeatMode.Restart), label = "dot_$index"
            )
            Box(modifier = Modifier.size(8.dp).offset { IntOffset(0, offsetY.roundToInt()) }.background(color = MaterialTheme.colorScheme.primary, shape = CircleShape))
        }
    }
}

@Composable
fun SendStopButton(isGenerating: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    AnimatedContent(targetState = isGenerating, transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) }, label = "send_stop_toggle") { generating ->
        if (generating) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.error).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel(), onOpenSettings: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val streamingState by viewModel.streamingState.collectAsState()
    val groupedMessages by viewModel.groupedMessages.collectAsState()
    val attachedImage by viewModel.attachedImage.collectAsState()
    val attachedDocumentText by viewModel.attachedDocumentText.collectAsState()
    
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    if (streamingState.isGenerating && streamingState.streamingText.isEmpty()) {
                        item(key = "thinking_indicator") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFEAEAEA)) { ThinkingIndicator() }
                            }
                        }
                    }

                    if (streamingState.isGenerating && (streamingState.streamingText.isNotEmpty() || streamingState.streamingThinking != null)) {
                        item(key = "streaming_bubble") {
                            MessageBubble(
                                message = ChatMessage(role = "zora", text = streamingState.streamingText, thinking = streamingState.streamingThinking, isThinkingDone = !streamingState.isThinking),
                                isPlayingAudio = false, onPlayAudio = {}, onStopAudio = {}
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    groupedMessages.forEach { group ->
                        item(key = "header_${group.dateLabel}") {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
                                    Text(text = group.dateLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(group.messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                onDelete = { viewModel.deleteMessage(message.id) },
                                isPlayingAudio = false, onPlayAudio = {}, onStopAudio = {}
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // Attachment previews
                if (attachedImage != null || attachedDocumentText != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (attachedImage != null) {
                            Text("Image attached", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        }
                        if (attachedDocumentText != null) {
                            Text("Document attached", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Clean "Ask anything" Input Pill
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = inputText, onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            placeholder = { Text("Ask anything") }, maxLines = 4
                        )
                        val canSend = inputText.text.trim().isNotEmpty()
                        SendStopButton(
                            isGenerating = streamingState.isGenerating, canSend = canSend,
                            onSend = {
                                val text = inputText.text.trim()
                                if (text.isNotEmpty()) { viewModel.sendMessage(text); inputText = TextFieldValue("") }
                            },
                            onStop = { viewModel.cancelGeneration() }
                        )
                    }
                }
            }
            
            // Floating Settings Button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), shape = CircleShape)
            ) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onDelete: () -> Unit = {},
    isPlayingAudio: Boolean,
    onPlayAudio: (ByteArray) -> Unit,
    onStopAudio: () -> Unit
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(onDismissRequest = { showConfirm = false }, title = { Text("Delete message?") }, text = { Text("This action cannot be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } })
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        val bubbleShape = RoundedCornerShape(16.dp)
        val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.Transparent
        val contentColor = if (isUser) Color.White else MaterialTheme.colorScheme.onBackground
        val containerModifier = if (isUser) Modifier.widthIn(max = 280.dp).clip(bubbleShape).background(backgroundColor).padding(horizontal = 16.dp, vertical = 10.dp)
                                else Modifier.fillMaxWidth().clip(bubbleShape).background(backgroundColor).padding(horizontal = 4.dp, vertical = 10.dp)

        Box(modifier = containerModifier) {
            Column {
                message.thinking?.let { thinking ->
                    Text(text = thinking, style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = contentColor.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))
                }
                if (message.text.isNotBlank()) {
                    if (isUser) {
                        Text(text = message.text, color = contentColor, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        val renderItems = remember(message.text) { MessageParser.buildMergedLayoutBlocks(message.text) }
                        Column {
                            for (item in renderItems) {
                                when (item) {
                                    is androidx.compose.ui.text.AnnotatedString -> Text(text = item, color = contentColor, style = MaterialTheme.typography.bodyLarge)
                                    is Float -> Spacer(modifier = Modifier.height(item.dp))
                                    is MessageBlock.CodeBlock -> CodeBlockCard(language = item.language, code = item.code)
                                    is MessageBlock.LatexBlock -> LatexCard(formula = item.formula.trim())
                                    is MessageBlock.TableBlock -> TableCard(headers = item.headers, rows = item.rows)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                    }
                    if (!isUser) {
                        IconButton(onClick = { clipboardManager.setText(buildAnnotatedString { append(message.text) }) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

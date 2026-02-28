package com.telo.tinyzora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.telo.tinyzora.util.MediaUtils
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val messages = uiState.messages
    val attachedImage by viewModel.attachedImage.collectAsState()
    val attachedAudio by viewModel.attachedAudio.collectAsState()
    
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isRecording by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch {
                    val bitmap = MediaUtils.getDownscaledBitmap(context, it)
                    viewModel.attachImage(bitmap)
                }
            }
        }
    )

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                // TODO: Trigger actual audio recording
            }
        }
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("tinyZora") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (uiState.isGenerating && uiState.streamingText.isNotEmpty()) {
                    item {
                        MessageBubble(message = ChatMessage(role = "zora", text = uiState.streamingText))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                // Reverse the list before reversing layout to keep correct chronological order
                items(messages.reversed()) { message ->
                    MessageBubble(message = message)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                if (attachedImage != null) {
                    val imageBitmap = remember(attachedImage) { attachedImage!!.asImageBitmap() }
                    Box(modifier = Modifier.padding(end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Attached Image",
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = { viewModel.attachImage(null) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (attachedAudio != null) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Audio")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Audio Clip", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { viewModel.attachAudio(null) },
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                        RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Input Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = { imagePickerLauncher.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.Add, "Attach Image")
                }
                
                IconButton(onClick = { 
                    when {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                            if (!isRecording) {
                                isRecording = true
                                coroutineScope.launch {
                                    val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                                        onMaxDurationReached = { isRecording = false },
                                        stopSignal = { !isRecording }
                                    )
                                    viewModel.attachAudio(audioBytes)
                                    isRecording = false
                                }
                            } else {
                                isRecording = false
                            }
                        }
                        else -> {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }) {
                    if (isRecording) {
                        // Show a red squared stop icon while recording
                        Icon(Icons.Default.Close, "Stop Recording", tint = MaterialTheme.colorScheme.error)
                    } else {
                        Icon(Icons.Default.PlayArrow, "Attach Audio")
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    placeholder = { Text("Message tinyZora...") }
                )

                FilledIconButton(
                    onClick = {
                        val text = inputText.text.trim()
                        if (text.isNotEmpty() || attachedImage != null || attachedAudio != null) {
                            viewModel.sendMessage(text)
                            inputText = TextFieldValue("")
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = (inputText.text.trim().isNotEmpty() || attachedImage != null || attachedAudio != null) && !uiState.isGenerating
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message"
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    // Container alignment
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // Asymmetrical shapes
        val bubbleShape = if (isUser) {
            RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
        } else {
            RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
        }

        val backgroundColor = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }

        val contentColor = if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                message.bitmap?.let { img ->
                    val imageBitmap = remember(img) { img.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Attached Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                message.audio?.let {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Audio", tint = contentColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Audio Clip", color = contentColor, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

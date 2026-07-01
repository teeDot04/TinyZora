package com.telo.tinyzora.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.telo.tinyzora.core.richtext.MessageBlock
import com.telo.tinyzora.core.richtext.MessageParser
import com.telo.tinyzora.ui.chat.components.CodeBlockCard
import com.telo.tinyzora.ui.chat.components.MessageBodyThinking
import com.telo.tinyzora.ui.chat.components.LatexCard
import com.telo.tinyzora.ui.chat.components.TableCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun ThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dotCount = 3
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        repeat(dotCount) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1000
                        0f at 0 using LinearEasing
                        -10f at (200 + index * 120) using FastOutSlowInEasing
                        0f at (400 + index * 120) using FastOutSlowInEasing
                        0f at 1000 using LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .offset { IntOffset(0, offsetY.roundToInt()) }
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
            )
        }
    }
}

@Composable
fun WaveformAnimator(amplitudes: List<Float>) {
    val barCount = 20
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val baseAmplitude = if (amplitudes.isNotEmpty()) {
                amplitudes.getOrElse(index % amplitudes.size) { 0f }
            } else 0f
            val animatedHeight by infiniteTransition.animateFloat(
                initialValue = 4f,
                targetValue = (baseAmplitude * 36f + 4f).coerceIn(4f, 40f),
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 200 + index * 15, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}

@Composable
fun AttachmentPopup(
    visible: Boolean, onCamera: () -> Unit, onPhotos: () -> Unit,
    onMic: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + expandVertically(tween(250, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shadowElevation = 12.dp,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onCamera(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = "Camera", modifier = Modifier.size(22.dp))
                    }
                    Text("Camera", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onPhotos(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Rounded.Photo, contentDescription = "Photos", modifier = Modifier.size(22.dp))
                    }
                    Text("Photos", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onMic(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Microphone", modifier = Modifier.size(22.dp))
                    }
                    Text("Mic", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onFiles(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Rounded.Folder, contentDescription = "Files", modifier = Modifier.size(22.dp))
                    }
                    Text("Files", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun SendStopButton(isGenerating: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val scale by animateFloatAsState(targetValue = 1f, animationSpec = tween(200, easing = FastOutSlowInEasing), label = "button_scale")

    AnimatedContent(
        targetState = isGenerating,
        transitionSpec = { (scaleIn(tween(200)) + fadeIn(tween(200))) togetherWith (scaleOut(tween(150)) + fadeOut(tween(150))) },
        label = "send_stop_toggle"
    ) { generating ->
        if (generating) {
            val pulse by rememberInfiniteTransition(label = "stop_pulse").animateFloat(
                initialValue = 0.92f, targetValue = 1.05f,
                animationSpec = infiniteRepeatable(animation = tween(600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "pulse"
            )
            Box(
                modifier = Modifier.size(40.dp).scale(pulse).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.error).clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = "Stop generation", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel(), onOpenSettings: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val streamingState by viewModel.streamingState.collectAsState()
    val messages = uiState.messages
    
    val attachedImage by viewModel.attachedImage.collectAsState()
    val attachedAudio by viewModel.attachedAudio.collectAsState()
    val attachedDocumentText by viewModel.attachedDocumentText.collectAsState()

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var amplitudes by remember { mutableStateOf(listOf<Float>()) }
    val elapsedSeconds by remember { derivedStateOf { "%.1f s".format(elapsedMs.toFloat() / 1000f) } }
    
    val listState = rememberLazyListState()
    var attachPopupVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Screen-level Audio Player to prevent leaks and scroll state loss
    var currentlyPlayingId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var tempAudioFile by remember { mutableStateOf<File?>(null) }

    fun stopAudio() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentlyPlayingId = null
        tempAudioFile?.delete()
        tempAudioFile = null
    }

    fun playAudio(id: String, audioBytes: ByteArray) {
        stopAudio() // Ensure previous is fully cleaned up
        currentlyPlayingId = id
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val file = File.createTempFile("audio_playback_", ".mp3", context.cacheDir)
                file.writeBytes(audioBytes)
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        coroutineScope.launch(Dispatchers.Main) { stopAudio() }
                    }
                }
                withContext(Dispatchers.Main) {
                    tempAudioFile = file
                    mediaPlayer = player
                    player.start()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { stopAudio() }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.streamingState.value.isGenerating) {
                    viewModel.resetStreamingState()
                }
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                stopAudio()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopAudio() 
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { viewModel.attachImage(it) } }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap -> 
            bitmap?.let {
                // Safely compress the bitmap to disk on IO thread and grab the URI
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val file = File(context.cacheDir, "camera_preview_${UUID.randomUUID()}.png")
                        file.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
                        withContext(Dispatchers.Main) {
                            viewModel.attachImage(Uri.fromFile(file))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.attachFile(it) } }
    )

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                isRecording = true
                elapsedMs = 0L
                coroutineScope.launch {
                    val startTime = System.currentTimeMillis()
                    val timerJob = launch {
                        while (isRecording) {
                            elapsedMs = System.currentTimeMillis() - startTime
                            delay(100)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        val ampHistory = mutableListOf<Float>()
                        val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                            onAmplitude = { amp ->
                                ampHistory.add(amp)
                                val window = ampHistory.takeLast(20)
                                // Fix Compose snapshot thread safety
                                coroutineScope.launch(Dispatchers.Main) { amplitudes = window }
                            },
                            onMaxDurationReached = { isRecording = false; timerJob.cancel() },
                            stopSignal = { !isRecording }
                        )
                        timerJob.cancel()
                        val snapshot = com.telo.tinyzora.util.AudioUtils.summariseAmplitudes(ampHistory)
                        withContext(Dispatchers.Main) {
                            viewModel.attachAudio(audioBytes)
                            viewModel.setAudioAmplitudeSnapshot(snapshot)
                            isRecording = false
                            amplitudes = listOf()
                        }
                    }
                }
            }
        }
    )

    fun startRecording() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                if (!isRecording) {
                    isRecording = true
                    elapsedMs = 0L
                    amplitudes = listOf()
                    coroutineScope.launch {
                        val startTime = System.currentTimeMillis()
                        val timerJob = launch {
                            while (isRecording) {
                                elapsedMs = System.currentTimeMillis() - startTime
                                delay(100)
                            }
                        }
                        withContext(Dispatchers.IO) {
                            val ampHistory = mutableListOf<Float>()
                            val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                                onAmplitude = { amp ->
                                    ampHistory.add(amp)
                                    val window = ampHistory.takeLast(20)
                                    // Fix Compose snapshot thread safety
                                    coroutineScope.launch(Dispatchers.Main) { amplitudes = window }
                                },
                                onMaxDurationReached = { isRecording = false; timerJob.cancel() },
                                stopSignal = { !isRecording }
                            )
                            timerJob.cancel()
                            val snapshot = com.telo.tinyzora.util.AudioUtils.summariseAmplitudes(ampHistory)
                            withContext(Dispatchers.Main) {
                                viewModel.attachAudio(audioBytes)
                                viewModel.setAudioAmplitudeSnapshot(snapshot)
                                isRecording = false
                                amplitudes = listOf()
                            }
                        }
                    }
                } else {
                    isRecording = false
                }
            }
            else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .imePadding()
            ) {
                // Clean inline grouping to prevent heavy produceState recalculations on every UI change
                val groupedMessages = remember(messages) {
                    messages.groupBy { message ->
                        val date = java.time.Instant.ofEpochMilli(message.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        val today = java.time.LocalDate.now()
                        when {
                            date == today -> "Today"
                            date == today.minusDays(1) -> "Yesterday"
                            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    if (streamingState.isGenerating && streamingState.streamingText.isEmpty()) {
                        item(key = "thinking_indicator") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFEAEAEA), modifier = Modifier.padding(bottom = 8.dp)) {
                                    ThinkingIndicator()
                                }
                            }
                        }
                    }
                    if (streamingState.isGenerating && (streamingState.streamingText.isNotEmpty() || streamingState.streamingThinking != null)) {
                        item(key = "streaming_bubble") {
                            val streamingMessage = remember(streamingState.streamingText, streamingState.streamingThinking) {
                                ChatMessage(
                                    role = "zora",
                                    text = streamingState.streamingText,
                                    thinking = streamingState.streamingThinking,
                                    isThinkingDone = !streamingState.isThinking
                                )
                            }
                            MessageBubble(
                                message = streamingMessage, 
                                isPlayingAudio = false,
                                onPlayAudio = {},
                                onStopAudio = {}
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    
                    groupedMessages.forEach { (dateStr, dateMessages) ->
                        items(dateMessages, key = { it.id }) { message ->
                            val onDeleteLambda = remember(message.id) { { viewModel.deleteMessage(message.id) } }
                            MessageBubble(
                                message = message, 
                                onDelete = onDeleteLambda,
                                isPlayingAudio = currentlyPlayingId == message.id,
                                onPlayAudio = { bytes: ByteArray -> playAudio(message.id, bytes) },
                                onStopAudio = { stopAudio() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // Header rendered as item to prevent reverseLayout inversion
                        item(key = "header_$dateStr") {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
                                    Text(
                                        text = dateStr,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Attachment previews
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (attachedImage != null) {
                        Box(modifier = Modifier.padding(end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                            AsyncImage(
                                model = attachedImage,
                                contentDescription = "Attached Image",
                                modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { viewModel.attachImage(null) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            ) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
                        }
                    }
                    if (attachedAudio != null) {
                        Box(modifier = Modifier.padding(vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Audio")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Audio Clip", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.attachAudio(null) },
                                    modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                ) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                    if (attachedDocumentText != null) {
                        Box(modifier = Modifier.padding(vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PostAdd, contentDescription = "Document", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Document attached (${attachedDocumentText!!.split(" ").size} words)", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.clearDocument() },
                                    modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                ) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.BottomStart) {
                    AttachmentPopup(
                        visible = attachPopupVisible,
                        onCamera = { cameraLauncher.launch(null) },
                        onPhotos = { photoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onMic = { startRecording() },
                        onFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onDismiss = { attachPopupVisible = false }
                    )
                }

                // Clean "Ask anything" Input Pill
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val clipRotation by animateFloatAsState(targetValue = if (attachPopupVisible) 45f else 0f, animationSpec = tween(250, easing = FastOutSlowInEasing), label = "clip_rotation")
                        IconButton(onClick = { attachPopupVisible = !attachPopupVisible }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = if (attachPopupVisible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.scale(1f))
                        }

                        if (isRecording) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { isRecording = false; amplitudes = listOf() }) { Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurface) }
                                Box(modifier = Modifier.weight(1f)) { WaveformAnimator(amplitudes) }
                                Text(elapsedSeconds, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp))
                                IconButton(onClick = { isRecording = false }) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send Audio", tint = MaterialTheme.colorScheme.primary) }
                            }
                        } else {
                            TextField(
                                value = inputText, onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                                placeholder = { Text("Ask anything") }, maxLines = 4
                            )
                            val canSend = (inputText.text.trim().isNotEmpty() || attachedImage != null || attachedAudio != null || attachedDocumentText != null)
                            SendStopButton(
                                isGenerating = streamingState.isGenerating,
                                canSend = canSend,
                                onSend = {
                                    val text = inputText.text.trim()
                                    if (text.isNotEmpty() || attachedImage != null || attachedAudio != null || attachedDocumentText != null) {
                                        viewModel.sendMessage(text)
                                        inputText = TextFieldValue("")
                                        attachPopupVisible = false
                                    }
                                },
                                onStop = { viewModel.cancelGeneration() }
                            )
                        }
                    }
                }
            }
            
            // Restored Floating Settings Button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp).background(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), shape = CircleShape)
            ) { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete message?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        val bubbleShape = RoundedCornerShape(16.dp)
        val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.Transparent
        val contentColor = if (isUser) Color.White else MaterialTheme.colorScheme.onBackground
        val containerModifier = if (isUser) {
            Modifier.widthIn(max = 280.dp).clip(bubbleShape).background(backgroundColor).padding(horizontal = 16.dp, vertical = 10.dp)
        } else {
            Modifier.fillMaxWidth().clip(bubbleShape).background(backgroundColor).padding(horizontal = 4.dp, vertical = 10.dp)
        }

        Box {
            Box(modifier = containerModifier) {
                Column {
                    message.image?.let { img ->
                        AsyncImage(
                            model = img.uriString,
                            contentDescription = "Attached Image",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).clip(RoundedCornerShape(8.dp)).padding(bottom = 8.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    message.documentName?.let { docName ->
                        Row(modifier = Modifier.padding(bottom = 8.dp).background(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PostAdd, contentDescription = "Document", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = docName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    message.audio?.let { audioData ->
                        val bars = if (message.audioAmplitudes.isNotEmpty()) message.audioAmplitudes else List(25) { (0.1f + (it % 5) * 0.12f + (it % 3) * 0.05f).coerceIn(0.1f, 1f) }
                        
                        val infiniteTransition = rememberInfiniteTransition(label = "playback_transition")
                        
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 4.dp).background(color = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), shape = RoundedCornerShape(20.dp)).border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(20.dp)).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (isPlayingAudio) onStopAudio() else onPlayAudio(audioData.data)
                                },
                                modifier = Modifier.size(40.dp).background(color = if (isPlayingAudio) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, shape = CircleShape)
                            ) {
                                Icon(imageVector = if (isPlayingAudio) Icons.Rounded.Stop else Icons.Default.PlayArrow, contentDescription = if (isPlayingAudio) "Stop" else "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Row(modifier = Modifier.weight(1f).height(36.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                bars.forEachIndexed { index, amp ->
                                    key(index) {
                                        val pulse by infiniteTransition.animateFloat(
                                            initialValue = 0.7f, targetValue = 1.2f,
                                            animationSpec = infiniteRepeatable(animation = tween(400 + index * 20, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                                            label = "bar_pulse_$index"
                                        )
                                        val targetHeight = if (isPlayingAudio) (amp * 28f + 4f).coerceIn(4f, 32f) * pulse else (amp * 28f + 4f).coerceIn(4f, 32f)
                                        Box(modifier = Modifier.width(3.dp).height(targetHeight.dp.coerceIn(4.dp, 36.dp)).clip(RoundedCornerShape(1.5.dp)).background(if (isPlayingAudio && index < bars.size / 2) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.4f)))
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("0:${(message.audioAmplitudes.size * 0.06f).toInt().toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp))
                            }
                        }
                    }

                    message.thinking?.let { thinking ->
                        MessageBodyThinking(thinkingText = thinking, inProgress = !message.isThinkingDone)
                        Spacer(modifier = Modifier.height(8.dp))
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isUser) 0.dp else 4.dp),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isUser) {
                            IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, message.text)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Zora response via"))
                                },
                                modifier = Modifier.size(28.dp)
                            ) { Icon(Icons.Default.Share, contentDescription = "Share", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)) }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(
                            onClick = { clipboardManager.setText(buildAnnotatedString { append(message.text) }) },
                            modifier = Modifier.size(28.dp)
                        ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}
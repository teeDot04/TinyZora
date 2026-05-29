package com.telo.tinyzora.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telo.tinyzora.core.richtext.MessageBlock
import com.telo.tinyzora.core.richtext.MessageParser
import com.telo.tinyzora.ui.chat.components.CodeBlockCard
import com.telo.tinyzora.ui.chat.components.MessageBodyThinking

import com.telo.tinyzora.ui.chat.components.LatexCard
import com.telo.tinyzora.ui.chat.components.TableCard
import com.telo.tinyzora.ui.chat.ChatMessage
import com.telo.tinyzora.util.MediaUtils
import com.telo.tinyzora.ui.theme.BgDarkStart
import com.telo.tinyzora.ui.theme.BgDarkEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// ─── Animated 3-dot loading indicator ────────────────────────────────────────
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
                initialValue = 0f,
                targetValue = 0f,
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
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )
        }
    }
}

// ─── Sound-reactive waveform recording animation ─────────────────────────────
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
                    animation = tween(
                        durationMillis = 200 + index * 15,
                        easing = FastOutSlowInEasing
                    ),
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

// ─── Animated attachment popup pill ──────────────────────────────────────────
@Composable
fun AttachmentPopup(
    visible: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onMic: () -> Unit,
    onFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + expandVertically(
            tween(250, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Bottom
        ),
        exit = fadeOut(tween(180)) + androidx.compose.animation.shrinkVertically(
            tween(200, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Bottom
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { onCamera(); onDismiss() },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = "Camera", modifier = Modifier.size(22.dp))
                    }
                    Text("Camera", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                // Photos
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { onPhotos(); onDismiss() },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Icon(Icons.Rounded.Photo, contentDescription = "Photos", modifier = Modifier.size(22.dp))
                    }
                    Text("Photos", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                // Mic
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { onMic(); onDismiss() },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Microphone", modifier = Modifier.size(22.dp))
                    }
                    Text("Mic", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                // Files
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { onFiles(); onDismiss() },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = "Files", modifier = Modifier.size(22.dp))
                    }
                    Text("Files", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ─── Squircle Stop / Send button ─────────────────────────────────────────────
@Composable
fun SendStopButton(
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isGenerating) 1f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "button_scale"
    )

    AnimatedContent(
        targetState = isGenerating,
        transitionSpec = {
            (scaleIn(tween(200)) + fadeIn(tween(200))) togetherWith
                    (scaleOut(tween(150)) + fadeOut(tween(150)))
        },
        label = "send_stop_toggle"
    ) { generating ->
        if (generating) {
            // Squircle stop button — red, pulsing
            val pulse by rememberInfiniteTransition(label = "stop_pulse").animateFloat(
                initialValue = 0.92f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .scale(pulse)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Stop,
                    contentDescription = "Stop generation",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            // Normal send icon button
            IconButton(
                onClick = onSend,
                enabled = canSend
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

// ─── Main ChatScreen ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val streamingState by viewModel.streamingState.collectAsState()
    // derivedStateOf means the LazyColumn only recomposes when the messages list
    // actually changes — NOT on every streaming token emission.
    val messages by remember { derivedStateOf { uiState.messages } }
    val attachedImage by viewModel.attachedImage.collectAsState()
    val attachedAudio by viewModel.attachedAudio.collectAsState()
    val attachedDocumentText by viewModel.attachedDocumentText.collectAsState()

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var amplitudes by remember { mutableStateOf(listOf<Float>()) }
    val elapsedSeconds by remember {
        androidx.compose.runtime.derivedStateOf { "%.1f s".format(elapsedMs.toFloat() / 1000f) }
    }
    val listState = rememberLazyListState()
    var attachPopupVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // consolidateMemory call removed to prevent redundant Disk I/O
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch {
                    val bitmap = MediaUtils.getDownscaledBitmap(context, it)
                    viewModel.attachImage(bitmap)
                }
            }
        }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap ->
            bitmap?.let { viewModel.attachImage(it) }
        }
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> 
            uri?.let { viewModel.attachFile(it) }
        }
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
                    val ampHistory = mutableListOf<Float>()
                    val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                        onAmplitude = { amp ->
                            ampHistory.add(amp)
                            val window = ampHistory.takeLast(20)
                            amplitudes = window
                        },
                        onMaxDurationReached = {
                            isRecording = false
                            timerJob.cancel()
                        },
                        stopSignal = { !isRecording }
                    )
                    timerJob.cancel()
                    val snapshot = com.telo.tinyzora.util.AudioUtils.summariseAmplitudes(ampHistory)
                    viewModel.attachAudio(audioBytes)
                    viewModel.setAudioAmplitudeSnapshot(snapshot)
                    isRecording = false
                    amplitudes = listOf()
                }
            }
        }
    )

    fun startRecording() {
        when {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
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
                        val ampHistory = mutableListOf<Float>()
                        val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                            onAmplitude = { amp ->
                                ampHistory.add(amp)
                                val window = ampHistory.takeLast(20)
                                amplitudes = window
                            },
                            onMaxDurationReached = {
                                isRecording = false
                                timerJob.cancel()
                            },
                            stopSignal = { !isRecording }
                        )
                        timerJob.cancel()
                        val snapshot = com.telo.tinyzora.util.AudioUtils.summariseAmplitudes(ampHistory)
                        viewModel.attachAudio(audioBytes)
                        viewModel.setAudioAmplitudeSnapshot(snapshot)
                        isRecording = false
                        amplitudes = listOf()
                    }
                } else {
                    isRecording = false
                }
            }
            else -> {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .imePadding()
            ) {
            // Group messages by Date
            val groupedMessages = remember(messages) {
                messages.groupBy { message ->
                    val date = java.time.Instant.ofEpochMilli(message.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    
                    val today = java.time.LocalDate.now()
                    when {
                        date == today -> "Today"
                        date == today.minusDays(1) -> "Yesterday"
                        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                    }
                }
            }

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
                // 3-dot indicator while waiting for first token
                if (streamingState.isGenerating && streamingState.streamingText.isEmpty()) {
                    item(key = "thinking_indicator") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFEAEAEA),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                ThinkingIndicator()
                            }
                        }
                    }
                }
                // Streaming bubble while tokens arrive
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
                        MessageBubble(message = streamingMessage)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                groupedMessages.forEach { (dateStr, dateMessages) ->
                    items(dateMessages, key = { it.id }) { message ->
                        val onDeleteLambda = remember(message.id) { { viewModel.deleteMessage(message.id) } }
                        MessageBubble(
                            message = message,
                            onDelete = onDeleteLambda
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    stickyHeader(key = "header_$dateStr") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                            ) {
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
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
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
                // Document / PDF attachment chip
                if (attachedDocumentText != null) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PostAdd,
                                contentDescription = "Document",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Document attached (${attachedDocumentText!!.split(" ").size} words)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { viewModel.clearDocument() },
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

            // Attachment animated popup — sits just above the input pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                AttachmentPopup(
                    visible = attachPopupVisible,
                    onCamera = { cameraLauncher.launch(null) },
                    onPhotos = {
                        photoPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onMic = { startRecording() },
                    onFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onDismiss = { attachPopupVisible = false }
                )
            }

            // Input pill
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Paperclip icon button — animated rotation when popup is open
                    val clipRotation by animateFloatAsState(
                        targetValue = if (attachPopupVisible) 45f else 0f,
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                        label = "clip_rotation"
                    )
                    IconButton(onClick = { attachPopupVisible = !attachPopupVisible }) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach",
                            tint = if (attachPopupVisible) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.scale(1f)
                        )
                    }



                    // Centre: recording waveform OR text field
                    if (isRecording) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Cancel recording
                            IconButton(onClick = { isRecording = false; amplitudes = listOf() }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            // Sound-reactive waveform
                            Box(modifier = Modifier.weight(1f)) {
                                WaveformAnimator(amplitudes)
                            }
                            // Timer label
                            Text(
                                elapsedSeconds,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            // Send audio
                            IconButton(onClick = { isRecording = false }) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = "Send Audio",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
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
                            placeholder = { Text("Ask anything") },
                            maxLines = 4
                        )

                        // Animated Send / Stop button
                        val canSend = (inputText.text.trim().isNotEmpty() || attachedImage != null || attachedAudio != null || attachedDocumentText != null)
                        SendStopButton(
                            isGenerating = streamingState.isGenerating,
                            canSend = canSend,
                            onSend = {
                                val text = inputText.text.trim()
                                if (text.isNotEmpty() || attachedImage != null || attachedAudio != null) {
                                    viewModel.sendMessage(text)
                                    inputText = TextFieldValue("")
                                    attachPopupVisible = false
                                }
                            },
                            onStop = { viewModel.cancelGeneration() }
                        )
                }
            }
            } // Closes the central Column container. Now we are back in the root Box!
            }

            // Floating Settings Button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
} // Closes ChatScreen

// ─── MessageBubble ────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,

    onDelete: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
            val bubbleShape = RoundedCornerShape(16.dp)

            // Dynamic Bubble coloring
            val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            else Color.Transparent // Floating AI content
            
            // Dynamic text high-contrast mapping
            val contentColor = if (isUser) Color.White else MaterialTheme.colorScheme.onBackground

            val containerModifier = if (isUser) {
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(bubbleShape)
                    .background(backgroundColor)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            } else {
                Modifier
                    .fillMaxWidth() // Full width for edge-to-edge tables and code blocks
                    .clip(bubbleShape)
                    .background(backgroundColor)
                    .padding(horizontal = 4.dp, vertical = 10.dp)
            }

            Box {
                Box(modifier = containerModifier) {
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
                        // Document / PDF chip in bubble
                        message.documentName?.let { docName ->
                            Row(
                                modifier = Modifier
                                    .padding(bottom = 8.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.PostAdd,
                                    contentDescription = "Document",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = docName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        message.audio?.let {
                            var isPlaying by remember { mutableStateOf(false) }
                            val bars = if (message.audioAmplitudes.isNotEmpty())
                                message.audioAmplitudes
                            else List(25) { (0.1f + (it % 5) * 0.12f + (it % 3) * 0.05f).coerceIn(0.1f, 1f) }

                            // Premium glassmorphic container
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp, top = 4.dp)
                                    .background(
                                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Play button — sleek solid circle
                                IconButton(
                                    onClick = { isPlaying = !isPlaying },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            color = if (isPlaying) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Stop" else "Play",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                // Static/animated waveform bars — only animate when playing
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    bars.forEachIndexed { index, amp ->
                                        key(index) {
                                            // Only run the infinite animation when actively playing.
                                            // When idle, every bar is static — zero animation goroutines.
                                            val targetHeight = if (isPlaying) {
                                                val infiniteTransition = rememberInfiniteTransition(label = "playback_$index")
                                                val pulse by infiniteTransition.animateFloat(
                                                    initialValue = 0.7f,
                                                    targetValue = 1.2f,
                                                    animationSpec = infiniteRepeatable(
                                                        animation = tween(400 + index * 20, easing = FastOutSlowInEasing),
                                                        repeatMode = RepeatMode.Reverse
                                                    ),
                                                    label = "bar_pulse_$index"
                                                )
                                                (amp * 28f + 4f).coerceIn(4f, 32f) * pulse
                                            } else {
                                                (amp * 28f + 4f).coerceIn(4f, 32f)
                                            }
                                            
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .height(targetHeight.dp.coerceIn(4.dp, 36.dp))
                                                    .clip(RoundedCornerShape(1.5.dp))
                                                    .background(
                                                        if (isPlaying && index < bars.size / 2) MaterialTheme.colorScheme.primary
                                                        else contentColor.copy(alpha = 0.4f)
                                                    )
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                
                                // Sleek timing capsule
                                Box(
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "0:${(message.audioAmplitudes.size * 0.06f).toInt().toString().padStart(2, '0')}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                      )
                            }
                        }

                        // Thinking content
                        message.thinking?.let { thinking ->
                            MessageBodyThinking(
                                thinkingText = thinking,
                                inProgress = !message.isThinkingDone
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (message.text.isNotBlank()) {
                            if (isUser) {
                                    Text(
                                        text = message.text,
                                        color = contentColor,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                } else {
                                    val renderItems = remember(message.text) { MessageParser.buildMergedLayoutBlocks(message.text) }

                                    Column {
                                        for (item in renderItems) {
                                            when (item) {
                                                is androidx.compose.ui.text.AnnotatedString -> {
                                                    Text(
                                                        text = item,
                                                        color = contentColor,
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                }
                                                is Float -> {
                                                    Spacer(modifier = Modifier.height(item.dp))
                                                }
                                                is MessageBlock.CodeBlock -> CodeBlockCard(language = item.language, code = item.code)
                                                is MessageBlock.LatexBlock -> LatexCard(formula = item.formula.trim())
                                                is MessageBlock.TableBlock -> TableCard(headers = item.headers, rows = item.rows)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Inline Action Row
                            Spacer(modifier = Modifier.height(4.dp))
                            var showConfirm by remember { mutableStateOf(false) }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = if (isUser) 0.dp else 4.dp),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isUser) {
                                    IconButton(
                                        onClick = { showConfirm = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = contentColor.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                } else {
                                    IconButton(
                                        onClick = { showConfirm = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = contentColor.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
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
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Share",
                                            tint = contentColor.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.buildAnnotatedString { append(message.text) })
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = contentColor.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            // Delete Confirmation row
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showConfirm,
                                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(180)),
                                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Delete message?",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentColor.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(
                                        onClick = { showConfirm = false },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Cancel", color = contentColor.copy(alpha = 0.7f))
                                    }
                                    TextButton(
                                        onClick = {
                                            showConfirm = false
                                            onDelete()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

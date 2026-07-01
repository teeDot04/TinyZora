
package com.telo.tinyzora.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
                    FilledIconButton(onClick = { onCamera(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = "Camera", modifier = Modifier.size(22.dp))
                    }
                    Text("Camera", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onPhotos(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Icon(Icons.Rounded.Photo, contentDescription = "Photos", modifier = Modifier.size(22.dp))
                    }
                    Text("Photos", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onMic(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Microphone", modifier = Modifier.size(22.dp))
                    }
                    Text("Mic", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { onFiles(); onDismiss() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
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

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.streamingState.value.isGenerating) {
                    viewModel.resetStreamingState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { viewModel.attachImage(it) } }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        // Note: Needs URI mapping in a real flow, stubbed safely
        onResult = { /* Camera logic requires FileProvider URI */ }
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
                    // Background thread execution to prevent ANRs
                    withContext(Dispatchers.IO) {
                        val ampHistory = mutableListOf<Float>()
                        val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                            onAmplitude = { amp ->
                                ampHistory.add(amp)
                                val window = ampHistory.takeLast(20)
                                amplitudes = window
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
                        // Background thread execution to prevent ANRs
                        withContext(Dispatchers.IO) {
                            val ampHistory = mutableListOf<Float>()
                            val audioBytes = com.telo.tinyzora.util.AudioUtils.recordAudio(
                                onAmplitude = { amp ->
                                    ampHistory.add(amp)
                                    val window = ampHistory.takeLast(20)
                                    amplitudes = window
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
                // Safely group messages on a background thread to prevent jank
                val groupedMessages by produceState(initialValue = emptyMap<String, List<ChatMessage>>(), key1 = messages) {
                    value = withContext(Dispatchers.Default) {
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
                            MessageBubble(message = streamingMessage)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    
                    groupedMessages.forEach { (dateStr, dateMessages) ->
                        items(dateMessages, key = { it.id }) { message ->

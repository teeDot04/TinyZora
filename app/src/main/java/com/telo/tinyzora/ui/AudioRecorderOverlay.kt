package com.telo.tinyzora.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun AudioRecorderOverlay(
    onRecordingComplete: (android.net.Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val audioRecorder = remember { AudioRecorder(context) }
    
    var recordingDuration by remember { mutableStateOf(0L) }
    var swipeOffset by remember { mutableStateOf(0f) }
    var isRecording by remember { mutableStateOf(false) }
    
    val cancelThreshold = -150f
    
    // Start recording on composition
    LaunchedEffect(Unit) {
        try {
            audioRecorder.startRecording()
            isRecording = true
            
            // Update timer
            while (isRecording) {
                delay(100)
                recordingDuration += 100
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onCancel()
        }
    }
    
    // Pulsing animation for mic
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.release()
        }
    }
    
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset < cancelThreshold) {
                                // Cancel recording
                                isRecording = false
                                audioRecorder.cancelRecording()
                                onCancel()
                            } else {
                                // Complete recording
                                isRecording = false
                                val file = audioRecorder.stopRecording()
                                if (file != null) {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    onRecordingComplete(uri)
                                } else {
                                    onCancel()
                                }
                            }
                            swipeOffset = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeOffset = (swipeOffset + dragAmount).coerceAtMost(0f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Timer
                Text(
                    text = formatDuration(recordingDuration),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                
                // Cancel hint with fade based on swipe
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(1f - (abs(swipeOffset) / abs(cancelThreshold)).coerceIn(0f, 1f))
                ) {
                    Text(
                        text = "←",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Slide to cancel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Microphone icon with pulse and swipe offset
                Box(
                    modifier = Modifier
                        .offset(x = swipeOffset.dp)
                        .size(80.dp)
                        .scale(scale)
                        .background(Color.Red, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Simple pulsing circle for recording indicator
                }
                
                // Trash icon appears when swiping
                if (swipeOffset < cancelThreshold / 2) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Cancel",
                        tint = Color.Red,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha((abs(swipeOffset) / abs(cancelThreshold)).coerceIn(0f, 1f))
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Release hint
                Text(
                    text = "Release to send",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / 1000) / 60
    return "%02d:%02d".format(minutes, seconds)
}

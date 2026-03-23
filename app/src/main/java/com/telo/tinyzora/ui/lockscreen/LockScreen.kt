package com.telo.tinyzora.ui.lockscreen

import android.app.Application
import androidx.compose.animation.core.Animatable

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.security.UserPreferences
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun LockScreen(
    onUnlock: () -> Unit,
    onResetComplete: () -> Unit
) {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    var pinInput by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(pinInput) {
        if (pinInput.length == 8) {
            if (userPrefs.verifyPin(pinInput)) {
                showError = false
                onUnlock()
            } else {
                showError = true
                scope.launch {
                    shakeOffset.animateTo(20f, tween(50))
                    shakeOffset.animateTo(-20f, tween(50))
                    shakeOffset.animateTo(20f, tween(50))
                    shakeOffset.animateTo(-20f, tween(50))
                    shakeOffset.animateTo(0f, tween(50))
                    pinInput = ""
                    showError = false
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Application") },
            text = { Text("This will permanently delete your PIN, wipe all chat logs, and erase Zora's entire memory. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            userPrefs.clearPin()
                            val chatHistoryFile = File(context.filesDir, "chat_history.txt")
                            if (chatHistoryFile.exists()) chatHistoryFile.delete()
                            val memoryStore = MemoryStore(context.applicationContext as Application)
                            memoryStore.clearAllMemories()
                            showResetDialog = false
                            onResetComplete()
                        }
                    }
                ) {
                    Text("Confirm Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ─── Arc-ring PIN indicator ───────────────────────────────────
        Box(
            modifier = Modifier
                .size(160.dp)
                .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
            contentAlignment = Alignment.Center
        ) {
            val ringColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
            val activeColor = MaterialTheme.colorScheme.primary
            val errorColor = MaterialTheme.colorScheme.error

            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 10.dp.toPx()
                val segmentCount = 8
                val sweepAngle = 360f / segmentCount
                val gapAngle = 6f
                val drawSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                for (i in 0 until segmentCount) {
                    val startAngle = -90f + (i * sweepAngle) + (gapAngle / 2)
                    val activeSweepAngle = sweepAngle - gapAngle
                    val isActive = i < pinInput.length

                    drawArc(
                        color = if (showError) errorColor.copy(alpha = 0.7f)
                                else if (isActive) activeColor
                                else ringColor,
                        startAngle = startAngle,
                        sweepAngle = activeSweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = drawSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Text(
                text = "tinyZora",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (showError) "Incorrect PIN" else "Enter PIN",
            color = if (showError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(56.dp))

        // ─── Keypad ───────────────────────────────────────────────────
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "<")
        )

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for (row in keys) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (key in row) {
                        LockKeypadButton(
                            text = key,
                            onClick = {
                                when {
                                    key == "<" -> {
                                        if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1)
                                    }
                                    key.isNotEmpty() && pinInput.length < 8 -> {
                                        pinInput += key
                                        showError = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = { showResetDialog = true }) {
            Text(
                "Forgot PIN? Reset App",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun LockKeypadButton(text: String, onClick: () -> Unit) {
    if (text.isEmpty()) {
        Spacer(modifier = Modifier.size(72.dp))
        return
    }

    val bgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (text == "<") {
            Icon(
                Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(
                text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

package com.telo.tinyzora.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PinLockScreen(onPinSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val maxPinLength = 8
    val correctPin = "00000000"
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val handleKeyPress: (String) -> Unit = { digit ->
        if (pin.length < maxPinLength) {
            pin += digit
            if (pin.length == maxPinLength) {
                if (pin == correctPin) {
                    onPinSuccess()
                } else {
                    coroutineScope.launch {
                        // Shake animation logic
                        shakeOffset.animateTo(25f, animationSpec = tween(50))
                        shakeOffset.animateTo(-25f, animationSpec = tween(50))
                        shakeOffset.animateTo(25f, animationSpec = tween(50))
                        shakeOffset.animateTo(-25f, animationSpec = tween(50))
                        shakeOffset.animateTo(0f, animationSpec = tween(50))
                        pin = ""
                    }
                }
            }
        }
    }

    val handleDelete: () -> Unit = {
        if (pin.isNotEmpty()) {
            pin = pin.dropLast(1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
            contentAlignment = Alignment.Center
        ) {
            val ringColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
            val activeColor = MaterialTheme.colorScheme.primary

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
                    val isActive = i < pin.length

                    drawArc(
                        color = if (isActive) activeColor else ringColor,
                        startAngle = startAngle,
                        sweepAngle = activeSweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = drawSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Square)
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

        Spacer(modifier = Modifier.height(64.dp))

        PinPad(onKeyPress = handleKeyPress, onDelete = handleDelete)
    }
}

@Composable
fun PinPad(onKeyPress: (String) -> Unit, onDelete: () -> Unit) {
    val buttons = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "DEL")
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        buttons.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { label ->
                    if (label.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else {
                        PinButton(
                            label = label,
                            onClick = {
                                if (label == "DEL") onDelete() else onKeyPress(label)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PinButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        shape = CircleShape
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
    }
}

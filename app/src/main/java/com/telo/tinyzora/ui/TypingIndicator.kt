package com.telo.tinyzora.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        
        @Composable
        fun Dot(offset: Int) {
            val y by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = offset, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ), 
                label = "dot"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { translationY = y }
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
            )
        }
        
        Dot(0)
        Dot(200)
        Dot(400)
    }
}

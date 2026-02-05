package com.telo.tinyzora.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telo.tinyzora.R
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("> ") }
    val fullText = "> TinyZora_"
    
    // Typing effect
    LaunchedEffect(Unit) {
        startAnimation = true
        delay(500) // Initial pause
        
        fullText.forEachIndexed { index, _ ->
            if (index > 1) { // Skip initial "> "
                text = fullText.substring(0, index + 1)
                delay(100)
            }
        }
        delay(800) // Pause at end
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            
            // Logo Animation
            AnimatedVisibility(
                visible = startAnimation,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) + fadeIn()
            ) {
                // Using the rounded icon drawable
                Image(
                    painter = painterResource(id = R.drawable.app_icon_source),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Typing Text
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

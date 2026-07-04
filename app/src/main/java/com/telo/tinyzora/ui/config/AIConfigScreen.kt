package com.telo.tinyzora.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.telo.tinyzora.core.security.UserPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    
    var temperature by remember { mutableFloatStateOf(userPrefs.getTemperature()) }
    var topK by remember { mutableIntStateOf(userPrefs.getTopK()) }
    var topP by remember { mutableFloatStateOf(userPrefs.getTopP()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Configuration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("Inference Parameters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))

            // Temperature
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Temperature: $temperature", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = temperature, onValueChange = { temperature = it; userPrefs.setTemperature(it) }, valueRange = 0f..2f)
                }
            }

            // Top-K
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Top-K: $topK", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = topK.toFloat(), onValueChange = { topK = it.toInt(); userPrefs.setTopK(it.toInt()) }, valueRange = 1f..100f, steps = 98)
                }
            }

            // Top-P
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Top-P: $topP", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = topP, onValueChange = { topP = it; userPrefs.setTopP(it) }, valueRange = 0f..1f)
                }
            }
        }
    }
}

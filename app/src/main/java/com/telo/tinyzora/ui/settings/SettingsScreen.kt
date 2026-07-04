package com.telo.tinyzora.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.telo.tinyzora.core.inference.InferenceEngineImpl
import com.telo.tinyzora.core.security.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    
    var isLoading by remember { mutableStateOf(false) }
    var currentModel by remember { mutableStateOf(userPrefs.getModelPath().ifEmpty { "No model selected" }) }
    
    var benchmarkResult by remember { mutableStateOf("Not run") }
    var isBenchmarking by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isLoading = true
                try {
                    val fileName = "model.gguf"
                    val destFile = File(context.filesDir, fileName)
                    
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    val newPath = destFile.absolutePath
                    userPrefs.setModelPath(newPath)
                    currentModel = "Loaded: $fileName"
                } catch (e: Exception) {
                    currentModel = "Error: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("App Modules", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))

            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI Configuration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Manage models, temperature, and inference settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Current Model:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(currentModel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(onClick = { filePickerLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading, shape = RoundedCornerShape(12.dp)) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Text("Import .gguf Model")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isBenchmarking = true
                                benchmarkResult = "Running benchmark (this may take a minute)..."
                                try {
                                    val modelPath = userPrefs.getModelPath()
                                    if (modelPath.isEmpty()) {
                                        benchmarkResult = "Error: Please import a model first!"
                                    } else {
                                        // Create a fresh engine instance for benchmarking
                                        val engine = InferenceEngineImpl.getInstance(context)
                                        try {
                                            engine.loadModel(modelPath)
                                            val res = engine.bench(512, 128, 1, 1)
                                            benchmarkResult = res
                                            engine.cleanUp()
                                        } catch (e: Exception) {
                                            benchmarkResult = "Benchmark Error: ${e.message}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    benchmarkResult = "Error: ${e.message}"
                                } finally {
                                    isBenchmarking = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBenchmarking
                    ) {
                        if (isBenchmarking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isBenchmarking) "Running Benchmark..." else "Run Benchmark")
                    }
                    
                    if (benchmarkResult != "Not run" && benchmarkResult.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = benchmarkResult,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }

            Text("Privacy & Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Application Lock (Coming Soon)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

package com.telo.tinyzora.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.telo.tinyzora.core.inference.InferenceManager
import com.telo.tinyzora.core.inference.InferenceEngineImpl
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.security.UserPreferences
import com.telo.tinyzora.util.ConsoleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAIConfig: () -> Unit = {}) {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    val engine = remember { InferenceEngineImpl.getInstance(context) }
    val inferenceManager = remember { InferenceManager(context, MemoryStore(context)) }

    var isLoading by remember { mutableStateOf(false) }
    var currentModel by remember { mutableStateOf(userPrefs.getModelPath().ifEmpty { "No model selected" }) }
    var benchmarkResult by remember { mutableStateOf("") }
    var isBenchmarking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // FIXED: Proper file picker with verification
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isLoading = true
                errorMessage = null
                try {
                    val dest = File(context.filesDir, "model.gguf")

                    // Delete existing file first
                    if (dest.exists()) {
                        dest.delete()
                    }

                    // Copy file with verification
                    context.contentResolver.openInputStream(it)?.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // VERIFY: Check file was copied correctly
                    if (!dest.exists()) {
                        errorMessage = "Error: File copy failed - destination not created"
                        isLoading = false
                        return@launch
                    }

                    if (dest.length() == 0L) {
                        dest.delete()
                        errorMessage = "Error: Copied file is empty (0 bytes)"
                        isLoading = false
                        return@launch
                    }

                    ConsoleLogger.d("SettingsScreen", "Model copied: ${dest.length()} bytes to ${dest.absolutePath}")

                    // Save path
                    userPrefs.setModelPath(dest.absolutePath)
                    currentModel = "model.gguf (${dest.length() / (1024 * 1024)} MB)"

                    // Initialize model
                    ConsoleLogger.d("SettingsScreen", "Initializing model...")
                    val success = inferenceManager.initialise()
                    if (!success) {
                        errorMessage = "Model failed to initialize (check logs)"
                        ConsoleLogger.e("SettingsScreen", "Model initialization failed")
                    } else {
                        errorMessage = null
                        ConsoleLogger.d("SettingsScreen", "Model loaded successfully!")
                    }

                } catch (e: Exception) {
                    errorMessage = "Error: ${e.message}"
                    ConsoleLogger.e("SettingsScreen", "File picker error", e)
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isBenchmarking) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("App Modules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))

            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clickable { if (!isBenchmarking) onOpenAIConfig() },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI Configuration", style = MaterialTheme.typography.titleMedium)
                            Text("Temperature, Top-K, Top-P, context size", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Text("Model:", style = MaterialTheme.typography.bodySmall)
                    Text(currentModel, style = MaterialTheme.typography.bodyMedium)

                    // Show error message if present
                    errorMessage?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { filePicker.launch("*/*") },
                        Modifier.fillMaxWidth(),
                        enabled = !isLoading && !isBenchmarking
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp))
                        else Text("Import .gguf Model")
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isBenchmarking = true
                                benchmarkResult = "Running..."
                                errorMessage = null
                                try {
                                    val path = userPrefs.getModelPath()
                                    if (path.isEmpty()) {
                                        benchmarkResult = "Import a model first"
                                        return@launch
                                    }

                                    // Use InferenceManager
                                    val success = inferenceManager.initialise()
                                    if (!success) {
                                        benchmarkResult = "Model not initialized"
                                        return@launch
                                    }

                                    benchmarkResult = inferenceManager.bench(512, 128, 1, 1)
                                } catch (e: Exception) {
                                    benchmarkResult = "Error: ${e.message}"
                                    ConsoleLogger.e("SettingsScreen", "Benchmark error", e)
                                } finally {
                                    isBenchmarking = false
                                }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = !isBenchmarking && !isLoading
                    ) {
                        if (isBenchmarking) CircularProgressIndicator(Modifier.size(20.dp))
                        else Text("Run Benchmark")
                    }

                    if (benchmarkResult.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                benchmarkResult,
                                Modifier.padding(12.dp),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

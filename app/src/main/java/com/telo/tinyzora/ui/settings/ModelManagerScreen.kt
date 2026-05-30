package com.telo.tinyzora.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telo.tinyzora.core.security.UserPreferences
import com.telo.tinyzora.ui.settings.components.ModelImportDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    var models by remember { mutableStateOf(userPrefs.getImportedModels()) }
    var currentModelPath by remember { mutableStateOf(userPrefs.getModelPath()) }

    var showImportDialog by remember { mutableStateOf<Uri?>(null) }

    var temperature by remember { mutableFloatStateOf(userPrefs.getTemperature()) }
    var topK by remember { mutableIntStateOf(userPrefs.getTopK()) }
    var topP by remember { mutableFloatStateOf(userPrefs.getTopP()) }
    var maxTokens by remember { mutableIntStateOf(userPrefs.getMaxTokens()) }

    val snackbarHostState = remember { SnackbarHostState() }
    var switchMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(switchMessage) {
        val msg = switchMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        switchMessage = null
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> showImportDialog = uri }
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI Configuration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { pickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Import Model")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("Inference Parameters")
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ParameterSlider("Temperature", temperature, 0f, 1f) {
                            temperature = it
                            userPrefs.setTemperature(it)
                        }
                        ParameterSlider("Top-K", topK.toFloat(), 1f, 100f, isInt = true) {
                            topK = it.toInt()
                            userPrefs.setTopK(it.toInt())
                        }
                        ParameterSlider("Top-P", topP, 0f, 1f) {
                            topP = it
                            userPrefs.setTopP(it)
                        }
                        ParameterSlider("Max Tokens", maxTokens.toFloat(), 128f, 16384f, isInt = true) {
                            maxTokens = it.toInt()
                            userPrefs.setMaxTokens(it.toInt())
                        }
                    }
                }
            }

            item {
                SectionHeader("Available Models")
            }

            if (models.isEmpty()) {
                item {
                    Text(
                        "No custom models imported yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            items(models) { model ->
                ModelItem(
                    model = model,
                    isSelected = model.path == currentModelPath,
                    onSelect = {
                        currentModelPath = model.path
                        userPrefs.setModelPath(model.path)
                        switchMessage = "Switching to ${model.name}… chat will be ready shortly."
                    },
                    onDelete = {
                        val updated = models.filter { it.path != model.path }
                        models = updated
                        userPrefs.setImportedModels(updated)
                        if (currentModelPath == model.path) {
                            currentModelPath = ""
                            userPrefs.setModelPath("")
                        }
                        try { java.io.File(model.path).delete() } catch (e: Exception) {}
                    }
                )
            }
        }

        showImportDialog?.let { uri ->
            ModelImportDialog(
                uri = uri,
                onDismiss = { showImportDialog = null },
                onImportComplete = { newModel ->
                    val updated = models + newModel
                    models = updated
                    userPrefs.setImportedModels(updated)
                    showImportDialog = null
                }
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        content = content
    )
}

@Composable
fun ParameterSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    isInt: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                if (isInt) value.toInt().toString() else "%.2f".format(value),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
fun ModelItem(
    model: com.telo.tinyzora.core.inference.ImportedModel,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${model.fileSize / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

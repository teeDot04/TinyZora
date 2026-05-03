package com.telo.tinyzora.ui.settings.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.telo.tinyzora.core.inference.ImportedModel
import com.telo.tinyzora.core.inference.LlmConfig
import com.telo.tinyzora.util.ConsoleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ModelImportDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onImportComplete: (ImportedModel) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var fileName by remember { mutableStateOf("model.task") }
    var fileSize by remember { mutableLongStateOf(0L) }

    LaunchedEffect(uri) {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }
    }

    Dialog(onDismissRequest = { if (!isImporting) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isImporting) "Importing Model..." else "Import Model",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (!isImporting) {
                    Text(
                        text = "File: $fileName",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Size: ${fileSize / (1024 * 1024)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                isImporting = true
                                scope.launch {
                                    val imported = performImport(context, uri, fileName, fileSize) {
                                        progress = it
                                    }
                                    if (imported != null) {
                                        onImportComplete(imported)
                                    } else {
                                        isImporting = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Import")
                        }
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

private suspend fun performImport(
    context: Context,
    uri: Uri,
    fileName: String,
    totalSize: Long,
    onProgress: (Float) -> Unit
): ImportedModel? = withContext(Dispatchers.IO) {
    try {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val destFile = File(modelsDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalSize > 0) {
                        withContext(Dispatchers.Main) {
                            onProgress(totalRead.toFloat() / totalSize)
                        }
                    }
                }
            }
        }
        
        ImportedModel(
            name = fileName,
            path = destFile.absolutePath,
            fileSize = totalSize,
            config = LlmConfig()
        )
    } catch (e: Exception) {
        ConsoleLogger.e("ModelImport", "Import failed: ${e.message}")
        null
    }
}

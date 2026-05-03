package com.telo.tinyzora.ui.settings

import android.content.Context
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telo.tinyzora.util.ConsoleLogger
import com.telo.tinyzora.core.security.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    chatHistoryFile: File,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAIConfig: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by ConsoleLogger.logs.collectAsState()
    val userPrefs = remember { UserPreferences(context) }
    
    val chatViewModel: com.telo.tinyzora.ui.chat.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val uiState by chatViewModel.uiState.collectAsState()
    
    var isPinSet by remember { mutableStateOf(userPrefs.isPinSet()) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogInput by remember { mutableStateOf("") }
    var pinDialogError by remember { mutableStateOf(false) }
    
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val content = if (chatHistoryFile.exists()) chatHistoryFile.readText() else "No chat history found."
                            context.contentResolver.openOutputStream(it)?.use { out ->
                                out.write(content.toByteArray())
                            }
                        }
                        ConsoleLogger.i("Settings", "Successfully exported chat history to $uri")
                    } catch (e: Exception) {
                        ConsoleLogger.e("Settings", "Failed to export history", e)
                    }
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Settings")
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    when {
                                        uiState.engineError -> MaterialTheme.colorScheme.error
                                        uiState.isEngineReady -> MaterialTheme.colorScheme.tertiary
                                        else -> androidx.compose.ui.graphics.Color(0xFFFFA500)
                                    }
                                )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .imePadding()
        ) {
            
            // App Actions Section
            Text(
                "App Modules",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Memory Vault") },
                        supportingContent = { Text("View and edit Zora's long-term persisted memories") },
                        leadingContent = {
                            Icon(Icons.Default.Star, contentDescription = "Memory")
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onOpenMemory() }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    ListItem(
                        headlineContent = { Text("AI Configuration") },
                        supportingContent = { Text("Manage models, temperature, and inference settings") },
                        leadingContent = {
                            Icon(Icons.Default.Create, contentDescription = "AI Config")
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onOpenAIConfig() }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    ListItem(
                        headlineContent = { Text("Export Chat History") },
                        supportingContent = { Text("Save your WhatsApp-style persisted chats to a .txt file") },
                        leadingContent = {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable {
                            exportLauncher.launch("tinyZora_chat_history.txt")
                        }
                    )
                }
            }

            // Security Section
            Text(
                "Privacy & Security",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                ListItem(
                    headlineContent = { Text(if (isPinSet) "Disable Application Lock" else "Enable Application Lock") },
                    supportingContent = { Text("Secure your chats and memories with an 8-digit PIN") },
                    leadingContent = {
                        Icon(Icons.Default.Lock, contentDescription = "Lock Settings", tint = if (isPinSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable {
                        if (isPinSet) {
                            userPrefs.clearPin()
                            isPinSet = false
                        } else {
                            showPinDialog = true
                            pinDialogInput = ""
                            pinDialogError = false
                        }
                    }
                )
            }

            if (showPinDialog) {
                AlertDialog(
                    onDismissRequest = { showPinDialog = false },
                    title = { Text("Set 8-Digit PIN") },
                    text = {
                        Column {
                            Text("This PIN will be required to open tinyZora.")
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pinDialogInput,
                                onValueChange = { 
                                    if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                                        pinDialogInput = it
                                        pinDialogError = false
                                    }
                                },
                                isError = pinDialogError,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true
                            )
                            if (pinDialogError) {
                                Text("PIN must be exactly 8 digits.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (pinDialogInput.length == 8) {
                                userPrefs.setPin(pinDialogInput)
                                isPinSet = true
                                showPinDialog = false
                            } else {
                                pinDialogError = true
                            }
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPinDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Console Tracker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "System Intelligence",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                
                TextButton(onClick = { ConsoleLogger.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant) // Light background for TradeFlow aesthetic
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "No logs captured.",
                        color = androidx.compose.ui.graphics.Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(items = logs, key = { it.id }) { logEntry ->
                            val log = logEntry.message
                            val color = when {
                                log.startsWith("[") && log.contains("] E/") -> MaterialTheme.colorScheme.error
                                log.startsWith("[") && log.contains("] D/") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.secondary 
                            }
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = color,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

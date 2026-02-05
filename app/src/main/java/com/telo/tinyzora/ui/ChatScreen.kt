package com.telo.tinyzora.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures // Add this
import android.content.Context // Add this
import android.widget.Toast // Add this
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.items
import com.telo.tinyzora.core.LifeChapter
import com.telo.tinyzora.ui.TinyViewModel
import com.telo.tinyzora.ui.ChatMessage
import com.telo.tinyzora.ui.AppStatus
import com.telo.tinyzora.data.ChatSession

// ==========================================
// 🎨 MATERIAL YOU DESIGN (Dynamic Theme)
// ==========================================

import com.telo.tinyzora.core.ChatMode
import androidx.compose.material.icons.filled.ArrowDropDown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: TinyViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val appStatus by viewModel.appStatus.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    
    // Toast Feedback
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    
    // Auto-scroll logic
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Drawer State
    var drawerTab by remember { mutableStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Rename Dialog State
    val showRenameDialog = remember { mutableStateOf(false) }
    val sessionToRename = remember { mutableStateOf<ChatSession?>(null) }
    
    // --- Model/Settings Logic (Simplified for brevity, kept same logic) ---
    val prefs = remember { context.getSharedPreferences("tiny_prefs", android.content.Context.MODE_PRIVATE) }
    var useGpu by remember { mutableStateOf(prefs.getBoolean("last_use_gpu", true)) }
    
    var showMemoriesScreen by remember { mutableStateOf(false) }
    var showAudioRecorder by remember { mutableStateOf(false) }
    
    // Audio permission launcher
    val audioPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showAudioRecorder = true }

    // Launchers
    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> 
        if (uris.size > 5) {
            viewModel.handleImages(uris.take(5))
            android.widget.Toast.makeText(context, "Max 5 images allowed. First 5 selected.", android.widget.Toast.LENGTH_LONG).show()
        } else {
            viewModel.handleImages(uris)
        }
    }

    if (showMemoriesScreen) {
        MemoriesScreen(
            viewModel = viewModel,
            onBack = { showMemoriesScreen = false }
        )
        return
    }

    if (showAudioRecorder) {
        AudioRecorderOverlay(
            onRecordingComplete = { uri ->
                viewModel.processRecordedAudio(uri)
                showAudioRecorder = false
            },
            onCancel = { showAudioRecorder = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(320.dp)
            ) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(modifier = Modifier.height(16.dp))
                
                // DRAWER TABS (Chats | Settings)
                TabRow(
                    selectedTabIndex = drawerTab,
                    containerColor = Color.Transparent,
                    divider = {},
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[drawerTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    Tab(
                        selected = drawerTab == 0,
                        onClick = { drawerTab = 0 },
                        text = { Text("Chats") },
                        icon = { Icon(Icons.Default.List, contentDescription = null) }
                    )
                     Tab(
                        selected = drawerTab == 1,
                        onClick = { drawerTab = 1 },
                        text = { Text("Models") },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

                if (drawerTab == 0) {
                    // --- CHATS TAB ---
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "My Chats",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { 
                            viewModel.startNewChat() 
                            scope.launch { drawerState.close() }
                        }) {
                             Icon(Icons.Default.Create, "New Chat", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    val sessions by viewModel.sessions.collectAsState()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp)
                    ) {
                        items(sessions) { session ->
                             Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.loadChat(session)
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                             ) {
                                  val firstChar = session.title.firstOrNull()?.toString() ?: "#"
                                  Text(firstChar, style = MaterialTheme.typography.titleMedium)
                                  Spacer(modifier = Modifier.width(16.dp))
                                  
                                  Column(modifier = Modifier.weight(1f)) {
                                      Text(
                                          text = session.title.ifBlank { "Untitled" },
                                          style = MaterialTheme.typography.bodyLarge,
                                          color = MaterialTheme.colorScheme.onSurface,
                                          maxLines = 1
                                      )
                                      val date = java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(session.timestamp))
                                      Text(
                                          text = date,
                                          style = MaterialTheme.typography.bodySmall,
                                          color = MaterialTheme.colorScheme.onSurfaceVariant
                                      )
                                  }
                                  
                                  // Edit Button
                                  IconButton(onClick = { 
                                      sessionToRename.value = session
                                      showRenameDialog.value = true
                                  }) {
                                      Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.outline.copy(alpha=0.6f))
                                  }

                                  IconButton(onClick = { viewModel.deleteChat(session) }) {
                                      Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.outline.copy(alpha=0.4f))
                                  }
                             }
                             HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                } else {
                    // --- SETTINGS / BRAIN TAB ---
                    val availableModels by viewModel.availableModels.collectAsState()
                    
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        
                        Text("Active Model", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        if (availableModels.isEmpty()) {
                            Text("No models found. Please import one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        } else {
                            var selectedModelName = prefs.getString("last_model_path", null)?.let { java.io.File(it).name }
                            
                            availableModels.forEach { modelInfo ->
                                val modelName = modelInfo.name
                                val isSelected = selectedModelName == modelName
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { 
                                        viewModel.loadModelByName(modelName, useGpu)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected, 
                                            onClick = { viewModel.loadModelByName(modelName, useGpu) }
                                        )
                                        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                                            Text(
                                                text = modelName, 
                                                style = MaterialTheme.typography.bodySmall, 
                                                maxLines = 1
                                            )
                                            // CAPABILITY BADGE
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (modelInfo.isMultimodal) {
                                                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Vision Capable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                } else {
                                                    Icon(Icons.Default.Description, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Text Only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                                }
                                            }
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteModel(modelName) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                             Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error.copy(0.6f))
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Use GPU Execution", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = useGpu, 
                                onCheckedChange = { 
                                    useGpu = it
                                    prefs.edit().putBoolean("last_use_gpu", it).apply()
                                }
                            )
                         }
                         
                        Spacer(modifier = Modifier.height(16.dp))

                        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                            contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                        ) { uri: android.net.Uri? -> uri?.let { viewModel.loadModelFromUri(it, useGpu) } }

                        OutlinedButton(
                            onClick = { launcher.launch("*/*") }, 
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import New Model")
                        }

                        if (appStatus == AppStatus.ACTIVE) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.unloadAi() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Close, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Unload Brain (Cool Down)")
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Memories", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        Text("Manage what TinyZora knows about you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = { 
                                showMemoriesScreen = true 
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("View Memories")
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        // SYSTEM LOGS (Restored)
                        Text("System Logs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
                        val logs by viewModel.systemLogs.collectAsState()
                        
                        SelectionContainer {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                logs.takeLast(10).forEach { log ->
                                    Text(
                                        text = log, 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             // Simplified Title
                             Text("TinyZora", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                            // Minimal Status Dot
                            if (appStatus == AppStatus.LOADING) {
                                Spacer(modifier = Modifier.height(4.dp))
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f) // Translucent
                    )
                )
            },
            // NO bottomBar here! Input is floating.
            bottomBar = {} 
        ) { paddingValues ->
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding() // Important for keyboard
            ) {
                // 1. BACKGROUND (Optional subtle gradient or solid)
                
                // 2. CHAT LIST
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp) // Bottom padding for floating bar
                ) {
                    items(messages) { msg ->
                        MaterialMessageBubble(message = msg)
                    }

                    if (uiState == "Thinking...") {
                        item {
                            TypingIndicator(modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        }
                    }
                }

                // 3. FLOATING INPUT BAR
                val pendingImages by viewModel.pendingImages.collectAsState() 
                val pendingAudio by viewModel.pendingAudio.collectAsState()
                
                MaterialInputBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank() || pendingImages.isNotEmpty() || pendingAudio.isNotEmpty()) {
                            viewModel.sendCommand(inputText)
                            inputText = ""
                        }
                    },
                    onAttach = { imageLauncher.launch("image/*") },
                    onRecord = { audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                    currentMode = currentMode,
                    onModeSelected = { viewModel.setMode(it) },
                    pendingImages = pendingImages,
                    pendingAudio = pendingAudio,
                    onClearImages = { viewModel.clearPendingImages() },
                    isGenerating = viewModel.isGenerating.collectAsState().value,
                    onStop = { viewModel.stopGeneration() }
                )
            }
        }
    }
    
    if (showRenameDialog.value && sessionToRename.value != null) {
        RenameSessionDialog(
            session = sessionToRename.value!!,
            onDismiss = { showRenameDialog.value = false },
            onConfirm = { newTitle ->
                viewModel.renameSession(sessionToRename.value!!, newTitle)
                showRenameDialog.value = false
            }
        )
    }
}

// --- GROK-STYLE BUBBLES ---

@Composable
fun MaterialMessageBubble(message: ChatMessage) {
    if (message.isUser) {
            // USER: Right-aligned, Pill Bubble, No Avatar
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
            modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier
                    .wrapContentWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Message", message.text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        })
                    }
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    // Images
                    if (message.imageUris.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .wrapContentWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 4.dp)
                        ) {
                            message.imageUris.forEach { uriString ->
                                AsyncImagePreview(uri = android.net.Uri.parse(uriString))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                    }
                    
                    // Audio
                    if (message.audioClips.isNotEmpty()) {
                         Row(
                            modifier = Modifier.padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier=Modifier.width(4.dp))
                            val duration = message.audioClips.sumOf { it.getDurationInSeconds().toDouble() }
                            Text("${duration.toInt()}s Audio", style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    
                    if (message.text.isNotBlank()) {
                         Text(
                             text = message.text,
                             modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                             style = MaterialTheme.typography.bodyLarge,
                             color = MaterialTheme.colorScheme.onPrimaryContainer
                         )
                    }
                }
            }
        }
    } else {
        // AI: Left-aligned
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 32.dp, start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // AVATAR
            Surface(
                modifier = Modifier.size(28.dp).offset(y = 4.dp), // Slight offset
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha=0.1f)
            ) {
                Icon(
                    Icons.Default.AutoAwesome, 
                    contentDescription = null, 
                    modifier = Modifier.padding(4.dp), 
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // CONTENT
            Column {
                if (message.text.isNotEmpty()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Box(modifier = Modifier.pointerInput(Unit) {
                         detectTapGestures(onLongPress = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Message", message.text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                         })
                    }) {
                        MarkdownText(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                lineHeight = 24.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// --- FLOATING INPUT CAPSULE ---

@Composable
fun MaterialInputBar(
    modifier: Modifier = Modifier,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRecord: () -> Unit,
    currentMode: ChatMode,
    onModeSelected: (ChatMode) -> Unit,
    pendingImages: List<android.net.Uri> = emptyList(),
    pendingAudio: List<com.telo.tinyzora.util.AudioClip> = emptyList(),
    onClearImages: () -> Unit = {},
    isGenerating: Boolean = false,
    onStop: () -> Unit = {}
) {
    Column(modifier = modifier) {
        // PENDING PREVIEWS
        if (pendingImages.isNotEmpty() || pendingAudio.isNotEmpty()) {
            Surface(
                modifier = Modifier.padding(bottom = 8.dp).align(Alignment.End),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp
            ) {
                Box {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        pendingImages.forEach { uri ->
                            AsyncImagePreview(uri = uri)
                        }
                        if (pendingAudio.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("${pendingAudio.size} clip(s)", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    
                    // Close Button
                    IconButton(
                        onClick = onClearImages,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(24.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.heightIn(min = 56.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // VISUALS & AUDIO STRIPPED (Temporary)
                /*
                // Attach Button
                IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Add, 
                        "Attach", 
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Mic Button
                IconButton(onClick = onRecord, modifier = Modifier.size(40.dp)) {
                    Icon(
                         Icons.Default.Mic,
                         "Record Audio",
                         tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                */

                // MODE SWITCHER
                Box {
                    var showModeMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showModeMenu = true }, modifier = Modifier.size(40.dp)) {
                         Icon(
                             Icons.Default.ArrowDropDown, 
                             contentDescription = "Switch Mode",
                             tint = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.size(28.dp).rotate(180f)
                         )
                    }
                    
                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                        offset = DpOffset(0.dp, (-16).dp)
                    ) {
                        ChatMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(mode.label, fontWeight = FontWeight.Bold)
                                        Text(mode.systemInstruction.take(30) + "...", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    onModeSelected(mode)
                                    showModeMenu = false
                                },
                                 leadingIcon = {
                                    if (mode == currentMode) Icon(Icons.Default.Check, null)
                                }
                            )
                        }
                    }
                }
                
                // Input Field
                Box(
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty() && pendingImages.isEmpty()) {
                        Text("Ask TinyZora...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    } else if (text.isEmpty() && pendingImages.isNotEmpty()) {
                        Text("Add a caption...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 4,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
                
                // Send / Stop Button
                if (isGenerating) {
                     IconButton(
                         onClick = onStop,
                         modifier = Modifier.size(40.dp)
                     ) {
                         Surface(
                             shape = RoundedCornerShape(12.dp),
                             color = MaterialTheme.colorScheme.error,
                             modifier = Modifier.size(32.dp)
                         ) {
                             Icon(
                                 Icons.Filled.Stop,
                                 contentDescription = "Stop",
                                 tint = MaterialTheme.colorScheme.onError,
                                 modifier = Modifier.padding(6.dp)
                             )
                         }
                     }
                } else {
                    val canSend = text.isNotBlank() || pendingImages.isNotEmpty() || pendingAudio.isNotEmpty()
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.size(40.dp)
                    ) {
                         Surface(
                             shape = CircleShape,
                             color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.12f),
                             modifier = Modifier.size(32.dp)
                         ) {
                             Icon(
                                 Icons.AutoMirrored.Filled.ArrowBack, 
                                 contentDescription = "Send",
                                 tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.38f),
                                 modifier = Modifier.padding(6.dp).graphicsLayer { rotationZ = 135f }
                             )
                         }
                    }
                }
            }
        }
    }
}

@Composable
fun AsyncImagePreview(uri: android.net.Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            // Target 200px for UI thumbnails to save memory
            bitmap = com.telo.tinyzora.util.ImageUtils.decodeSampledBitmapFromUri(context, uri, 200, 200)
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(4.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        // Loading / Error State
        Box(modifier = Modifier.padding(4.dp).size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
             Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Markdown Text Placeholder
@Composable
fun MarkdownText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val annotatedString = remember(text) {
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()
        val lines = text.split("\n")
        
        lines.forEachIndexed { index, line ->
            var currentLine = line
            var isListItem = false
            
            // Handle Bullet List
            if (currentLine.trim().startsWith("- ") || currentLine.trim().startsWith("* ")) {
                builder.append("•  ") // Bullet
                currentLine = currentLine.trim().drop(2)
                isListItem = true
            }

            // Parse Bold and Italic
            var currentIndex = 0
            // Extremely basic parser: Matches **bold** or *italic*
            // Note: This regex is simple and doesn't handle nested or mixed perfectly, but works for basic cases.
            val regex = "(\\*\\*|\\*)(.*?)(\\1)".toRegex()
            
            val matches = regex.findAll(currentLine)
            var lastMatchEnd = 0
            
            matches.forEach { match ->
                // Plain text before match
                if (match.range.first > lastMatchEnd) {
                    builder.append(currentLine.substring(lastMatchEnd, match.range.first))
                }
                
                // Styled Text
                val tag = match.groupValues[1]
                val content = match.groupValues[2]
                
                if (tag == "**") {
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                    builder.append(content)
                    builder.pop()
                } else if (tag == "*") {
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    builder.append(content)
                    builder.pop()
                }
                
                lastMatchEnd = match.range.last + 1
            }
            
            // Remaining text
            if (lastMatchEnd < currentLine.length) {
                builder.append(currentLine.substring(lastMatchEnd))
            }
            
            if (index < lines.size - 1) {
                builder.append("\n")
            }
        }
        
        builder.toAnnotatedString()
    }

    Text(text = annotatedString, style = style, color = color)
}

@Composable
fun AnimatedModeSelector(
    currentMode: ChatMode,
    onModeSelected: (ChatMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val containerColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceVariant,
        label = "color"
    )

    Surface(
        modifier = Modifier
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.1f)),
        tonalElevation = if (expanded) 8.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header (Always Visible)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if(expanded) Icons.Default.Close else Icons.Default.AutoAwesome, // Dynamic Icon
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedContent(targetState = currentMode, label = "text") { mode ->
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded Options
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha=0.1f))
                Spacer(modifier = Modifier.height(8.dp))
                
                ChatMode.values().forEach { mode ->
                    val isSelected = mode == currentMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                onModeSelected(mode)
                                expanded = false 
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onModeSelected(mode); expanded = false },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                             Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                             )
                             // Optional: Add description if ChatMode has it
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenameSessionDialog(
    session: ChatSession,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(session.title) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Chat") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Chat Title") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

package com.telo.tinyzora.ui.memory

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telo.tinyzora.core.memory.MemoryEntry
import com.telo.tinyzora.core.memory.MemoryStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val memoryStore = remember { MemoryStore(context.applicationContext as Application) }

    var reminderEntries by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var vaultEntries by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun reload() {
        val all = memoryStore.load().entries
        reminderEntries = all.filter { it.type == "reminder" && it.due != null }
        vaultEntries = all.filter { it.type != "reminder" }
    }

    LaunchedEffect(Unit) { reload() }

    fun deleteEntry(entry: MemoryEntry) {
        scope.launch {
            val current = memoryStore.load()
            val updated = current.entries.toMutableList().also { it.remove(entry) }
            memoryStore.save(current.copy(entries = updated))
            reload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {

            item {
                Text(
                    "Active Reminders",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (reminderEntries.isEmpty()) {
                item {
                    Text(
                        "No pending reminders.",
                        color = androidx.compose.ui.graphics.Color.Gray,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                items(reminderEntries, key = { it.content }) { entry ->
                    val displayText = entry.content + if (entry.due != null) " (Due: ${entry.due})" else ""
                    MemoryItemCard(
                        text = displayText,
                        icon = Icons.Default.Notifications,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onDelete = { deleteEntry(entry) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    "Long-Term Memory",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (vaultEntries.isEmpty()) {
                item {
                    Text(
                        "Zora hasn't formed any distinct memories yet.",
                        color = androidx.compose.ui.graphics.Color.Gray,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                items(vaultEntries, key = { it.content }) { entry ->
                    val icon: ImageVector = when (entry.type) {
                        "fact" -> Icons.Default.PushPin
                        "preference" -> Icons.Default.Settings
                        "correction" -> Icons.Default.Edit
                        else -> Icons.Default.Circle
                    }
                    MemoryItemCard(
                        text = entry.content.trim(),
                        icon = icon,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onDelete = { deleteEntry(entry) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun MemoryItemCard(
    text: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (expanded) text else text.take(60) + if (text.length > 60) "…" else "",
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                // Delete button
                IconButton(
                    onClick = { showConfirm = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete memory",
                        tint = contentColor.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Inline confirmation row — appears only after tapping delete
            AnimatedVisibility(
                visible = showConfirm,
                enter = expandVertically(tween(180)),
                exit = shrinkVertically(tween(180))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Remove this memory?",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showConfirm = false }) {
                        Text("Cancel", color = contentColor.copy(alpha = 0.6f))
                    }
                    TextButton(onClick = {
                        showConfirm = false
                        onDelete()
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

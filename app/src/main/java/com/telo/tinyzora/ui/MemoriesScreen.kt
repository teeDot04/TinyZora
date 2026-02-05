package com.telo.tinyzora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telo.tinyzora.data.FactEntity
import com.telo.tinyzora.data.PrefEntity
import com.telo.tinyzora.data.ReminderEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    viewModel: TinyViewModel,
    onBack: () -> Unit
) {
    val facts by viewModel.facts.collectAsState()
    val prefs by viewModel.prefs.collectAsState()
    val reminders by viewModel.reminders.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshMemories()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // FACTS SECTION
            item { 
                SectionHeader(
                    title = "Facts (What I Know)", 
                    icon = Icons.Default.AutoAwesome // Reusing AutoAwesome for "Brain" concept
                ) 
            }
            if (facts.isEmpty()) {
                item { EmptyState("No facts stored yet.") }
            } else {
                items(facts) { item ->
                    MemoryItem(
                        text = item.content,
                        onDelete = { viewModel.deleteMemory(item) }
                    )
                }
            }

            // PREFS SECTION
            item { 
                SectionHeader(
                    title = "Preferences (How I Act)", 
                    icon = Icons.Default.Settings 
                ) 
            }
            if (prefs.isEmpty()) {
                item { EmptyState("No preferences stored yet.") }
            } else {
                items(prefs) { item ->
                    MemoryItem(
                        text = item.content,
                        onDelete = { viewModel.deleteMemory(item) }
                    )
                }
            }

            // REMINDERS SECTION
            item { 
                SectionHeader(
                    title = "Active Reminders", 
                    icon = Icons.Default.Notifications // Using Notifications/Alarm concept
                ) 
            }
            if (reminders.isEmpty()) {
                item { EmptyState("No active reminders.") }
            } else {
                items(reminders) { item ->
                    MemoryItem(
                        text = "${item.content} (at ${item.dueTime})",
                        onDelete = { viewModel.deleteMemory(item) },
                        isReminder = true
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
fun EmptyState(msg: String) {
    Text(
        text = msg,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun MemoryItem(
    text: String, 
    onDelete: () -> Unit,
    isReminder: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isReminder) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Delete, 
                    "Delete", 
                    tint = MaterialTheme.colorScheme.error.copy(alpha=0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

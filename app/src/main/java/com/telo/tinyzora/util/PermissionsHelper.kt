package com.telo.tinyzora.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Handles Runtime Permissions (Camera) and Special Settings (Notifications).
 */
@Composable
fun RequestPermissionsEffect(
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    var showExplanation by remember { mutableStateOf(false) }

    // 1. Camera Permission Launcher (For Flashlight)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) onPermissionsGranted()
        }
    )

    // 2. Check on Start
    LaunchedEffect(Unit) {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        
        if (!hasCamera) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            onPermissionsGranted()
        }
    }
}

/**
 * Checks if "The Bouncer" (Notification Listener) is active.
 * If not, prompts user to open Settings.
 */
fun checkNotificationAccess(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName) == true
}

fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

package com.telo.tinyzora.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.*

class MobileActionExecutor(private val context: Context) {

    sealed class ActionResult {
        data class Success(val message: String) : ActionResult()
        data class Error(val message: String) : ActionResult()
        object None : ActionResult()
    }

    fun execute(input: String): ActionResult {
        val lower = input.lowercase()

        return when {
            // Flashlight
            lower.contains("flashlight") || lower.contains("torch") -> {
                val turnOn = lower.containsAny("on", "turn on", "enable", "start")
                val duration = extractDuration(input)
                executeFlashlight(turnOn, duration)
            }
            // Volume control
            lower.contains("volume") -> {
                val level = extractNumber(input)
                when {
                    lower.containsAny("up", "increase", "raise") -> executeSetVolume(level ?: 5, increase = true)
                    lower.containsAny("down", "decrease", "lower") -> executeSetVolume(level ?: -5, increase = false)
                    level != null -> executeSetVolume(level, absolute = true)
                    else -> ActionResult.Error("Specify volume level or up/down")
                }
            }
            // Brightness
            lower.contains("brightness") -> {
                val level = extractNumber(input)
                if (level != null) executeBrightness(level)
                else ActionResult.Error("Specify brightness level (0-100)")
            }
            // Calendar
            lower.contains("calendar") && lower.containsAny("read", "show", "list") -> {
                executeReadCalendar()
            }
            lower.contains("calendar") && lower.containsAny("add", "create", "schedule") -> {
                ActionResult.Success("Calendar creation requires date/time parsing - coming soon!")
            }
            // Notifications
            lower.contains("notification") && lower.containsAny("read", "show", "list") -> {
                executeReadNotifications()
            }
            // Open Apps
            lower.startsWith("open") || lower.contains("launch") -> {
                val appName = lower.replace("open", "").replace("launch", "").trim()
                executeOpenApp(appName)
            }
            else -> ActionResult.None
        }
    }

    private fun executeFlashlight(turnOn: Boolean, durationMs: Long): ActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, turnOn)

            if (turnOn && durationMs > 0) {
                GlobalScope.launch {
                    delay(durationMs)
                    try { cameraManager.setTorchMode(cameraId, false) } catch (e: Exception) { }
                }
                ActionResult.Success("Flashlight ON for ${durationMs / 1000}s")
            } else {
                ActionResult.Success("Flashlight ${if (turnOn) "ON" else "OFF"}")
            }
        } catch (e: Exception) {
            ActionResult.Error("Camera Error: ${e.message}")
        }
    }

    private fun executeOpenApp(appName: String): ActionResult {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val match = packages.find { pm.getApplicationLabel(it).toString().lowercase().contains(appName) }

        return if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName)
            if (intent != null) {
                context.startActivity(intent)
                ActionResult.Success("Opening ${pm.getApplicationLabel(match)}")
            } else {
                ActionResult.Error("Cannot launch $appName")
            }
        } else {
            ActionResult.Error("App '$appName' not found")
        }
    }

    
    private fun extractDuration(input: String): Long {
        val minuteMatch = """(\d+)\s*min""".toRegex().find(input)
        if (minuteMatch != null) return (minuteMatch.groupValues[1].toIntOrNull() ?: 0) * 60 * 1000L
        
        val secondMatch = """(\d+)\s*sec""".toRegex().find(input)
        if (secondMatch != null) return (secondMatch.groupValues[1].toIntOrNull() ?: 0) * 1000L
        return 0L
    }
    
    private fun extractNumber(input: String): Int? {
        return """\d+""".toRegex().find(input)?.value?.toIntOrNull()
    }
    
    private fun executeSetVolume(level: Int, increase: Boolean = false, absolute: Boolean = false): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            
            val newVolume = when {
                absolute -> (level * maxVolume / 100).coerceIn(0, maxVolume)
                increase -> (currentVolume + level).coerceIn(0, maxVolume)
                else -> (currentVolume - level).coerceIn(0, maxVolume)
            }
            
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVolume, 0)
            val percentage = (newVolume * 100 / maxVolume)
            ActionResult.Success("Volume set to $percentage%")
        } catch (e: Exception) {
            ActionResult.Error("Volume control failed: ${e.message}")
        }
    }
    
    private fun executeBrightness(level: Int): ActionResult {
        return try {
            // Note: Requires WRITE_SETTINGS permission and user to grant it in settings
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                (level * 255 / 100).coerceIn(0, 255)
            )
            ActionResult.Success("Brightness set to $level% (requires WRITE_SETTINGS permission)")
        } catch (e: Exception) {
            ActionResult.Error("Brightness control failed - grant WRITE_SETTINGS permission in system settings")
        }
    }
    
    private fun executeReadCalendar(): ActionResult {
        return try {
            val uri = android.provider.CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND
            )
            
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            val events = mutableListOf<String>()
            
            cursor?.use {
                while (it.moveToNext() && events.size < 5) {
                    val title = it.getString(0)
                    val start = it.getLong(1)
                    val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                    events.add("$title - ${dateFormat.format(java.util.Date(start))}")
                }
            }
            
            if (events.isNotEmpty()) {
                ActionResult.Success("Next events:\n${events.joinToString("\n")}")
            } else {
                ActionResult.Success("No upcoming calendar events")
            }
        } catch (e: SecurityException) {
            ActionResult.Error("Calendar access denied - grant READ_CALENDAR permission")
        } catch (e: Exception) {
            ActionResult.Error("Calendar read failed: ${e.message}")
        }
    }
    
    private fun executeReadNotifications(): ActionResult {
        // Note: Requires NotificationListenerService to be enabled by user
        // This is a placeholder - full implementation requires a Service
        return ActionResult.Success("Notification reading requires NotificationListenerService setup (coming soon)")
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { this.contains(it) }
}

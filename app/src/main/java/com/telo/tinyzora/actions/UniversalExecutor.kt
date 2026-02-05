package com.telo.tinyzora.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import org.json.JSONObject

/**
 * ⚡ UNIVERSAL EXECUTOR
 * The "Hands" of TinyZora. Executes JSON commands on the Android System.
 */
class UniversalExecutor(private val context: Context) {

    /**
     * MAIN ENTRY POINT
     * Takes a tool name and arguments (from Gemma's JSON) and runs it.
     */
    fun execute(toolName: String, args: JSONObject): String {
        return try {
            when (toolName.lowercase()) {
                // --- TIME & ORGANIZATION ---
                "set_alarm" -> setAlarm(args)
                "set_timer" -> setTimer(args)
                "add_calendar" -> addToCalendar(args)
                
                // --- HARDWARE CONTROL ---
                "flashlight" -> toggleFlashlight(args)
                "volume" -> setVolume(args)
                "vibrate" -> vibratePhone()
                
                // --- COMMUNICATION ---
                "read_notifications" -> readNotifications(args)
                "read_call_logs" -> readCallLogs(args)
                "dial" -> dialNumber(args)
                "whatsapp" -> openWhatsApp(args)
                "email" -> sendEmail(args)
                
                // --- NAVIGATION & WEB ---
                "map" -> openMaps(args)
                "search" -> googleSearch(args)
                "open_url" -> openUrl(args)
                
                // --- SETTINGS ---
                "wifi_settings" -> openSettings(Settings.ACTION_WIFI_SETTINGS)
                "bluetooth_settings" -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                "battery_settings" -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                
                // --- APPS ---
                "launch_app" -> launchApp(args)

                else -> "Error: Unknown tool '$toolName'"
            }
        } catch (e: Exception) {
            "Action Failed: ${e.message}"
        }
    }

    // ==========================================
    // ⏰ TIME TOOLS
    // ==========================================
    private fun setAlarm(args: JSONObject): String {
        val time = args.optString("time", "08:00")
        val label = args.optString("label", "TinyZora Alarm")
        val (h, m) = time.split(":").map { it.toInt() }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Alarm set for $time"
    }

    private fun setTimer(args: JSONObject): String {
        val seconds = args.optInt("seconds", 60)
        val msg = args.optString("message", "Timer")
        
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Timer set for ${seconds}s"
    }
    
    private fun addToCalendar(args: JSONObject): String {
        val title = args.optString("title", "Event")
        val loc = args.optString("location", "Nairobi")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Calendar opened for '$title'"
    }

    // ==========================================
    // 🔦 HARDWARE TOOLS
    // ==========================================
    private fun toggleFlashlight(args: JSONObject): String {
        val state = args.optBoolean("on", true)
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = camManager.cameraIdList[0]
        camManager.setTorchMode(camId, state)
        return "Flashlight ${if (state) "ON" else "OFF"}"
    }

    private fun setVolume(args: JSONObject): String {
        val level = args.optInt("level", 5)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val scaledVol = (level.toFloat() / 10f * maxVol).toInt()
        
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaledVol, AudioManager.FLAG_SHOW_UI)
        return "Volume set to level $level"
    }

    private fun vibratePhone(): String {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
        return "Bzzzt!"
    }

    // ==========================================
    // 📱 COMM & WEB TOOLS
    // ==========================================
    private fun readNotifications(args: JSONObject): String {
        // NOTE: Real implementation requires NotificationListenerService and permission.
        // For now, we return a placeholder or checking system service.
        return "Notification access requires system permission. Please enable Notification Access for TinyZora in Settings."
    }

    private fun readCallLogs(args: JSONObject): String {
         val count = args.optInt("count", 5)
         try {
             val uri = android.provider.CallLog.Calls.CONTENT_URI
             val projection = arrayOf(
                 android.provider.CallLog.Calls.NUMBER,
                 android.provider.CallLog.Calls.TYPE,
                 android.provider.CallLog.Calls.DATE
             )
             // Requires READ_CALL_LOG permission
             val cursor = context.contentResolver.query(uri, projection, null, null, android.provider.CallLog.Calls.DATE + " DESC")
             val logs = mutableListOf<String>()
             
             cursor?.use {
                 while (it.moveToNext() && logs.size < count) {
                     val number = it.getString(0)
                     val type = when(it.getInt(1)) {
                         android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                         android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                         android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
                         else -> "Unknown"
                     }
                     val date = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.getLong(2)))
                     logs.add("$type: $number at $date")
                 }
             }
             return if (logs.isNotEmpty()) "Recent Calls:\n${logs.joinToString("\n")}" else "No recent calls found."
         } catch (e: SecurityException) {
             return "Access denied: Please grant READ_CALL_LOG permission."
         } catch (e: Exception) {
             return "Failed to read call logs: ${e.message}"
         }
    }

    private fun sendSMS(args: JSONObject): String {
        val number = args.optString("number")
        val msg = args.optString("message")
        val uri = Uri.parse("smsto:$number")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", msg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opening SMS to $number"
    }

    private fun dialNumber(args: JSONObject): String {
        val number = args.optString("number")
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Dialing $number..."
    }

    private fun openWhatsApp(args: JSONObject): String {
        val number = args.optString("number").replace("+", "").replace(" ", "")
        val url = "https://api.whatsapp.com/send?phone=$number"
        return openUrl(JSONObject().put("url", url))
    }

    private fun sendEmail(args: JSONObject): String {
        val to = args.optString("to")
        val subject = args.optString("subject", "")
        val body = args.optString("body", "")
        
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opening email to $to"
    }

    private fun openMaps(args: JSONObject): String {
        val query = args.optString("query")
        val uri = Uri.parse("geo:0,0?q=$query")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return "Opening Maps for '$query'"
        }
        return googleSearch(args)
    }

    private fun googleSearch(args: JSONObject): String {
        val query = args.optString("query")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Searching Google for '$query'"
    }
    
    private fun openUrl(args: JSONObject): String {
        val url = args.optString("url")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opening browser..."
    }

    private fun openSettings(action: String): String {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return "Opening Settings"
    }

    private fun launchApp(args: JSONObject): String {
        val appName = args.optString("name").lowercase()
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val match = packages.find { pm.getApplicationLabel(it).toString().lowercase().contains(appName) }
        
        return if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName)
            context.startActivity(intent)
            "Launching ${pm.getApplicationLabel(match)}"
        } else {
            "App '$appName' not found."
        }
    }
}

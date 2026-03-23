package com.telo.tinyzora.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.telo.tinyzora.core.memory.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ZoraAlarm", "ReminderReceiver captured an incoming broadcast!")
        val content = intent.getStringExtra("REMINDER_CONTENT")
        
        if (content == null) {
            Log.e("ZoraAlarm", "Broadcast received but REMINDER_CONTENT was null!")
            return
        }
        
        Log.d("ZoraAlarm", "Posting notification for: $content")
        
        NotificationHelper.postReminderNotification(
            context,
            title = "Reminder from Zora",
            body = content,
            reminderContent = content
        )
        
        // Auto-delete the reminder so it doesn't repeatedly try to schedule if the device reboots
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = MemoryStore(context)
                store.deleteReminderByContent(content)
                Log.d("ZoraAlarm", "Successfully auto-deleted fired reminder: $content")
            } catch (e: Exception) {
                Log.e("ZoraAlarm", "Failed to delete reminder: $content", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

package com.telo.tinyzora.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.telo.tinyzora.core.memory.MemoryEntry
import com.telo.tinyzora.core.memory.MemoryStore
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.util.Log

object ReminderScheduler {
    fun scheduleReminder(context: Context, entry: MemoryEntry) {
        val dueString = entry.due ?: return
        Log.d("ZoraAlarm", "Attempting to schedule reminder: ${entry.content} for due string: $dueString")
        
        try {
            val now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"))
            val cleanDue = dueString.trim()
            
            val dueTime = try {
                ZonedDateTime.parse(cleanDue, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withZoneSameInstant(ZoneId.of("Africa/Nairobi"))
            } catch (e: Exception) {
                try {
                    java.time.LocalDateTime.parse(cleanDue)
                        .atZone(ZoneId.of("Africa/Nairobi"))
                } catch (e2: Exception) {
                    java.time.LocalDate.parse(cleanDue)
                        .atTime(12, 0)
                        .atZone(ZoneId.of("Africa/Nairobi"))
                }
            }
            

            Log.d("ZoraAlarm", "Parsed DueTime: $dueTime, Current Time: $now")
            
            if (dueTime.isAfter(now)) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra("REMINDER_CONTENT", entry.content)
                }
                
                val requestCode = dueString.hashCode()
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 
                    requestCode, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                // Allow exact alarm while idle for high priority reminders. 
                // ColorOS heavily restricts setExactAndAllowWhileIdle in Deep Doze. 
                // Must use setAlarmClock for critical user-facing alarms.
                val alarmClockInfo = AlarmManager.AlarmClockInfo(
                    dueTime.toInstant().toEpochMilli(),
                    null
                )
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d("ZoraAlarm", "Successfully set AlarmClock for: ${dueTime.toInstant().toEpochMilli()} ms")
            } else {
                Log.d("ZoraAlarm", "Skipping schedule: Due time is in the past.")
            }
        } catch (e: Exception) {
            Log.e("ZoraAlarm", "Error scheduling reminder: ${e.message}", e)
            e.printStackTrace()
        }
    }

    fun scheduleAllReminders(context: Context, memoryStore: MemoryStore) {
        val file = memoryStore.load()
        val reminders = file.entries.filter { it.type == "reminder" && it.due != null }
        
        reminders.forEach { scheduleReminder(context, it) }
    }
}

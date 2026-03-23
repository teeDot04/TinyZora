package com.telo.tinyzora.core.training

import android.content.Context
import android.util.Log
import com.telo.tinyzora.util.ConsoleLogger
import androidx.work.*
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.notifications.NotificationHelper
import com.telo.tinyzora.core.notifications.ReminderScheduler
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class NightWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "NightWorker"

    override suspend fun doWork(): Result {
        val context = applicationContext
        val memoryStore = MemoryStore(context)
        val nairobi = ZoneId.of("Africa/Nairobi")
        
        try {
            // STEP 1 — Morning briefing (only if time < 10:00 Nairobi)
            val now = ZonedDateTime.now(nairobi)
            if (now.hour < 10) {
                val file = memoryStore.load()
                val today = now.toLocalDate()
                
                val remindersToday = file.entries.filter { entry ->
                    entry.type == "reminder" && entry.due != null && try {
                        ZonedDateTime.parse(entry.due, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .withZoneSameInstant(nairobi)
                            .toLocalDate() == today
                    } catch (e: Exception) { false }
                }

                val briefingString = if (remindersToday.isNotEmpty()) {
                    buildString {
                        append("You have ${remindersToday.size} reminder(s) today:\n")
                        remindersToday.forEach { append("- ${it.content}\n") }
                    }
                } else {
                    "No reminders today. Have a great day, Otieno."
                }
                
                NotificationHelper.postBriefingNotification(context, briefingString)
            }

            // STEP 2 — Reschedule reminders
            ReminderScheduler.scheduleAllReminders(context, memoryStore)

        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "NightWorker failed during briefing/rescheduling: ${e.message}", e)
        } finally {
            // STEP 3 — Reschedule self for tomorrow 03:00 Nairobi
            // This MUST always run even if Steps 1-2 fail.
            try {
                val now = ZonedDateTime.now(nairobi)
                var next = now.toLocalDate().atTime(3, 0).atZone(nairobi)
                if (!next.isAfter(now)) {
                    next = next.plusDays(1)
                }
                
                val delayMillis = next.toInstant().toEpochMilli() - System.currentTimeMillis()
                val request = OneTimeWorkRequestBuilder<NightWorker>()
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .addTag("night_worker")
                    .build()
                    
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "tinyzora_night",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                ConsoleLogger.i(TAG, "Rescheduled for $next (Delay: $delayMillis ms)")
            } catch (e: Exception) {
                ConsoleLogger.e(TAG, "Critical: NightWorker failed to reschedule itself!", e)
            }
        }

        return Result.success()
    }
}

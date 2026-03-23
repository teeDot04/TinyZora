package com.telo.tinyzora.core.training

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object NightWorkerScheduler {
    fun scheduleNightWorker(context: Context) {
        val nairobi = ZoneId.of("Africa/Nairobi")
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
            ExistingWorkPolicy.KEEP,
            request
        )
        
        Log.i("NightWorkerScheduler", "Rescheduled for $next (Delay: $delayMillis ms)")
    }
}

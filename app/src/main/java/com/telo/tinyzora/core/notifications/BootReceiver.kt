package com.telo.tinyzora.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.telo.tinyzora.core.memory.MemoryStore
import com.telo.tinyzora.core.training.NightWorkerScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val memoryStore = MemoryStore(context)
            ReminderScheduler.scheduleAllReminders(context, memoryStore)
            NightWorkerScheduler.scheduleNightWorker(context)
        }
    }
}

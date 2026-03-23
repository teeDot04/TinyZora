package com.telo.tinyzora.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.telo.tinyzora.MainActivity

object NotificationHelper {

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channels = listOf(
                NotificationChannel("tinyzora_reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel("tinyzora_briefing", "Daily Briefing", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel("tinyzora_proactive", "Proactive Check-ins", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel("tinyzora_trading", "Trading Alerts", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel("tinyzora_system", "System", NotificationManager.IMPORTANCE_MIN)
            )

            channels.forEach { manager.createNotificationChannel(it) }
        }
    }

    fun postReminderNotification(context: Context, title: String, body: String, reminderContent: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("REMINDER_CONTEXT", reminderContent)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            reminderContent.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "tinyzora_reminders")
            .setSmallIcon(com.telo.tinyzora.R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(reminderContent.hashCode(), notification)
    }

    fun postBriefingNotification(context: Context, body: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "tinyzora_briefing")
            .setSmallIcon(com.telo.tinyzora.R.drawable.ic_stat_name)
            .setContentTitle("Good morning, Otieno ☀️")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }
}

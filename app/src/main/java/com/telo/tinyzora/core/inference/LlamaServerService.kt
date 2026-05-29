package com.telo.tinyzora.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LlamaServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "llama_server_channel"
        const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LlamaServerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, LlamaServerService::class.java)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            val result = LlamaServerManager.start(applicationContext)
            result.onFailure { e ->
                sendBroadcast(Intent("com.telo.tinyzora.LLAMA_SERVER_ERROR").apply {
                    putExtra("error", e.message)
                })
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        LlamaServerManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "tinyZora Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the local AI engine running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("tinyZora")
            .setContentText("Local AI engine running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
}

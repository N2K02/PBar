package com.n2k02.pbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/** Keeps the enabled overlay process important; the notification listener remains OS-managed. */
class PBarForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isEligible(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            promoteToForeground()
        } catch (error: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "PBar", NotificationManager.IMPORTANCE_LOW))
        }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder.setSmallIcon(R.drawable.ic_pbar_launcher)
            .setContentTitle("PBar is active")
            .setContentText("Keeping the progress indicator available")
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "pbar_service"
        private const val NOTIFICATION_ID = 4201

        fun hasNotificationListenerAccess(context: Context): Boolean = runCatching {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val name = ComponentName(context, ProgressNotificationListener::class.java).flattenToString()
            enabled.split(':').any { it.equals(name, ignoreCase = true) }
        }.getOrDefault(false)

        private fun isEligible(context: Context): Boolean {
            val prefs = context.getSharedPreferences("pbar", Context.MODE_PRIVATE)
            return !prefs.getBoolean("quit_requested", false) &&
                prefs.getBoolean("overlay_enabled", false) &&
                Settings.canDrawOverlays(context) &&
                hasNotificationListenerAccess(context)
        }

        fun start(context: Context) {
            if (!isEligible(context)) return
            val app = context.applicationContext
            runCatching { app.startForegroundService(Intent(app, PBarForegroundService::class.java)) }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, PBarForegroundService::class.java)) }
        }
    }
}

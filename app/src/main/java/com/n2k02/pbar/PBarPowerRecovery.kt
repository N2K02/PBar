package com.n2k02.pbar

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Never restart a user-quit or disabled bar, even if the phone is charging. */
internal object PBarRecoveryPolicy {
    fun shouldRestore(enabled: Boolean, quitRequested: Boolean, charging: Boolean, powerSaveMode: Boolean): Boolean =
        enabled && !quitRequested && (charging || !powerSaveMode)
}

/**
 * Restore the foreground host and OS-managed notification listener after extreme battery mode.
 * Dynamic broadcasts handle live processes immediately; the persisted job is a best-effort
 * fallback when Android has killed the process (Android may defer job execution).
 */
internal object PBarPowerRecovery {
    private const val JOB_ID = 4202
    private const val PERIOD_MS = 15 * 60 * 1000L

    private fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("pbar", Context.MODE_PRIVATE)
        return !prefs.getBoolean("quit_requested", false) &&
            prefs.getBoolean("overlay_enabled", false) &&
            Settings.canDrawOverlays(context) &&
            PBarForegroundService.hasNotificationListenerAccess(context)
    }

    fun shouldRestore(context: Context): Boolean {
        val prefs = context.getSharedPreferences("pbar", Context.MODE_PRIVATE)
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return PBarRecoveryPolicy.shouldRestore(
            enabled(context),
            prefs.getBoolean("quit_requested", false),
            battery.isCharging,
            power.isPowerSaveMode,
        )
    }

    fun schedule(context: Context) {
        if (!enabled(context)) return
        val app = context.applicationContext
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, PBarPowerRecoveryJobService::class.java))
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            .build()
        runCatching { scheduler.schedule(job) }
            .onFailure { AppLog.add("RECOVERY", "JOB_SCHEDULE_FAILED") }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        runCatching { scheduler.cancel(JOB_ID) }
    }

    fun restore(context: Context): Boolean {
        if (!shouldRestore(context)) return false
        val app = context.applicationContext
        // Never rely on a persisted connection flag after an OEM kills the process.
        if (!ProgressNotificationListener.isConnectedInProcess) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(app, ProgressNotificationListener::class.java))
            }.onFailure { AppLog.add("RECOVERY", "LISTENER_REBIND_FAILED") }
        }
        PBarForegroundService.start(app)
        app.sendBroadcast(Intent(ProgressNotificationListener.ACTION_REFRESH).setPackage(app.packageName))
        return true
    }
}

class PBarPowerRecoveryJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (PBarPowerRecovery.shouldRestore(this)) {
            if (PBarPowerRecovery.restore(this)) AppLog.add("RECOVERY", "RESTORE_REQUESTED")
            else AppLog.add("RECOVERY", "RESTORE_DEFERRED")
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

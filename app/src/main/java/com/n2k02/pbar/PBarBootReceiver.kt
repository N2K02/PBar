package com.n2k02.pbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores PBar after reboot when the user has left startup enabled. */
class PBarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("pbar", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot", true)) return
        // An enabled boot preference explicitly opts back in after a device restart,
        // even if the previous session was stopped with Quit.
        prefs.edit().putBoolean("quit_requested", false).putBoolean("overlay_enabled", true).apply()
        if (PBarForegroundService.hasNotificationListenerAccess(context) && android.provider.Settings.canDrawOverlays(context)) {
            PBarPowerRecovery.schedule(context)
            PBarForegroundService.start(context)
        }
    }
}

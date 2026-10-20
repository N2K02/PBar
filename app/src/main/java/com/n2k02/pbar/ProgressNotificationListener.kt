package com.n2k02.pbar

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ProgressNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        activeNotifications?.forEach(::consume)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = consume(sbn)
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        activeNotifications?.forEach(::consume)
    }

    private fun consume(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val category = n.category.orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val isMedia = category == Notification.CATEGORY_TRANSPORT ||
            n.extras.containsKey("android.mediaSession") ||
            title.contains("playing", true) || text.contains("playing", true)
        val hasProgress = indeterminate || max > 0
        if (!hasProgress && !isMedia) return

        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("has_signal", true)
            .putBoolean("signal_media", isMedia && !hasProgress)
            .putBoolean("signal_indeterminate", indeterminate)
            .putInt("signal_progress", if (max > 0) ((progress.toFloat() / max) * 100).toInt().coerceIn(0, 100) else 0)
            .putString("signal_title", title.ifBlank { sbn.packageName })
            .putString("signal_text", text)
            .putLong("signal_updated", System.currentTimeMillis())
            .apply()
        sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val remaining = activeNotifications.orEmpty().filter(::isIndicatorNotification)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (remaining.isEmpty()) {
            prefs.edit().putBoolean("has_signal", false).putInt("signal_progress", 0).putBoolean("signal_media", false).apply()
            sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
        } else {
            remaining.forEach(::consume)
        }
    }

    private fun isIndicatorNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: return false
        val hasProgress = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val media = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession") ||
            title.contains("playing", true) || text.contains("playing", true)
        return hasProgress || media
    }

    companion object { const val ACTION_SIGNAL = "com.n2k02.pbar.SIGNAL_UPDATED" }
}

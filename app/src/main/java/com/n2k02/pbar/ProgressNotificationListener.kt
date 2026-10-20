package com.n2k02.pbar

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ProgressNotificationListener : NotificationListenerService() {
    private lateinit var mediaSessions: MediaSessionManager
    private val registeredControllers = mutableMapOf<MediaController, MediaController.Callback>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!::mediaSessions.isInitialized) return
            val controllers = runCatching { mediaSessions.getActiveSessions(ComponentName(this@ProgressNotificationListener, ProgressNotificationListener::class.java)) }.getOrDefault(emptyList())
            refreshMediaSessions(controllers)
        }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        refreshMediaSessions(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        activeNotifications?.forEach(::consume)
        mediaSessions = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        runCatching {
            mediaSessions.addOnActiveSessionsChangedListener(sessionsChanged, ComponentName(this, ProgressNotificationListener::class.java))
            refreshMediaSessions(mediaSessions.getActiveSessions(ComponentName(this, ProgressNotificationListener::class.java)))
        }
    }

    private fun refreshMediaSessions(controllers: List<MediaController>) {
        val active = controllers.toSet()
        registeredControllers.keys.filter { it !in active }.forEach { controller ->
            registeredControllers.remove(controller)?.let { callback -> runCatching { controller.unregisterCallback(callback) } }
        }
        controllers.forEach { controller ->
            if (!registeredControllers.containsKey(controller)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        refreshMediaSessions(runCatching { mediaSessions.getActiveSessions(ComponentName(this@ProgressNotificationListener, ProgressNotificationListener::class.java)) }.getOrDefault(emptyList()))
                    }
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                        refreshMediaSessions(runCatching { mediaSessions.getActiveSessions(ComponentName(this@ProgressNotificationListener, ProgressNotificationListener::class.java)) }.getOrDefault(emptyList()))
                    }
                }
                registeredControllers[controller] = callback
                runCatching { controller.registerCallback(callback) }
            }
        }
        controllers.forEach { rememberSourcePackage(it.packageName) }
        val playing = controllers.firstOrNull { controller ->
            !isIgnoredPackage(controller.packageName) && when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
                else -> false
            }
        }
        progressHandler.removeCallbacks(progressTicker)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (playing != null) {
            val metadata = playing.metadata
            val state = playing.playbackState
            val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = state?.position ?: 0L
            val estimatedPosition = if (state?.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else position
            val percent = if (duration > 0L) ((estimatedPosition.coerceIn(0L, duration) * 100L) / duration).toInt() else 0
            prefs.edit()
                .putBoolean("has_signal", true)
                .putBoolean("signal_media", true)
                .putBoolean("signal_indeterminate", duration <= 0L)
                .putInt("signal_progress", percent)
                .putLong("signal_duration", duration)
                .putLong("signal_position", estimatedPosition.coerceAtLeast(0L))
                .putInt("signal_color", 0)
                .putString("signal_package", playing.packageName)
                .putString("signal_title", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: playing.packageName)
                .putString("signal_text", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
                .putLong("signal_updated", System.currentTimeMillis())
                .apply()
            sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            if (state?.state == PlaybackState.STATE_PLAYING) progressHandler.postDelayed(progressTicker, 500L)
        } else {
            val notificationSignal = activeNotifications.orEmpty().any { !isIgnoredPackage(it.packageName) && isIndicatorNotification(it) }
            if (!notificationSignal) {
                prefs.edit().putBoolean("has_signal", false).putBoolean("signal_media", false).putInt("signal_progress", 0).apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
        }
    }

    override fun onDestroy() {
        registeredControllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        registeredControllers.clear()
        progressHandler.removeCallbacks(progressTicker)
        if (::mediaSessions.isInitialized) runCatching { mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = consume(sbn)
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        activeNotifications?.forEach(::consume)
    }

    private fun consume(sbn: StatusBarNotification) {
        rememberSourcePackage(sbn.packageName)
        if (isIgnoredPackage(sbn.packageName)) {
            val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
            if (prefs.getString("signal_package", null) == sbn.packageName) {
                prefs.edit().putBoolean("has_signal", false).apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
            return
        }
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val category = n.category.orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val template = extras.getString("android.template").orEmpty()
        val isMedia = category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession") ||
            template.contains("MediaStyle", true) ||
            title.contains("playing", true) || text.contains("playing", true)
        val hasProgress = indeterminate || max > 0
        if (!hasProgress && !isMedia) return

        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("has_signal", true)
            .putBoolean("signal_media", isMedia && !hasProgress)
            .putBoolean("signal_indeterminate", indeterminate)
            .putInt("signal_progress", if (max > 0) ((progress.toFloat() / max) * 100).toInt().coerceIn(0, 100) else 0)
            .putInt("signal_color", n.color)
            .putString("signal_package", sbn.packageName)
            .putString("signal_title", title.ifBlank { sbn.packageName })
            .putString("signal_text", text)
            .putLong("signal_updated", System.currentTimeMillis())
            .apply()
        sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val remaining = activeNotifications.orEmpty().filter { !isIgnoredPackage(it.packageName) && isIndicatorNotification(it) }
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (remaining.isEmpty()) {
            prefs.edit().putBoolean("has_signal", false).putInt("signal_progress", 0).putBoolean("signal_media", false).apply()
            sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
        } else {
            remaining.forEach(::consume)
        }
    }

    private fun rememberSourcePackage(sourcePackage: String) {
        if (sourcePackage.isBlank() || sourcePackage == packageName) return
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        val known = prefs.getStringSet("known_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (known.add(sourcePackage)) prefs.edit().putStringSet("known_packages", known).apply()
    }

    private fun isIgnoredPackage(sourcePackage: String): Boolean {
        return getSharedPreferences("pbar", MODE_PRIVATE)
            .getStringSet("ignored_packages", emptySet())
            ?.contains(sourcePackage) == true
    }

    private fun isIndicatorNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: return false
        val hasProgress = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val template = extras.getString("android.template").orEmpty()
        val media = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession") ||
            template.contains("MediaStyle", true) ||
            title.contains("playing", true) || text.contains("playing", true)
        return hasProgress || media
    }

    companion object { const val ACTION_SIGNAL = "com.n2k02.pbar.SIGNAL_UPDATED" }
}

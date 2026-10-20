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
            getSharedPreferences("pbar", MODE_PRIVATE).getBoolean("show_for_media", true) &&
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
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (isIgnoredPackage(sbn.packageName)) {
            if (prefs.getString("signal_package", null) == sbn.packageName) {
                prefs.edit().putBoolean("has_signal", false).apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
            return
        }
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val rawProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminateFromSource = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val category = n.category.orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val template = extras.getString("android.template").orEmpty()
        val media = category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession") ||
            template.contains("MediaStyle", true)
        val timer = isGoogleTimerNotification(sbn)
        if (media && !prefs.getBoolean("show_for_media", true)) return
        if (timer && !prefs.getBoolean("show_for_timers", true)) return
        if (!timer && !media && !prefs.getBoolean("show_for_downloads", true)) return

        val parsedPercent = parsePercentage(extras)
        val timerProgress = parseGoogleTimerProgress(sbn)
        val hasNativeProgress = max > 0
        val hasParsedProgress = parsedPercent != null
        val hasTimerProgress = timerProgress != null
        val hasProgress = hasNativeProgress || hasParsedProgress || hasTimerProgress || indeterminateFromSource
        if (!hasProgress && !media && !timer) return

        val percent = when {
            hasNativeProgress -> ((rawProgress.toFloat() / max) * 100).toInt().coerceIn(0, 100)
            hasParsedProgress -> parsedPercent!!.coerceIn(0, 100)
            hasTimerProgress -> timerProgress!!.coerceIn(0, 100)
            else -> 0
        }
        val indeterminate = when {
            hasNativeProgress || hasParsedProgress || hasTimerProgress -> false
            timer || indeterminateFromSource -> true
            else -> false
        }
        prefs.edit()
            .putBoolean("has_signal", true)
            .putBoolean("signal_media", media && !hasProgress && !timer)
            .putBoolean("signal_indeterminate", indeterminate)
            .putInt("signal_progress", percent)
            .putInt("signal_color", n.color)
            .putString("signal_package", sbn.packageName)
            .putString("signal_title", title.ifBlank { sbn.packageName })
            .putString("signal_text", text)
            .putLong("signal_updated", System.currentTimeMillis())
            .apply()
        sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
    }

    private fun parsePercentage(extras: android.os.Bundle): Int? {
        val candidates = mutableListOf<String>()
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT).forEach { key ->
            extras.getCharSequence(key)?.toString()?.let(candidates::add)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { candidates.add(it.toString()) }
        val regex = Regex("""(?:^|\D)(100|[1-9]?\d)\s*%""")
        return candidates.firstNotNullOfOrNull { value ->
            regex.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun isGoogleTimerNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != "com.google.android.deskclock") return false
        val key = sbn.notification.sortKey.orEmpty()
        return key.contains("RUNNING") && (key.contains("⏳") || key.contains("Σ"))
    }

    private fun parseGoogleTimerProgress(sbn: StatusBarNotification): Int? {
        if (!isGoogleTimerNotification(sbn)) return null
        val fields = sbn.notification.sortKey.orEmpty().split("|")
        val remaining = fields.firstOrNull { it.contains("⏳") }?.substringAfter("⏳")?.let(::parseClockMillis) ?: return null
        val total = fields.firstOrNull { it.contains("Σ") }?.substringAfter("Σ")?.let(::parseClockMillis) ?: return null
        if (total <= 0L) return null
        return (((total - remaining).coerceIn(0L, total) * 100L) / total).toInt()
    }

    private fun parseClockMillis(value: String): Long? {
        val parts = value.trim().split(":").map { it.toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
        return seconds * 1000L
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
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            parsePercentage(extras) != null || parseGoogleTimerProgress(sbn) != null
        val template = extras.getString("android.template").orEmpty()
        val media = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession") ||
            template.contains("MediaStyle", true)
        val timer = isGoogleTimerNotification(sbn)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        return (hasProgress && prefs.getBoolean("show_for_downloads", true)) ||
            (media && prefs.getBoolean("show_for_media", true)) ||
            (timer && prefs.getBoolean("show_for_timers", true))
    }

    companion object { const val ACTION_SIGNAL = "com.n2k02.pbar.SIGNAL_UPDATED" }
}

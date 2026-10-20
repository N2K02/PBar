package com.n2k02.pbar

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
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
    private val notificationControllers = mutableMapOf<String, MediaController>()
    // The overlay lives here because Android binds and restarts this service itself.
    private var overlayController: OverlayController? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerPackage = ""
    private var timerRemainingAtStart = 0L
    private var timerTotalMs = 0L
    private var timerStartElapsed = 0L
    private val timerTicker = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - timerStartElapsed
            val remaining = (timerRemainingAtStart - elapsed).coerceAtLeast(0L)
            val total = timerTotalMs
            val percent = if (total > 0L) (((total - remaining).coerceIn(0L, total) * 100L) / total).toInt() else 0
            val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
            if (prefs.getString("signal_package", null) == timerPackage && prefs.getBoolean("signal_timer", false)) {
                prefs.edit().putInt("signal_progress", percent).putBoolean("signal_indeterminate", false).apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
            if (remaining > 0L) timerHandler.postDelayed(this, 1000L)
        }
    }
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!::mediaSessions.isInitialized) return
            val controllers = runCatching { mediaSessions.getActiveSessions(ComponentName(this@ProgressNotificationListener, ProgressNotificationListener::class.java)) }.getOrDefault(emptyList())
            refreshMediaSessions(controllers)
        }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        refreshMediaSessions((controllers.orEmpty() + notificationControllers.values).distinctBy { it.sessionToken })
    }

    override fun onListenerConnected() {
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        prefs.edit().putBoolean("diagnostic_listener_connected", true)
            .putLong("diagnostic_listener_connected_at", System.currentTimeMillis()).apply()
        // Start the overlay first so it still works if media-session setup below fails.
        overlayController = (overlayController ?: OverlayController(this)).also { it.start() }
        mediaSessions =getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        runCatching {
            mediaSessions.addOnActiveSessionsChangedListener(sessionsChanged, ComponentName(this, ProgressNotificationListener::class.java))
            activeNotifications?.forEach(::consume)
            refreshMediaSessions(currentMediaControllers())
        }.onFailure { error ->
            prefs.edit().putString("diagnostic_session_error", error.javaClass.simpleName + ": " + (error.message ?: "no details")).apply()
        }
    }

    private fun currentMediaControllers(): List<MediaController> {
        val active = if (::mediaSessions.isInitialized) runCatching {
            mediaSessions.getActiveSessions(ComponentName(this, ProgressNotificationListener::class.java))
        }.getOrDefault(emptyList()) else emptyList()
        return (active + notificationControllers.values).distinctBy { it.sessionToken }
    }

    @Suppress("DEPRECATION")
    private fun rememberNotificationSession(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) ?: return
        runCatching { MediaController(this, token) }.getOrNull()?.let { controller ->
            notificationControllers[sbn.key] = controller
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
                        refreshMediaSessions(currentMediaControllers())
                    }
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                        refreshMediaSessions(currentMediaControllers())
                    }
                }
                registeredControllers[controller] = callback
                runCatching { controller.registerCallback(callback) }
            }
        }
        controllers.forEach { rememberSourcePackage(it.packageName) }
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        val sessionSummary = controllers.take(8).joinToString(" | ") { controller ->
            val state = controller.playbackState
            val duration = controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            controller.packageName + ":" + (state?.state?.toString() ?: "no-state") + ":" + duration + "ms"
        }.ifBlank { "No active media sessions returned by Android" }
        prefs.edit()
            .putInt("diagnostic_session_count", controllers.size)
            .putString("diagnostic_session_summary", sessionSummary)
            .putString("diagnostic_session_error", "")
            .putLong("diagnostic_last_session_check", System.currentTimeMillis())
            .apply()
        // Only active playback should create a media progress overlay. A paused
        // media session often remains registered for a long time after playback stops.
        val playingCandidates = controllers.filter { controller ->
            prefs.getBoolean("show_for_media", true) &&
                !isIgnoredPackage(controller.packageName) && when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
                else -> false
            }
        }
        // Prefer a session that exposes usable timing data; stale browser/system
        // sessions can otherwise win and leave the indicator apparently stuck.
        val playing = playingCandidates.firstOrNull { controller ->
            controller.playbackState?.state != PlaybackState.STATE_PAUSED &&
                (controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L &&
                (controller.playbackState?.position ?: -1L) >= 0L
        } ?: playingCandidates.firstOrNull { it.playbackState?.state != PlaybackState.STATE_PAUSED }
            ?: playingCandidates.firstOrNull { controller ->
                (controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L &&
                    (controller.playbackState?.position ?: -1L) >= 0L
            } ?: playingCandidates.firstOrNull()
        progressHandler.removeCallbacks(progressTicker)
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
                .putBoolean("signal_playing", state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_FAST_FORWARDING || state?.state == PlaybackState.STATE_REWINDING)
                .putFloat("signal_speed", state?.playbackSpeed ?: 1f)
                .putBoolean("signal_timer", false)
                .putBoolean("signal_download", false)
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
            // A paused/finished media session must not keep the overlay visible.
            // Preserve genuinely active timer/download progress notifications only.
            val pausedMediaPackages = controllers.filter { controller ->
                controller.playbackState?.state == PlaybackState.STATE_PAUSED ||
                    controller.playbackState?.state == PlaybackState.STATE_STOPPED ||
                    controller.playbackState?.state == PlaybackState.STATE_NONE
            }.map { it.packageName }.toSet()
            val remainingSignals = activeNotifications.orEmpty().filter { notification ->
                !isIgnoredPackage(notification.packageName) &&
                    notification.packageName !in pausedMediaPackages &&
                    !isMediaNotification(notification) &&
                    isIndicatorNotification(notification)
            }
            if (remainingSignals.isNotEmpty()) {
                remainingSignals.forEach(::consume)
            } else {
                prefs.edit()
                    .putBoolean("has_signal", false)
                    .putBoolean("signal_media", false)
                    .putBoolean("signal_playing", false)
                    .putBoolean("signal_indeterminate", false)
                    .putBoolean("signal_timer", false)
                    .putBoolean("signal_download", false)
                    .putInt("signal_progress", 0)
                    .putLong("signal_duration", 0L)
                    .putLong("signal_position", 0L)
                    .apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
        }
    }

    override fun onListenerDisconnected() {
        overlayController?.stop()
        getSharedPreferences("pbar", MODE_PRIVATE).edit().putBoolean("diagnostic_listener_connected", false).apply()
        // Ask Android to reconnect so the overlay comes back without reopening PBar.
        runCatching { NotificationListenerService.requestRebind(ComponentName(this, ProgressNotificationListener::class.java)) }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        overlayController?.stop()
        overlayController = null
        registeredControllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        registeredControllers.clear()
        progressHandler.removeCallbacks(progressTicker)
        stopTimerTicker()
        if (::mediaSessions.isInitialized) runCatching { mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        notificationControllers.clear()
        getSharedPreferences("pbar", MODE_PRIVATE).edit().putBoolean("diagnostic_listener_connected", false).apply()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = consume(sbn)
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        activeNotifications?.forEach(::consume)
    }

    private fun consume(sbn: StatusBarNotification) {
        rememberSourcePackage(sbn.packageName)
        if (isMediaNotification(sbn)) {
            rememberNotificationSession(sbn)
            if (::mediaSessions.isInitialized) refreshMediaSessions(currentMediaControllers())
        }
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (isIgnoredPackage(sbn.packageName)) {
            if (prefs.getString("signal_package", null) == sbn.packageName) {
                prefs.edit().putBoolean("has_signal", false).putBoolean("signal_timer", false).apply()
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
        // Media notification refreshes often carry no playback position. Let the
        // MediaSession path own the progress value instead of resetting it to 0.
        if (media) {
            if (!prefs.getBoolean("show_for_media", true)) return
            if (::mediaSessions.isInitialized) {
                val controllers = runCatching {
                    mediaSessions.getActiveSessions(ComponentName(this, ProgressNotificationListener::class.java))
                }.getOrDefault(emptyList())
                refreshMediaSessions(controllers)
            }
            // A media-style notification alone is not proof of playback. Only the
            // active MediaSession path may show a media bar; otherwise clear a stale
            // signal for this app and wait for an actual playing session.
            val current = getSharedPreferences("pbar", MODE_PRIVATE)
            if (current.getString("signal_package", null) == sbn.packageName &&
                current.getBoolean("signal_media", false) &&
                !current.getBoolean("signal_playing", false)) {
                current.edit()
                    .putBoolean("has_signal", false)
                    .putBoolean("signal_media", false)
                    .putBoolean("signal_indeterminate", false)
                    .putInt("signal_progress", 0)
                    .putLong("signal_duration", 0L)
                    .putLong("signal_position", 0L)
                    .apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
            return
        }
        val timer = isGoogleTimerNotification(sbn)
        if (!timer && prefs.getBoolean("signal_timer", false) && prefs.getString("signal_package", null) == sbn.packageName) {
            stopTimerTicker()
            prefs.edit().putBoolean("has_signal", false).putBoolean("signal_timer", false).apply()
            sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
        }
        if (media && !prefs.getBoolean("show_for_media", true)) return
        if (timer && !prefs.getBoolean("show_for_timers", true)) return
        if (!timer && !media && !prefs.getBoolean("show_for_downloads", true)) return

        val parsedPercent = parsePercentage(extras)
        val timerDurations = parseGoogleTimerDurations(sbn)
        val timerProgress = timerDurations?.let { (remaining, total) ->
            if (total > 0L) (((total - remaining).coerceIn(0L, total) * 100L) / total).toInt() else null
        }
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
            .putBoolean("signal_timer", timer)
            .putBoolean("signal_download", !media && !timer && hasProgress)
            .putBoolean("signal_indeterminate", indeterminate)
            .putInt("signal_progress", percent)
            .putInt("signal_color", n.color)
            .putString("signal_package", sbn.packageName)
            .putString("signal_title", title.ifBlank { sbn.packageName })
            .putString("signal_text", text)
            .putLong("signal_updated", System.currentTimeMillis())
            .apply()
        sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
        if (timer && timerDurations != null) startTimerTicker(sbn.packageName, timerDurations.first, timerDurations.second)
        else if (timer) stopTimerTicker()
    }

    private fun startTimerTicker(sourcePackage: String, remainingMs: Long, totalMs: Long) {
        timerHandler.removeCallbacks(timerTicker)
        timerPackage = sourcePackage
        timerRemainingAtStart = remainingMs
        timerTotalMs = totalMs
        timerStartElapsed = SystemClock.elapsedRealtime()
        timerHandler.postDelayed(timerTicker, 1000L)
    }

    private fun stopTimerTicker() {
        timerHandler.removeCallbacks(timerTicker)
        timerPackage = ""
        timerRemainingAtStart = 0L
        timerTotalMs = 0L
        timerStartElapsed = 0L
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
        return key.contains("RUNNING")
    }

    private fun parseGoogleTimerDurations(sbn: StatusBarNotification): Pair<Long, Long>? {
        if (!isGoogleTimerNotification(sbn)) return null
        val fields = sbn.notification.sortKey.orEmpty().split("|")
        val remaining = fields.firstOrNull { it.contains("⏳") }?.substringAfter("⏳")?.let(::parseClockMillis) ?: return null
        val total = fields.firstOrNull { it.contains("Σ") }?.substringAfter("Σ")?.let(::parseClockMillis) ?: return null
        if (total <= 0L || remaining < 0L) return null
        return remaining to total
    }

    private fun parseGoogleTimerProgress(sbn: StatusBarNotification): Int? {
        val (remaining, total) = parseGoogleTimerDurations(sbn) ?: return null
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
        notificationControllers.remove(sbn.key)
        if (isGoogleTimerNotification(sbn)) stopTimerTicker()

        // Notification lifetimes and MediaSession lifetimes are not identical. Some
        // players replace/remove their media notification while playback continues.
        // Re-query sessions before clearing the overlay signal so a live session wins.
        if (::mediaSessions.isInitialized) {
            refreshMediaSessions(currentMediaControllers())
        }

        // Media notifications are handled exclusively through MediaSession state.
        // Re-consuming them here would call refreshMediaSessions recursively and can
        // cause a stack overflow when a stale media notification remains posted.
        val remaining = activeNotifications.orEmpty().filter {
            !isIgnoredPackage(it.packageName) &&
                !isMediaNotification(it) &&
                isIndicatorNotification(it)
        }
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (remaining.isEmpty()) {
            // refreshMediaSessions already clears stale media sessions. If it left a
            // media signal, that signal is still backed by an active session; retain it.
            val activeMediaSignal = prefs.getBoolean("has_signal", false) && prefs.getBoolean("signal_media", false)
            if (!activeMediaSignal) {
                prefs.edit().putBoolean("has_signal", false).putInt("signal_progress", 0).putBoolean("signal_media", false).putBoolean("signal_timer", false).putBoolean("signal_download", false).apply()
                sendBroadcast(android.content.Intent(ACTION_SIGNAL).setPackage(packageName))
            }
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

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: return false
        val template = extras.getString("android.template").orEmpty()
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession") ||
            template.contains("MediaStyle", true)
    }

    private fun isIndicatorNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: return false
        val hasProgress = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            parsePercentage(extras) != null || parseGoogleTimerProgress(sbn) != null
        val media = isMediaNotification(sbn)
        val timer = isGoogleTimerNotification(sbn)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        return (hasProgress && prefs.getBoolean("show_for_downloads", true)) ||
            (media && prefs.getBoolean("show_for_media", true)) ||
            (timer && prefs.getBoolean("show_for_timers", true))
    }

    companion object { const val ACTION_SIGNAL = "com.n2k02.pbar.SIGNAL_UPDATED" }
}

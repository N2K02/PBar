package com.n2k02.pbar

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Owns the on-screen progress line.
 *
 * It is hosted by [ProgressNotificationListener]. Android binds that service and restarts it
 * on its own (the same way Noti's overlay lives inside its AccessibilityService), so the bar
 * no longer depends on MainActivity having started a separate background service that the
 * system or an OEM battery manager can silently kill.
 */
class OverlayController(private val host: Context) {
    private val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlay: IndicatorView? = null
    private var fullscreen = false
    private var receiverRegistered = false
    private var demoStartedAt = 0L
    private fun demoActive() = demoStartedAt > 0L &&
        SystemClock.elapsedRealtime() - demoStartedAt < TEST_DURATION_MS

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PBarAccessibilityService.ACTION_FULLSCREEN_CHANGED ->
                    fullscreen = intent.getBooleanExtra(PBarAccessibilityService.EXTRA_FULLSCREEN, false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> fullscreen = false
                ACTION_TEST_PROGRESS -> demoStartedAt = SystemClock.elapsedRealtime()
            }
            render()
        }
    }

    /** Start listening for signal/fullscreen/screen events and draw the current state. */
    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ProgressNotificationListener.ACTION_SIGNAL)
                addAction(ACTION_TEST_PROGRESS)
                addAction(PBarAccessibilityService.ACTION_FULLSCREEN_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") host.registerReceiver(receiver, filter)
            receiverRegistered = true
        }
        render()
    }

    /** Stop listening and take the bar off screen. */
    fun stop() {
        if (receiverRegistered) {
            runCatching { host.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        demoStartedAt = 0L
        removeOverlay()
    }

    fun render() {
        if (!Settings.canDrawOverlays(host)) { removeOverlay(); return }
        val prefs = host.getSharedPreferences("pbar", Context.MODE_PRIVATE)
        val keyguard = host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = keyguard.isKeyguardLocked
        if (!prefs.getBoolean("overlay_enabled", false) ||
            (!prefs.getBoolean("has_signal", false) && !demoActive()) ||
            (prefs.getBoolean("hide_fullscreen", false) && fullscreen) ||
            (locked && !prefs.getBoolean("show_on_lock_screen", false))) {
            removeOverlay()
            return
        }
        // Demo deliberately uses the global style, not an app-specific override.
        val sourcePackage = if (demoActive()) "" else prefs.getString("signal_package", "").orEmpty()
        val vertical = prefs.getString("app_${sourcePackage}_orientation", prefs.getString("orientation", "horizontal")) == "vertical"
        val thickness = prefs.getInt("app_${sourcePackage}_thickness", prefs.getInt("thickness", 4)).coerceIn(2, 12)
        val appPosition = prefs.getString("app_${sourcePackage}_position", prefs.getString("position", "top"))
        val params = WindowManager.LayoutParams(
            if (vertical) dp(thickness) else WindowManager.LayoutParams.MATCH_PARENT,
            if (vertical) WindowManager.LayoutParams.MATCH_PARENT else dp(thickness),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (prefs.getBoolean("show_on_lock_screen", false)) @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (vertical) {
                when (appPosition) {
                    "right" -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    "center" -> Gravity.CENTER
                    else -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                }
            } else {
                when (appPosition) {
                    "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
            }
            x = 0
            // Same placement as Noti: the line sits at the very top edge (behind the
            // transparent status-bar window) instead of being pushed below it.
            y = if (appPosition == "bottom") dp(12) else 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val current = overlay
        if (current != null) {
            current.updateFrom(prefs)
            // Media tick broadcasts update the pixels, not window geometry. Avoid
            // a WindowManager relayout on every tick (which can drop animation frames).
            val old = current.layoutParams as? WindowManager.LayoutParams
            if (old != null && old.width == params.width && old.height == params.height &&
                old.gravity == params.gravity && old.flags == params.flags &&
                old.x == params.x && old.y == params.y &&
                (Build.VERSION.SDK_INT < 28 ||
                    old.layoutInDisplayCutoutMode == params.layoutInDisplayCutoutMode)) return
            val updated = runCatching { wm.updateViewLayout(current, params) }
                .onFailure { Log.w(TAG, "updateViewLayout failed; recreating overlay", it) }
                .isSuccess
            if (updated) return
            // Fall through and add a fresh view instead of leaving the bar gone.
            removeOverlay()
        }
        val view = IndicatorView(host).apply { updateFrom(prefs) }
        runCatching { wm.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure { Log.e(TAG, "addView failed", it) }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun dp(value: Int) = (value * host.resources.displayMetrics.density).toInt()

    private inner class IndicatorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var progress = 0
        private var mediaPositionMs = 0L
        private var mediaDurationMs = 0L
        private var mediaPlaying = false
        private var mediaSpeed = 1f
        private var mediaAnchorElapsed = 0L
        private var indeterminate = false
        private var media = false
        private var signal = false
        private var sourcePackage = ""
        private var mediaTitle = ""
        private var timerActive = false
        private var timerRemainingMs = 0L
        private var timerDurationMs = 0L
        private var timerAnchorElapsed = 0L
        private var positionCorrectionMs = 0f
        private var correctionAnchorElapsed = 0L
        private var primaryColor = Color.rgb(103, 80, 164)
        private var opacity = 255
        private var rounded = true

        fun updateFrom(prefs: SharedPreferences) {
            val nextPackage = prefs.getString("signal_package", "").orEmpty()
            val nextTitle = prefs.getString("signal_title", "").orEmpty()
            val nextMedia = prefs.getBoolean("signal_media", false)
            val nextPosition = prefs.getLong(
                "signal_raw_position", prefs.getLong("signal_position", 0L)
            ).coerceAtLeast(0L)
            val nextDuration = prefs.getLong("signal_duration", 0L).coerceAtLeast(0L)
            val nextPlaying = prefs.getBoolean("signal_playing", false)
            val nextSpeed = prefs.getFloat("signal_speed", 1f).coerceIn(-4f, 4f)
            val playerAnchor = prefs.getLong("signal_anchor_elapsed", 0L)
            val now = SystemClock.elapsedRealtime()
            val previousPosition = interpolatedMediaPosition(now)
            // Android playback timestamps are expressed in elapsed-realtime milliseconds.
            // Preserve the clock between reports and only fall back to our own when absent.
            val nextAnchor = if (nextMedia && playerAnchor in 1L..now) playerAnchor
                else if (nextPackage != sourcePackage || nextTitle != mediaTitle || nextMedia != media ||
                    nextPosition != mediaPositionMs || nextDuration != mediaDurationMs ||
                    nextPlaying != mediaPlaying || nextSpeed != mediaSpeed) now
                else mediaAnchorElapsed
            // Tiny player corrections should ease into place; real seeks or track changes
            // must snap immediately to the new position (no misleading slow catch-up).
            val newPosition = (nextPosition +
                (if (nextPlaying) (now - nextAnchor).coerceAtLeast(0L) * nextSpeed else 0f))
                .coerceIn(0f, nextDuration.toFloat())
            val continuous = nextMedia && media && nextPlaying && mediaPlaying &&
                nextPackage == sourcePackage && nextTitle == mediaTitle && nextDuration == mediaDurationMs
            val delta = previousPosition - newPosition
            positionCorrectionMs = if (continuous && kotlin.math.abs(delta) <= 1500f) delta else 0f
            correctionAnchorElapsed = now
            mediaAnchorElapsed = nextAnchor
            progress = prefs.getInt("signal_progress", 0).coerceIn(0, 100)
            mediaPositionMs = nextPosition
            mediaDurationMs = nextDuration
            mediaPlaying = nextPlaying
            mediaSpeed = nextSpeed
            timerActive = prefs.getBoolean("signal_timer", false)
            timerRemainingMs = prefs.getLong("signal_timer_remaining_ms", 0L).coerceAtLeast(0L)
            timerDurationMs = prefs.getLong("signal_timer_total_ms", 0L).coerceAtLeast(0L)
            timerAnchorElapsed = prefs.getLong("signal_timer_anchor_elapsed", 0L)
            indeterminate = prefs.getBoolean("signal_indeterminate", false)
            media = nextMedia
            signal = demoActive() || prefs.getBoolean("has_signal", false)
            val fallbackColor = if (Build.VERSION.SDK_INT >= 31) {
                val typed = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.colorAccent, typed, true)) typed.data
                else Color.rgb(103, 80, 164)
            } else Color.rgb(103, 80, 164)
            sourcePackage = nextPackage
            mediaTitle = nextTitle
            val sourceColor = prefs.getInt("signal_color", 0)
            val appOverride = prefs.getStringSet("app_color_overrides", emptySet()).orEmpty()
                .firstOrNull { it.startsWith("$nextPackage=") }
                ?.substringAfter("=")
                ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            val customColor = prefs.getString("indicator_custom_color", null)
                ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            primaryColor = appOverride
                ?: if (prefs.getBoolean("app_color", true) && sourceColor != Color.TRANSPARENT && sourceColor != 0) sourceColor
                else customColor ?: fallbackColor
            opacity = prefs.getInt("indicator_opacity", 100).coerceIn(20, 100) * 255 / 100
            rounded = prefs.getBoolean("rounded_corners", true)
            invalidate()
        }

        private fun interpolatedMediaPosition(now: Long): Float {
            if (!media || mediaDurationMs <= 0L) return 0f
            val elapsed = if (mediaPlaying) (now - mediaAnchorElapsed).coerceAtLeast(0L) else 0L
            val raw = mediaPositionMs + elapsed * mediaSpeed
            val remainingCorrection = if (mediaPlaying)
                positionCorrectionMs * (1f - (now - correctionAnchorElapsed).coerceAtLeast(0L) / 650f)
                    .coerceIn(0f, 1f)
            else 0f
            return (raw + remainingCorrection).coerceIn(0f, mediaDurationMs.toFloat())
        }

        private fun currentProgress(): Float {
            val now = SystemClock.elapsedRealtime()
            if (demoActive()) return ((now - demoStartedAt) * 100f / TEST_DURATION_MS).coerceIn(0f, 100f)
            if (timerActive && timerDurationMs > 0L && timerAnchorElapsed > 0L) {
                val remaining = (timerRemainingMs - (now - timerAnchorElapsed).coerceAtLeast(0L))
                    .coerceIn(0L, timerDurationMs)
                return 100f * (timerDurationMs - remaining).toFloat() / timerDurationMs
            }
            if (media && mediaDurationMs > 0L)
                return interpolatedMediaPosition(now) * 100f / mediaDurationMs
            return progress.toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val displayProgress = currentProgress()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(opacity * 80 / 255, 150, 150, 150)
            if (rounded) canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (signal) {
                paint.color = Color.argb(opacity, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
                if (width < height) {
                    val segment = if (indeterminate) height * 0.35f else height * displayProgress / 100f
                    val start = if (indeterminate) ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (height - segment) else height - segment
                    if (rounded) canvas.drawRoundRect(0f, start, width.toFloat(), start + segment, width / 2f, width / 2f, paint)
                    else canvas.drawRect(0f, start, width.toFloat(), start + segment, paint)
                } else {
                    val segment = if (indeterminate) width * 0.12f else width * displayProgress / 100f
                    val start = if (indeterminate) ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (width - segment) else 0f
                    if (rounded) canvas.drawRoundRect(start, 0f, start + segment, height.toFloat(), height / 2f, height / 2f, paint)
                    else canvas.drawRect(start, 0f, start + segment, height.toFloat(), paint)
                }
            }
            if (demoStartedAt > 0L && !demoActive()) {
                // The preview never modifies real signals; resume the original overlay state.
                demoStartedAt = 0L
                post { render() }
            } else if (demoActive() || (signal && (indeterminate ||
                (media && mediaPlaying && mediaDurationMs > 0L) ||
                (timerActive && timerDurationMs > 0L)))) postInvalidateOnAnimation()
        }
    }

    companion object {
        const val ACTION_TEST_PROGRESS = "com.n2k02.pbar.TEST_PROGRESS"
        const val TEST_DURATION_MS = 10_000L
        private const val TAG = "PBar"
    }
}

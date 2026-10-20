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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PBarAccessibilityService.ACTION_FULLSCREEN_CHANGED ->
                    fullscreen = intent.getBooleanExtra(PBarAccessibilityService.EXTRA_FULLSCREEN, false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> fullscreen = false
            }
            render()
        }
    }

    /** Start listening for signal/fullscreen/screen events and draw the current state. */
    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ProgressNotificationListener.ACTION_SIGNAL)
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
        removeOverlay()
    }

    fun render() {
        if (!Settings.canDrawOverlays(host)) { removeOverlay(); return }
        val prefs = host.getSharedPreferences("pbar", Context.MODE_PRIVATE)
        val keyguard = host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = keyguard.isKeyguardLocked
        if (!prefs.getBoolean("overlay_enabled", false) || !prefs.getBoolean("has_signal", false) ||
            (prefs.getBoolean("hide_fullscreen", false) && fullscreen) ||
            (locked && !prefs.getBoolean("show_on_lock_screen", false))) {
            removeOverlay()
            return
        }
        val sourcePackage = prefs.getString("signal_package", "").orEmpty()
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
        private var sourceColor = 0
        private var sourcePackage = ""

        fun updateFrom(prefs: SharedPreferences) {
            progress = prefs.getInt("signal_progress", 0).coerceIn(0, 100)
            mediaPositionMs = prefs.getLong("signal_position", 0L).coerceAtLeast(0L)
            mediaDurationMs = prefs.getLong("signal_duration", 0L).coerceAtLeast(0L)
            mediaPlaying = prefs.getBoolean("signal_playing", false)
            mediaSpeed = prefs.getFloat("signal_speed", 1f).coerceIn(0.1f, 4f)
            mediaAnchorElapsed = SystemClock.elapsedRealtime()
            indeterminate = prefs.getBoolean("signal_indeterminate", false)
            media = prefs.getBoolean("signal_media", false)
            signal = prefs.getBoolean("has_signal", false)
            sourceColor = prefs.getInt("signal_color", 0)
            sourcePackage = prefs.getString("signal_package", "").orEmpty()
            invalidate()
        }

        private fun currentProgress(): Int {
            if (!media || mediaDurationMs <= 0L) return progress
            val elapsed = if (mediaPlaying) (SystemClock.elapsedRealtime() - mediaAnchorElapsed).coerceAtLeast(0L) else 0L
            val estimated = mediaPositionMs + (elapsed * mediaSpeed).toLong()
            return ((estimated.coerceIn(0L, mediaDurationMs) * 100L) / mediaDurationMs).toInt()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val displayProgress = currentProgress()
            val fallbackColor = if (Build.VERSION.SDK_INT >= 31) {
                val typed = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.colorAccent, typed, true)) typed.data else Color.rgb(103, 80, 164)
            } else Color.rgb(103, 80, 164)
            val prefs = host.getSharedPreferences("pbar", Context.MODE_PRIVATE)
            val appColorEnabled = prefs.getBoolean("app_color", true)
            val appOverride = prefs.getStringSet("app_color_overrides", emptySet()).orEmpty()
                .firstOrNull { it.startsWith("$sourcePackage=") }
                ?.substringAfter("=")
                ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            val customColor = prefs.getString("indicator_custom_color", null)
                ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            val primary = appOverride
                ?: if (appColorEnabled && sourceColor != Color.TRANSPARENT && sourceColor != 0) sourceColor
                else customColor ?: fallbackColor
            val opacity = prefs.getInt("indicator_opacity", 100).coerceIn(20, 100) * 255 / 100
            val rounded = prefs.getBoolean("rounded_corners", true)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(opacity * 80 / 255, 150, 150, 150)
            if (rounded) canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (signal) {
                paint.color = Color.argb(opacity, Color.red(primary), Color.green(primary), Color.blue(primary))
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
            if (signal && indeterminate) postInvalidateDelayed(32L)
            else if (signal && media && mediaPlaying && mediaDurationMs > 0L) postInvalidateDelayed(16L)
        }
    }

    private companion object { const val TAG = "PBar" }
}

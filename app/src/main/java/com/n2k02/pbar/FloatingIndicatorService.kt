package com.n2k02.pbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class FloatingIndicatorService : Service() {
    private var overlay: View? = null
    private lateinit var wm: WindowManager
    private var fullscreen = false
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PBarAccessibilityService.ACTION_FULLSCREEN_CHANGED ->
                    fullscreen = intent.getBooleanExtra(PBarAccessibilityService.EXTRA_FULLSCREEN, false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> fullscreen = false
            }
            render()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter().apply {
            addAction(ProgressNotificationListener.ACTION_SIGNAL)
            addAction(PBarAccessibilityService.ACTION_FULLSCREEN_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(refreshReceiver, filter)
        if (Settings.canDrawOverlays(this)) render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        render()
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "PBar indicator", NotificationManager.IMPORTANCE_LOW))
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PBar is active")
            .setContentText("Floating progress indicator is enabled")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun render() {
        if (!Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val locked = keyguard.isKeyguardLocked
        if (!prefs.getBoolean("overlay_enabled", false) || !prefs.getBoolean("has_signal", false) ||
            (prefs.getBoolean("hide_fullscreen", false) && fullscreen) ||
            (locked && !prefs.getBoolean("show_on_lock_screen", false))) {
            removeOverlay()
            return
        }
        val sourcePackage = prefs.getString("signal_package", "").orEmpty()
        val isRing = prefs.getString("app_${sourcePackage}_style", prefs.getString("style", "line")) == "ring"
        val vertical = !isRing && prefs.getString("app_${sourcePackage}_orientation", prefs.getString("orientation", "horizontal")) == "vertical"
        val thickness = prefs.getInt("app_${sourcePackage}_thickness", prefs.getInt("thickness", 4)).coerceIn(2, 12)
        val ringSize = prefs.getInt("app_${sourcePackage}_ring_size", prefs.getInt("ring_size", 68)).coerceIn(40, 120)
        val ringOffsetX = prefs.getInt("app_${sourcePackage}_ring_offset_x", prefs.getInt("ring_offset_x", 0)).coerceIn(-150, 150)
        val ringOffsetY = prefs.getInt("app_${sourcePackage}_ring_offset_y", prefs.getInt("ring_offset_y", 28)).coerceIn(0, 160)
        val appPosition = prefs.getString("app_${sourcePackage}_position", prefs.getString("position", "top"))
        val params = WindowManager.LayoutParams(
            when { isRing -> dp(ringSize); vertical -> dp(thickness); else -> WindowManager.LayoutParams.MATCH_PARENT },
            when { isRing -> dp(ringSize); vertical -> WindowManager.LayoutParams.MATCH_PARENT; else -> dp(thickness) },
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (prefs.getBoolean("show_on_lock_screen", false)) WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED else 0),
            android.graphics.PixelFormat.TRANSLUCENT
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
                    "camera" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
            }
            x = if (isRing) dp(ringOffsetX) else dp(2)
            y = when (appPosition) {
                "camera" -> dp(ringOffsetY)
                "bottom" -> dp(12)
                else -> dp(2)
            }
        }
        val current = overlay
        if (current is IndicatorView && current.ring == isRing) {
            current.updateFrom(prefs)
            runCatching { wm.updateViewLayout(current, params) }.onFailure { removeOverlay() }
            return
        }
        removeOverlay()
        val view = IndicatorView(this, isRing).apply { updateFrom(prefs) }
        overlay = view
        runCatching { wm.addView(view, params) }.onFailure { overlay = null }
    }

    private inner class IndicatorView(context: Context, val ring: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private var progress = 0
        private var indeterminate = false
        private var media = false
        private var signal = false
        private var sourceColor = 0
        private var sourcePackage = ""

        fun updateFrom(prefs: android.content.SharedPreferences) {
            progress = prefs.getInt("signal_progress", 0).coerceIn(0, 100)
            indeterminate = prefs.getBoolean("signal_indeterminate", false)
            media = prefs.getBoolean("signal_media", false)
            signal = prefs.getBoolean("has_signal", false)
            sourceColor = prefs.getInt("signal_color", 0)
            sourcePackage = prefs.getString("signal_package", "").orEmpty()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val fallbackColor = if (Build.VERSION.SDK_INT >= 31) {
                val typed = android.util.TypedValue()
                if (getContext().theme.resolveAttribute(android.R.attr.colorAccent, typed, true)) typed.data else Color.rgb(103, 80, 164)
            } else Color.rgb(103, 80, 164)
            val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
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
            val tertiary = Color.rgb(0, 150, 136)
            val opacity = prefs.getInt("indicator_opacity", 100).coerceIn(20, 100) * 255 / 100
            val rounded = prefs.getBoolean("rounded_corners", true)
            if (ring) {
                val stroke = prefs.getInt("ring_stroke", 5).coerceIn(2, 10) * density
                val inset = stroke
                bounds.set(inset, inset, width - inset, height - inset)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.argb(opacity * 225 / 255, 240, 238, 246)
                canvas.drawArc(bounds, 0f, 360f, false, paint)
                if (signal) {
                    val activeColor = if (media) tertiary else primary
                    paint.color = Color.argb(opacity, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
                    val sweep = if (indeterminate) 115f else progress * 3.6f
                    val startAngle = if (indeterminate) -90f + (SystemClock.uptimeMillis() % 1400L) / 1400f * 360f else -90f
                    canvas.drawArc(bounds, startAngle, sweep, false, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 11f * density
                paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                val label = if (media) "♫" else if (signal && !indeterminate) "$progress%" else "•"
                canvas.drawText(label, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
                if (signal && indeterminate) postInvalidateDelayed(32L)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(opacity * 80 / 255, 150, 150, 150)
                if (rounded) canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
                else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                if (signal) {
                    val activeColor = if (media) tertiary else primary
                    paint.color = Color.argb(opacity, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
                    if (width < height) {
                        val segment = if (indeterminate) height * 0.35f else height * progress / 100f
                        val start = if (indeterminate) ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (height - segment) else height - segment
                        if (rounded) canvas.drawRoundRect(0f, start, width.toFloat(), start + segment, width / 2f, width / 2f, paint)
                        else canvas.drawRect(0f, start, width.toFloat(), start + segment, paint)
                    } else {
                        val segment = if (indeterminate) width * 0.12f else width * progress / 100f
                        val start = if (indeterminate) ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (width - segment) else 0f
                        if (rounded) canvas.drawRoundRect(start, 0f, start + segment, height.toFloat(), height / 2f, height / 2f, paint)
                        else canvas.drawRect(start, 0f, start + segment, height.toFloat(), paint)
                    }
                }
                if (signal && indeterminate) postInvalidateDelayed(32L)
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pbar_indicator"
        private const val FOREGROUND_ID = 1204
    }
}

package com.n2k02.pbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class FloatingIndicatorService : Service() {
    private var overlay: View? = null
    private lateinit var wm: WindowManager
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
            if (!prefs.getBoolean("overlay_enabled", false) || !prefs.getBoolean("has_signal", false)) {
                removeOverlay()
            } else {
                val current = overlay
                if (current is IndicatorView) current.updateFrom(prefs) else render()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, IntentFilter(ProgressNotificationListener.ACTION_SIGNAL), RECEIVER_NOT_EXPORTED)
        else registerReceiver(refreshReceiver, IntentFilter(ProgressNotificationListener.ACTION_SIGNAL))
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
        if (!prefs.getBoolean("overlay_enabled", false) || !prefs.getBoolean("has_signal", false)) {
            removeOverlay()
            return
        }
        val isRing = prefs.getString("style", "line") == "ring"
        val vertical = !isRing && prefs.getString("orientation", "horizontal") == "vertical"
        val thickness = prefs.getInt("thickness", 4).coerceIn(2, 12)
        val params = WindowManager.LayoutParams(
            when { isRing -> dp(68); vertical -> dp(thickness); else -> WindowManager.LayoutParams.MATCH_PARENT },
            when { isRing -> dp(68); vertical -> WindowManager.LayoutParams.MATCH_PARENT; else -> dp(thickness) },
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (vertical) {
                when (prefs.getString("position", "left")) {
                    "right" -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    "center" -> Gravity.CENTER
                    else -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                }
            } else {
                when (prefs.getString("position", "top")) {
                    "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    "camera" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
            }
            x = dp(2)
            y = when (prefs.getString("position", "top")) {
                "camera" -> dp(28)
                "bottom" -> dp(12)
                else -> dp(2)
            }
        }
        removeOverlay()
        val view = IndicatorView(this, isRing).apply {
            updateFrom(prefs)
        }
        overlay = view
        runCatching { wm.addView(view, params) }.onFailure { overlay = null }
    }

    private inner class IndicatorView(context: Context, private val ring: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private var progress = 0
        private var indeterminate = false
        private var media = false
        private var signal = false
        private var sourceColor = 0

        fun updateFrom(prefs: android.content.SharedPreferences) {
            progress = prefs.getInt("signal_progress", 0).coerceIn(0, 100)
            indeterminate = prefs.getBoolean("signal_indeterminate", false)
            media = prefs.getBoolean("signal_media", false)
            signal = prefs.getBoolean("has_signal", false)
            sourceColor = prefs.getInt("signal_color", 0)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val fallbackColor = if (Build.VERSION.SDK_INT >= 31) {
                val typed = android.util.TypedValue()
                if (getContext().theme.resolveAttribute(android.R.attr.colorAccent, typed, true)) typed.data else Color.rgb(103, 80, 164)
            } else Color.rgb(103, 80, 164)
            val appColorEnabled = getSharedPreferences("pbar", MODE_PRIVATE).getBoolean("app_color", true)
            val primary = if (appColorEnabled && sourceColor != Color.TRANSPARENT && sourceColor != 0) sourceColor else fallbackColor
            val tertiary = Color.rgb(0, 150, 136)
            if (ring) {
                val stroke = 4.5f * density
                val inset = stroke
                bounds.set(inset, inset, width - inset, height - inset)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.argb(225, 240, 238, 246)
                canvas.drawArc(bounds, 0f, 360f, false, paint)
                if (signal) {
                    paint.color = if (media) tertiary else primary
                    val sweep = when {
                        indeterminate -> 115f
                        else -> progress * 3.6f
                    }
                    canvas.drawArc(bounds, -90f, sweep, false, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 11f * density
                paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                val label = if (media) "♫" else if (signal && !indeterminate) "$progress%" else "•"
                canvas.drawText(label, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(80, 150, 150, 150)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                if (signal) {
                    paint.color = if (media) tertiary else primary
                    if (width < height) {
                        val segment = if (indeterminate) height * 0.35f else height * progress / 100f
                        canvas.drawRect(0f, height - segment, width.toFloat(), height.toFloat(), paint)
                    } else {
                        val segment = if (indeterminate) width * 0.12f else width * progress / 100f
                        canvas.drawRect(0f, 0f, segment, height.toFloat(), paint)
                    }
                }
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

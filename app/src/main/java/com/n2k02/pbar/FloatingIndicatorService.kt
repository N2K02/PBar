package com.n2k02.pbar

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

class FloatingIndicatorService : Service() {
    private var overlay: View? = null
    private lateinit var wm: WindowManager
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { render() }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, IntentFilter(ProgressNotificationListener.ACTION_SIGNAL), RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(refreshReceiver, IntentFilter(ProgressNotificationListener.ACTION_SIGNAL)) }
        if (Settings.canDrawOverlays(this)) render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        render()
        return START_STICKY
    }

    private fun render() {
        if (!Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (!prefs.getBoolean("overlay_enabled", false)) { removeOverlay(); return }
        val isRing = prefs.getString("style", "line") == "ring"
        val params = WindowManager.LayoutParams(
            if (isRing) dp(68) else WindowManager.LayoutParams.MATCH_PARENT,
            if (isRing) dp(68) else dp(5),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (prefs.getString("position", "top")) {
                "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                "camera" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            y = when (prefs.getString("position", "top")) {
                "camera" -> dp(28)
                "bottom" -> dp(12)
                else -> dp(2)
            }
        }
        removeOverlay()
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val current = getSharedPreferences("pbar", MODE_PRIVATE)
                val progress = current.getInt("signal_progress", 0)
                val indeterminate = current.getBoolean("signal_indeterminate", false)
                val media = current.getBoolean("signal_media", false)
                val signal = current.getBoolean("has_signal", false)
                MaterialTheme {
                    if (isRing) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                            Box(Modifier.fillMaxSize().padding(5.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val stroke = 4.dp.toPx()
                                    drawArc(MaterialTheme.colorScheme.surfaceVariant, -90f, 360f, false, style = Stroke(stroke))
                                    if (signal && !media) drawArc(MaterialTheme.colorScheme.primary, -90f, if (indeterminate) 110f else progress * 3.6f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                                    else if (signal) drawArc(MaterialTheme.colorScheme.tertiary, -90f, 250f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                                }
                                Text(if (media) "♫" else if (signal && !indeterminate) "$progress%" else "•", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            if (signal) {
                                val width = if (media || indeterminate) size.width * 0.35f else size.width * progress / 100f
                                drawRect(if (media) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, size = androidx.compose.ui.geometry.Size(width, size.height))
                            }
                        }
                    }
                }
            }
        }
        overlay = view
        runCatching { wm.addView(view, params) }.onFailure { overlay = null }
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
}

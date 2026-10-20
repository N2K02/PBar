package com.n2k02.pbar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper for fullscreen auto-hide. It only inspects foreground window bounds
 * and package names; it never reads or stores on-screen text.
 */
class PBarAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        if (!prefs.getBoolean("hide_fullscreen", false)) return

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return
        if (packageName == this.packageName) {
            sendBroadcast(Intent(ACTION_FULLSCREEN_CHANGED).setPackage(this.packageName).putExtra(EXTRA_FULLSCREEN, false))
            return
        }

        val bounds = Rect()
        val root = rootInActiveWindow
        val fullscreen = try {
            if (root == null) false else {
                root.getBoundsInScreen(bounds)
                val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.let { display ->
                        val size = android.graphics.Point()
                        @Suppress("DEPRECATION")
                        display.getRealSize(size)
                        size
                    }
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Point(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                }
                val screenHeight = metrics?.y ?: resources.displayMetrics.heightPixels
                val screenWidth = metrics?.x ?: resources.displayMetrics.widthPixels
                bounds.top <= 4 && bounds.left <= 4 &&
                    bounds.bottom >= screenHeight * 0.92f &&
                    bounds.right >= screenWidth * 0.92f
            }
        } catch (_: Exception) { false }

        sendBroadcast(
            Intent(ACTION_FULLSCREEN_CHANGED)
                .setPackage(this.packageName)
                .putExtra(EXTRA_FULLSCREEN, fullscreen)
                .putExtra(EXTRA_FOREGROUND_PACKAGE, packageName)
        )
    }

    override fun onInterrupt() {
        sendBroadcast(Intent(ACTION_FULLSCREEN_CHANGED).setPackage(packageName).putExtra(EXTRA_FULLSCREEN, false))
    }

    companion object {
        const val ACTION_FULLSCREEN_CHANGED = "com.n2k02.pbar.FULLSCREEN_CHANGED"
        const val EXTRA_FULLSCREEN = "fullscreen"
        const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"
    }
}

package com.n2k02.pbar

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PBarEfficiencyLifecycleTest {
    private fun source(path: String): String =
        listOf(File("app/src/main/java/com/n2k02/pbar/$path"),
            File("src/main/java/com/n2k02/pbar/$path"))
            .first { it.isFile }.readText()

    @Test fun mediaCallbacksDoNotRestartPeriodicSessionQuery() {
        val listener = source("ProgressNotificationListener.kt")
        assertFalse(listener.contains("progressTicker"))
        assertTrue(listener.contains("progressHandler.postDelayed(refreshRunnable, 100L)"))
        assertTrue(listener.contains("if (isQuitRequested() || refreshScheduled) return"))
        assertTrue(listener.contains("refreshScheduled = false"))
        assertTrue(listener.contains("mediaSessions.addOnActiveSessionsChangedListener"))
        assertTrue(listener.contains("override fun onPlaybackStateChanged"))
        assertTrue(listener.contains("override fun onMetadataChanged"))
        assertTrue(listener.contains("if (::mediaSessions.isInitialized) {\n                refreshMediaSessions(currentMediaControllers())"))
    }

    @Test fun allListenerExitPathsReleaseTimersSessionsAndOverlay() {
        val listener = source("ProgressNotificationListener.kt")
        val cleanup = listener.substringAfter("private fun releaseResources()")
            .substringBefore("private fun stopForQuit()")
        for (required in listOf(
            "progressHandler.removeCallbacksAndMessages(null)",
            "timerHandler.removeCallbacksAndMessages(null)",
            "unregisterRefreshReceiver()",
            "overlayController?.stop()",
            "controller.unregisterCallback(callback)",
            "registeredControllers.clear()",
            "notificationControllers.clear()",
            "mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged)"))
            assertTrue("Missing exit cleanup: $required",cleanup.contains(required))
        val disconnected = listener.substringAfter("override fun onListenerDisconnected()")
            .substringBefore("override fun onDestroy()")
        assertTrue(disconnected.contains("releaseResources()"))
        assertTrue(disconnected.contains("if (!isQuitRequested())"))
        assertTrue(listener.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onNotificationPosted(").contains("releaseResources()"))
        assertTrue(listener.substringAfter("private fun stopForQuit()")
            .substringBefore("override fun onListenerConnected()").contains("releaseResources()"))
    }

    @Test fun progressFramePacingAndFullscreenBroadcastsAreStateDriven() {
        val overlay = source("OverlayController.kt")
        assertTrue(overlay.contains("ProgressFramePacer.nextDelayMs("))
        assertTrue(overlay.contains("if (nextDelay != null) postInvalidateDelayed(nextDelay)"))
        assertTrue(overlay.contains("animationAllowed &&"))
        val accessibility = source("PBarAccessibilityService.kt")
        assertTrue(accessibility.contains("if (lastReportedFullscreen == value) return"))
        assertTrue(accessibility.contains("reportFullscreen(false)"))
        assertTrue(accessibility.contains("override fun onDestroy()"))
    }
}

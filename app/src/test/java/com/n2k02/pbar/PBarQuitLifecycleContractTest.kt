package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contracts for a complete explicit Quit of the OS-bound listener. */
class PBarQuitLifecycleContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun quitPersistsStopBeforeUnbindingListenerAndStopsUpdater() {
        val main = source("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        assertTrue(main.contains(".putBoolean(\"quit_requested\", true)"))
        assertTrue(main.contains(".putBoolean(\"overlay_enabled\", false).commit()"))
        assertTrue(main.contains("sendBroadcast(Intent(ProgressNotificationListener.ACTION_QUIT)"))
        assertTrue(main.contains("LiveUpdater.controller.cancel()"))
        assertTrue(main.contains("NotificationListenerService.requestRebind("))
    }

    @Test fun listenerStopsTimersCallbacksAndOverlayWithoutAutomaticReconnect() {
        val listener = source("app/src/main/java/com/n2k02/pbar/ProgressNotificationListener.kt")
        listOf("ACTION_QUIT", "stopForQuit()", "requestUnbind()",
            "progressHandler.removeCallbacksAndMessages(null)",
            "timerHandler.removeCallbacksAndMessages(null)", "overlayController?.stop()",
            "controller.unregisterCallback(callback)",
            "mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged)",
            "if (!isQuitRequested())",
            "if (isQuitRequested()) { stopForQuit(); return }").forEach {
                assertTrue("Missing Quit cleanup: $it", listener.contains(it))
            }
    }
}

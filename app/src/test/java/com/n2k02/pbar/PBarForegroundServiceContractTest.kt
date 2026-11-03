package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarForegroundServiceContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun foregroundServiceIsStickyAndUsesSpecialUseType() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val service = source("app/src/main/java/com/n2k02/pbar/PBarForegroundService.kt")
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertFalse(manifest.contains("POST_NOTIFICATIONS"))
        assertTrue(service.contains("startForegroundService("))
        assertTrue(service.contains("return START_STICKY"))
        assertTrue(service.contains("return START_NOT_STICKY"))
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(service.contains("Settings.canDrawOverlays(context)"))
        assertTrue(service.contains("hasNotificationListenerAccess(context)"))
    }

    @Test fun explicitQuitStopsTheForegroundService() {
        val main = source("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        val quit = main.substringAfter("private fun quitPBar()").substringBefore("@OptIn")
        assertTrue(quit.contains("PBarForegroundService.stop(this)"))
    }
}

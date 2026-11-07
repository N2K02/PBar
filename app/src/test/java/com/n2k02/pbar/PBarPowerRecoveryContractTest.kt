package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarPowerRecoveryContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun foregroundAndListenerReactToChargingAndPowerSaver() {
        val service = source("app/src/main/java/com/n2k02/pbar/PBarForegroundService.kt")
        val listener = source("app/src/main/java/com/n2k02/pbar/ProgressNotificationListener.kt")
        assertTrue(service.contains("Intent.ACTION_POWER_CONNECTED"))
        assertTrue(service.contains("PowerManager.ACTION_POWER_SAVE_MODE_CHANGED"))
        assertTrue(listener.contains("Intent.ACTION_POWER_CONNECTED"))
        assertTrue(listener.contains("PowerManager.ACTION_POWER_SAVE_MODE_CHANGED"))
    }

    @Test fun persistedFallbackHonorsQuitAndPermissions() {
        val recovery = source("app/src/main/java/com/n2k02/pbar/PBarPowerRecovery.kt")
        val main = source("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(recovery.contains(".setPersisted(true)"))
        assertTrue(recovery.contains("NotificationListenerService.requestRebind"))
        assertTrue(recovery.contains("!ProgressNotificationListener.isConnectedInProcess"))
        assertTrue(source("app/src/main/java/com/n2k02/pbar/ProgressNotificationListener.kt")
            .contains("isConnectedInProcess = false"))
        assertTrue(recovery.contains("Settings.canDrawOverlays(context)"))
        assertTrue(recovery.contains("PBarForegroundService.hasNotificationListenerAccess(context)"))
        assertTrue(main.contains("PBarPowerRecovery.cancel(this)"))
        assertTrue(manifest.contains("android.permission.BIND_JOB_SERVICE"))
    }
}

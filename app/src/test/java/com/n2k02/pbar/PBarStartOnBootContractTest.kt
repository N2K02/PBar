package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarStartOnBootContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun bootRestoreDefaultsEnabledAndRequiresBothOverlayPermissions() {
        val receiver = source("app/src/main/java/com/n2k02/pbar/PBarBootReceiver.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val activity = source("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        assertTrue(receiver.contains("getBoolean(\"start_on_boot\", true)"))
        assertTrue(receiver.contains("PBarForegroundService.hasNotificationListenerAccess(context)"))
        assertTrue(receiver.contains("Settings.canDrawOverlays(context)"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(activity.contains("SettingsPage.STARTUP"))
        assertTrue(activity.contains("putBoolean(\"start_on_boot\", value)"))
    }
}

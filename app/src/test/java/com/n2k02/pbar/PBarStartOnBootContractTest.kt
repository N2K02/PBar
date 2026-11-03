package com.n2k02.pbar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PBarStartOnBootContractTest {
    @Test fun bootRestoreDefaultsEnabledAndRequiresBothOverlayPermissions() {
        val root = File("src/main")
        val receiver = File(root, "java/com/n2k02/pbar/PBarBootReceiver.kt").readText()
        val manifest = File(root, "AndroidManifest.xml").readText()
        val activity = File(root, "java/com/n2k02/pbar/MainActivity.kt").readText()
        assertTrue(receiver.contains("getBoolean(\"start_on_boot\", true)"))
        assertTrue(receiver.contains("PBarForegroundService.hasNotificationListenerAccess(context)"))
        assertTrue(receiver.contains("Settings.canDrawOverlays(context)"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(activity.contains("SettingsPage.STARTUP"))
        assertTrue(activity.contains("putBoolean(\"start_on_boot\", value)"))
    }
}

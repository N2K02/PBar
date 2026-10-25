package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both Settings navigation and About & updates must show the shared New badge. */
class UpdateBadgeContractTest {
    @Test fun newUpdateAppearsOnSettingsNavigationAndSubmenu() {
        val path = "app/src/main/java/com/n2k02/pbar/MainActivity.kt"
        val source = listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
        assertTrue(source.contains("val updateStatus by LiveUpdater.controller.status.collectAsState()"))
        assertTrue(source.contains("N2K02UpdateBadgedIcon(updateStatus)"))
        assertTrue(source.contains("N2K02UpdateBadge(updateStatus)"))
        assertTrue(source.contains("updateStatus.hasPendingUpdate()"))
        assertTrue(source.contains("headlineContent = { Text(\"About & updates\") }"))
    }
}

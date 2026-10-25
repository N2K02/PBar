package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the mRadio-inspired Settings grouping while retaining app-specific pages. */
class SettingsConsistencyContractTest {
    @Test fun settingsRowsAndUpdatesFollowConsistentOrder() {
        val relative = "app/src/main/java/com/n2k02/pbar/MainActivity.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("SettingsPage.MAIN -> {", "")
        assertTrue("Settings section missing", menu.isNotBlank())
        assertTrue("Settings group title is missing", menu.contains("Make PBar your own"))
        assertTrue("Settings menu item is missing", menu.contains("headlineContent = { Text(\"Appearance\") }"))
        assertTrue("Updates or diagnostic footer missing", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        val divider = menu.indexOf("HorizontalDivider()")
        val update = menu.indexOf("headlineContent = { Text(\"About & updates\") }")
        assertTrue("Divider must separate settings and diagnostic/update links", divider >= 0 && update > divider)
    }
}

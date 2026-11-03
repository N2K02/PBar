package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarLiveStatusContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun liveStatusDetailsAreAlwaysVisible() {
        val main = source("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        val card = main.substringAfter("Text(\"Live status\"").substringBefore("Tab.STYLE -> {")
        assertFalse(main.contains("showTrackingDetails"))
        assertFalse(main.contains("Hide details"))
        assertFalse(main.contains("Text(\"Details\")"))
        assertTrue(card.contains("Tracking details"))
        assertTrue(card.contains("diagnosticSessionSummary"))
        assertTrue(card.contains("diagnosticNotificationSummary"))
        assertTrue(card.contains("Source app"))
    }

    @Test fun statusUsesColoredBadgesForStateAndListener() {
        val main = source("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        assertTrue(main.contains("PBarStatusBadge"))
        assertTrue(main.contains("\"LIVE\""))
        assertTrue(main.contains("\"IDLE\""))
        assertTrue(main.contains("LISTENER CONNECTED"))
        assertTrue(main.contains("LISTENER DISCONNECTED"))
        assertTrue(main.contains("LinearProgressIndicator("))
    }
}

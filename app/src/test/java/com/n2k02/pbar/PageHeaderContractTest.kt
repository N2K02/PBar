package com.n2k02.pbar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight source-level regression guard for the mRadio header contract.
 * Visual layout and playback behavior still require Android UI validation.
 */
class PageHeaderContractTest {
    private fun source(): String {
        val relative = "com/n2k02/pbar/MainActivity.kt"
        return listOf(File("src/main/java", relative), File("app/src/main/java", relative))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing app UI source: $relative")
    }

    @Test fun pageHeaderHasLeadingIconAndRightSideAppName() {
        val text = source()
        assertTrue(text.contains("topBar = {"))
        val header = text.substringAfter("topBar = {")
            .substringBefore("bottomBar = {")
        assertTrue("Missing page icon in header", header.contains("Icons.Rounded.HomeRounded"))
        assertTrue("Missing application name in header", header.contains("Text(\"PBar\""))
    }

    @Test fun quitIsOnlyInBottomBarToTheRightOfSettings() {
        val text = source()
        val header = text.substringAfter("topBar = {").substringBefore("bottomBar = {")
        val bar = text.substringAfter("bottomBar = {").substringBefore(") { padding ->")
        val home = text.substringAfter("Tab.HOME -> {").substringBefore("Tab.STYLE -> {")
        assertFalse("No quit action in header", header.contains("quitPBar()"))
        assertFalse("Home must not include an extra Quit PBar button", home.contains("Quit PBar"))
        assertTrue("Quit item must be in bottom navigation", bar.contains("label = { Text(\"Quit\") }"))
        assertTrue("Quit must follow Settings", bar.indexOf("label = { Text(\"Quit\") }") >
            bar.indexOf("label = { Text(\"Settings\") }"))
        assertTrue("Quit must retain shutdown semantics", bar.contains("onClick = { quitPBar() }"))
        val shutdown = text.substringAfter("private fun quitPBar()").substringBefore("@OptIn(ExperimentalMaterial3Api")
        assertTrue(shutdown.contains("putBoolean(\"overlay_enabled\", false)"))
        assertTrue(shutdown.contains("ACTION_QUIT"))
        assertTrue(shutdown.contains("LiveUpdater.controller.cancel()"))
        assertTrue(shutdown.contains("finishAndRemoveTask()"))
    }

    @Test fun appTitleIsNotProvidedBySharedUpdater() {
        val header = source().substringAfter("topBar = {")
            .substringBefore("bottomBar = {")
        assertFalse("App header should not duplicate updater title", header.contains("N2K02UpdateTitle("))
    }
}

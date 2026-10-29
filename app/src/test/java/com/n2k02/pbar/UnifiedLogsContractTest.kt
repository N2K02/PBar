package com.n2k02.pbar
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class UnifiedLogsContractTest {
    private fun read(p: String) = listOf(File(p), File("../$p")).firstOrNull { it.isFile }
        ?.readText() ?: error(p)
    @Test fun logsAreAnInAppSubpageWithPagingAndControls() {
        val main = read("app/src/main/java/com/n2k02/pbar/MainActivity.kt")
        val logs = read("app/src/main/java/com/n2k02/pbar/UnifiedLogsPage.kt")
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(main.contains("SettingsPage.LOGS -> Unit"))
        assertTrue(main.contains("PBarLogsPage("))
        assertTrue(main.contains("AppLog.init(applicationContext)"))
        assertFalse(main.contains("LogsActivity::class.java"))
        assertFalse(manifest.contains("LogsActivity"))
        listOf("followLatest", "Text(\"Prev\",", "Text(\"Next\",", "Text(\"Copy\",",
            "Text(\"Clear all\",", "Switch(checked = enabled").forEach { assertTrue(it, logs.contains(it)) }
        assertTrue(logs.contains("Icons.Filled.ContentCopy"))
        assertTrue(logs.contains("Icons.Filled.DeleteSweep"))
        assertTrue(logs.contains("Icons.AutoMirrored.Filled.KeyboardArrowLeft"))
        assertTrue(logs.contains("Icons.AutoMirrored.Filled.KeyboardArrowRight"))
        assertTrue(logs.contains("horizontalArrangement = Arrangement.SpaceBetween"))
        assertFalse(logs.contains("Text(\"Back\")"))
        assertTrue(main.contains("settingsPage == SettingsPage.LOGS -> Icons.Rounded.Article"))
        assertTrue(main.contains("leadingContent = { Icon(Icons.Rounded.Article, null) }"))
    }
}

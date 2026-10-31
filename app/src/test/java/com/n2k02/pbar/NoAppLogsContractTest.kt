package com.n2k02.pbar
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NoAppLogsContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    @Test fun noEmbeddedLogPage() {
        assertFalse(src("app/src/main/java/com/n2k02/pbar/MainActivity.kt").contains("PBarLogsPage"))
    }
    @Test fun noJournalProvider() {
        assertTrue(src("app/src/main/java/com/n2k02/pbar/AppLog.kt").contains("Log.d("))
        assertFalse(src("app/src/main/java/com/n2k02/pbar/AppLog.kt").contains("appendText("))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("CrashJournalProvider"))
    }
}

package com.n2k02.pbar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayAnimationEfficiencyTest {
    @Test fun progressOverlayRedrawIsBoundedAndStopsWhenStatic() {
        val paths = listOf(
            File("src/main/java/com/n2k02/pbar/OverlayController.kt"),
            File("app/src/main/java/com/n2k02/pbar/OverlayController.kt")
        )
        val content = paths.first { it.exists() }.readText()
        assertTrue(content.contains("postInvalidateDelayed(33L)"))
        assertFalse(content.contains("postInvalidateOnAnimation()"))
        assertTrue(content.contains("demoActive() || (signal && (indeterminate"))
        assertTrue(content.contains("media && mediaPlaying && mediaDurationMs > 0L"))
    }
}

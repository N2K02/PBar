package com.n2k02.pbar

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class OverlayAnimationEfficiencyTest {
    @Test fun activeProgressRedrawsAreCapped() {
        val file = listOf(
            File("src/main/java/com/n2k02/pbar/OverlayController.kt"),
            File("app/src/main/java/com/n2k02/pbar/OverlayController.kt")
        ).first { it.exists() }
        val source = file.readText()
        assertTrue(source.contains("postInvalidateDelayed(33L)"))
        assertFalse(source.contains("postInvalidateOnAnimation()"))
        assertTrue(source.contains("media && mediaPlaying && mediaDurationMs > 0L"))
    }
}

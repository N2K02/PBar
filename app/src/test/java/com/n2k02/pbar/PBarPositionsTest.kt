package com.n2k02.pbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarPositionsTest {
    @Test fun portraitHasFourOptionsWithSafeSystemBarPositions() {
        assertEquals(
            listOf("top", "below_status_bar", "bottom", "above_navigation_bar"),
            PBarPositions.portraitChoices.map { it.first },
        )
        assertEquals("below_status_bar", PBarPositions.portrait("below_status_bar"))
        assertEquals("above_navigation_bar", PBarPositions.portrait("above_navigation_bar"))
    }

    @Test fun landscapeOnlyHasLeftAndRight() {
        assertEquals(listOf("left", "right"), PBarPositions.landscapeChoices.map { it.first })
        assertEquals("left", PBarPositions.landscape("center"))
        assertEquals("left", PBarPositions.landscape(null))
        assertEquals("right", PBarPositions.landscape("right"))
    }

    @Test fun safePortraitOffsetsReflectVisibleBarsOrFallBackToEdges() {
        assertEquals(0, PBarPositions.portraitOffsetPx("top", 65, 85))
        assertEquals(65, PBarPositions.portraitOffsetPx("below_status_bar", 65, 85))
        assertEquals(0, PBarPositions.portraitOffsetPx("bottom", 65, 85))
        assertEquals(85, PBarPositions.portraitOffsetPx("above_navigation_bar", 65, 85))
        assertEquals(0, PBarPositions.portraitOffsetPx("above_navigation_bar", 65, 0))
        assertEquals(0, PBarPositions.portraitOffsetPx("below_status_bar", 0, 85))
    }

    @Test fun bottomChoicesUseBottomGravity() {
        assertTrue(PBarPositions.anchoredAtBottom("bottom"))
        assertTrue(PBarPositions.anchoredAtBottom("above_navigation_bar"))
        assertFalse(PBarPositions.anchoredAtBottom("top"))
        assertFalse(PBarPositions.anchoredAtBottom("below_status_bar"))
    }
}

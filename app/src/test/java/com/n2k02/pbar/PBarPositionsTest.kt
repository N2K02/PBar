package com.n2k02.pbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PBarPositionsTest {
    private val fourPositions = listOf("top", "below_status_bar", "bottom", "above_navigation_bar")

    @Test fun portraitHasFourOptionsWithSafeSystemBarPositions() {
        assertEquals(fourPositions, PBarPositions.portraitChoices.map { it.first })
        assertEquals("below_status_bar", PBarPositions.portrait("below_status_bar"))
        assertEquals("above_navigation_bar", PBarPositions.portrait("above_navigation_bar"))
    }

    @Test fun landscapeMatchesPortraitOptionsAndAcceptsEachOne() {
        assertEquals(PBarPositions.portraitChoices, PBarPositions.landscapeChoices)
        assertEquals(fourPositions, PBarPositions.landscapeChoices.map { it.first })
        fourPositions.forEach { assertEquals(it, PBarPositions.landscape(it)) }
    }

    @Test fun legacyLandscapePositionsMigrateToHorizontalEdges() {
        assertEquals("top", PBarPositions.landscape(null))
        assertEquals("top", PBarPositions.landscape("left"))
        assertEquals("bottom", PBarPositions.landscape("right"))
        assertEquals("top", PBarPositions.landscape("center"))
        assertEquals("default", PBarPositions.landscape("unsupported", fallback = "default"))
    }

    @Test fun systemBarOffsetsApplyToBothOrientationsAndHiddenBars() {
        for (position in fourPositions) {
            val expected = when (position) {
                "below_status_bar" -> 65
                "above_navigation_bar" -> 85
                else -> 0
            }
            assertEquals(expected, PBarPositions.horizontalOffsetPx(position, 65, 85))
        }
        assertEquals(0, PBarPositions.horizontalOffsetPx("above_navigation_bar", 65, 0))
        assertEquals(0, PBarPositions.horizontalOffsetPx("below_status_bar", 0, 85))
        assertEquals(0, PBarPositions.horizontalOffsetPx("below_status_bar", -1, 85))
        assertEquals(0, PBarPositions.horizontalOffsetPx("above_navigation_bar", 65, -1))
    }

    @Test fun bottomChoicesUseBottomGravityInEitherOrientation() {
        assertTrue(PBarPositions.anchoredAtBottom("bottom"))
        assertTrue(PBarPositions.anchoredAtBottom("above_navigation_bar"))
        assertFalse(PBarPositions.anchoredAtBottom("top"))
        assertFalse(PBarPositions.anchoredAtBottom("below_status_bar"))
    }
}

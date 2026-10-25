package com.n2k02.pbar

/**
 * Position choices shared by global preferences, per-app overrides and the overlay.
 * Both orientations use horizontal lines, with independent saved positions.
 */
internal object PBarPositions {
    const val TOP = "top"
    const val BELOW_STATUS_BAR = "below_status_bar"
    const val BOTTOM = "bottom"
    const val ABOVE_NAVIGATION_BAR = "above_navigation_bar"

    val portraitChoices = listOf(
        TOP to "Top edge",
        BELOW_STATUS_BAR to "Below status bar",
        BOTTOM to "Bottom edge",
        ABOVE_NAVIGATION_BAR to "Above navigation bar (when visible)",
    )
    val landscapeChoices = portraitChoices

    fun portrait(value: String?, fallback: String = TOP): String =
        value?.takeIf { it in setOf(TOP, BELOW_STATUS_BAR, BOTTOM, ABOVE_NAVIGATION_BAR) }
            ?: fallback

    /**
     * Legacy landscape preferences used left/right edges for a vertical bar.
     * Map them to top/bottom when switching to the horizontal landscape bar.
     */
    fun landscape(value: String?, fallback: String = TOP): String =
        when (value) {
            TOP, BELOW_STATUS_BAR, BOTTOM, ABOVE_NAVIGATION_BAR -> value
            "right" -> BOTTOM
            "left", "center" -> TOP
            else -> fallback
        }

    fun anchoredAtBottom(value: String): Boolean =
        value == BOTTOM || value == ABOVE_NAVIGATION_BAR

    /** Insets are pixel values for currently visible system bars in either orientation. */
    fun horizontalOffsetPx(value: String, statusBarPx: Int, navigationBarPx: Int): Int =
        when (value) {
            BELOW_STATUS_BAR -> statusBarPx.coerceAtLeast(0)
            ABOVE_NAVIGATION_BAR -> navigationBarPx.coerceAtLeast(0)
            else -> 0
        }
}

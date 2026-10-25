package com.n2k02.pbar

/**
 * Position choices shared by global preferences, per-app overrides and the overlay.
 * Portrait stays horizontal; landscape stays vertical.
 */
internal object PBarPositions {
    const val TOP = "top"
    const val BELOW_STATUS_BAR = "below_status_bar"
    const val BOTTOM = "bottom"
    const val ABOVE_NAVIGATION_BAR = "above_navigation_bar"
    const val LEFT = "left"
    const val RIGHT = "right"

    val portraitChoices = listOf(
        TOP to "Top edge",
        BELOW_STATUS_BAR to "Below status bar",
        BOTTOM to "Bottom edge",
        ABOVE_NAVIGATION_BAR to "Above navigation bar (when visible)",
    )
    val landscapeChoices = listOf(
        LEFT to "Left edge",
        RIGHT to "Right edge",
    )

    fun portrait(value: String?, fallback: String = TOP): String =
        value?.takeIf { it in setOf(TOP, BELOW_STATUS_BAR, BOTTOM, ABOVE_NAVIGATION_BAR) }
            ?: fallback

    // "center" is a legacy setting. It now maps to the left edge; never render Centre.
    fun landscape(value: String?, fallback: String = LEFT): String =
        when (value) {
            LEFT, RIGHT -> value
            "center" -> LEFT
            else -> fallback
        }

    fun anchoredAtBottom(value: String): Boolean =
        value == BOTTOM || value == ABOVE_NAVIGATION_BAR

    /** Insets are pixel values for currently visible system bars. */
    fun portraitOffsetPx(value: String, statusBarPx: Int, navigationBarPx: Int): Int =
        when (value) {
            BELOW_STATUS_BAR -> statusBarPx.coerceAtLeast(0)
            ABOVE_NAVIGATION_BAR -> navigationBarPx.coerceAtLeast(0)
            else -> 0
        }
}

package com.n2k02.pbar

import kotlin.math.abs

/**
 * Avoid repainting a thin bar more often than its visible pixel can change.
 * Indeterminate and demo animations retain the original smooth 30 fps cap.
 * All delays are bounded so a long media item still receives fresh frames.
 */
internal object ProgressFramePacer {
    fun nextDelayMs(
        demo: Boolean, indeterminate: Boolean,
        mediaPlaying: Boolean, mediaDurationMs: Long, mediaSpeed: Float,
        timerActive: Boolean, timerDurationMs: Long,
        pixelLength: Int, correctingPosition: Boolean
    ): Long? {
        if (demo || indeterminate || correctingPosition) return 33L
        val fullDuration = when {
            mediaPlaying && mediaDurationMs > 0L && abs(mediaSpeed) > 0.001f ->
                mediaDurationMs / abs(mediaSpeed).toDouble()
            timerActive && timerDurationMs > 0L -> timerDurationMs.toDouble()
            else -> return null
        }
        // Two updates per display pixel, capped to 30fps for short progress and
        // at least one refresh every 250ms for long progress.
        return (fullDuration / pixelLength.coerceAtLeast(1) / 2.0)
            .toLong().coerceIn(33L, 250L)
    }
}

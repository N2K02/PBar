package com.n2k02.pbar

/**
 * Timer overlay interpolates each frame using elapsedRealtime, so background
 * notification updates are needed only as the displayed whole percent changes.
 */
internal object TimerTickPolicy {
    fun nextReportDelayMs(remainingMs: Long, totalMs: Long): Long? {
        if (remainingMs <= 0L || totalMs <= 0L) return null
        val elapsed = (totalMs - remainingMs.coerceAtMost(totalMs)).coerceAtLeast(0L)
        val progress = (elapsed.toDouble() * 100.0 / totalMs)
            .toInt().coerceIn(0, 99)
        val nextBoundary = totalMs.toDouble() * (progress + 1) / 100.0
        return (nextBoundary - elapsed).toLong().coerceIn(1000L, 30_000L)
    }
}

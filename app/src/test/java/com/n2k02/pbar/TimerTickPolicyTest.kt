package com.n2k02.pbar

import org.junit.Assert.*
import org.junit.Test

class TimerTickPolicyTest {
    @Test fun longTimerDoesNotWakeEverySecond() {
        val delay = TimerTickPolicy.nextReportDelayMs(60 * 60 * 1000L, 60 * 60 * 1000L)
        assertEquals(30_000L, delay)
        val middle = TimerTickPolicy.nextReportDelayMs(30 * 60 * 1000L, 60 * 60 * 1000L)
        assertEquals(30_000L, middle)
    }

    @Test fun shortTimersRemainResponsiveAndNeverOverrunOneSecondMinimum() {
        assertEquals(1000L, TimerTickPolicy.nextReportDelayMs(20_000L, 20_000L))
        val mid = TimerTickPolicy.nextReportDelayMs(4000L, 10_000L)!!
        assertTrue(mid in 1000L..30_000L)
        assertEquals(1000L, TimerTickPolicy.nextReportDelayMs(100L, 10_000L))
    }

    @Test fun finishedOrInvalidTimerNeverReposts() {
        assertNull(TimerTickPolicy.nextReportDelayMs(0L, 30000L))
        assertNull(TimerTickPolicy.nextReportDelayMs(-2L, 30000L))
        assertNull(TimerTickPolicy.nextReportDelayMs(10000L, 0L))
        assertNull(TimerTickPolicy.nextReportDelayMs(10000L, -1L))
        assertTrue(TimerTickPolicy.nextReportDelayMs(Long.MAX_VALUE, Long.MAX_VALUE)!! > 0L)
    }
}

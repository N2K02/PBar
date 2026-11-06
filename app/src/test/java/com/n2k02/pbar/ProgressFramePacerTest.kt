package com.n2k02.pbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressFramePacerTest {
    private fun delay(
        demo: Boolean = false, indeterminate: Boolean = false,
        playing: Boolean = false, duration: Long = 0L, speed: Float = 1f,
        timer: Boolean = false, timerDuration: Long = 0L,
        px: Int = 1080, correcting: Boolean = false
    ) = ProgressFramePacer.nextDelayMs(
        demo, indeterminate, playing, duration, speed,
        timer, timerDuration, px, correcting)

    @Test fun animationsKeepSmoothOriginalThirtyFpsCap() {
        assertEquals(33L, delay(demo = true))
        assertEquals(33L, delay(indeterminate = true))
        assertEquals(33L, delay(playing = true, duration = 240000, correcting = true))
    }

    @Test fun longMediaUsesFarFewerDrawsWithoutSkippingMoreThanPixelStep() {
        val result = delay(playing = true, duration = 240000L)!!
        assertTrue(result in 34L..250L)
        assertTrue(result <= 240000L / 1080L)
        assertEquals(33L, delay(playing = true, duration = 15000L))
        val fast = delay(playing = true, duration = 240000L, speed = 2f)!!
        assertTrue(fast < result)
    }

    @Test fun timerAndMissingAnimationStopOrBoundPacing() {
        assertTrue(delay(timer = true, timerDuration = 3600000L)!! <= 250L)
        assertEquals(250L, delay(timer = true, timerDuration = 3600000L))
        assertNull(delay())
        assertNull(delay(playing = false, duration = 200000L))
        assertNull(delay(playing = true, duration = 0L))
        assertNull(delay(playing = true, duration = 200000L, speed = 0f))
        assertEquals(delay(playing = true, duration = 100000L, speed = 1f),
            delay(playing = true, duration = 100000L, speed = -1f))
    }
}

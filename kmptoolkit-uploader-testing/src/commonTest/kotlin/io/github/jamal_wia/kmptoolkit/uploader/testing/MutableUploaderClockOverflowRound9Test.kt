package io.github.jamal_wia.kmptoolkit.uploader.testing

import kotlin.test.Test
import kotlin.test.assertEquals

/** R9-S1 sweep: `advanceBy` stops at the Long range instead of wrapping into the past (or the future). */
class MutableUploaderClockOverflowRound9Test {

    @Test
    fun `advancing past the top of the range stays at the top`() {
        val clock = MutableUploaderClock(Long.MAX_VALUE - 5L)
        clock.advanceBy(10L)
        assertEquals(Long.MAX_VALUE, clock.nowMillis)
        clock.advanceBy(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, clock.nowMillis)
    }

    @Test
    fun `stepping back past the bottom of the range stays at the bottom`() {
        val clock = MutableUploaderClock(Long.MIN_VALUE + 5L)
        clock.advanceBy(-10L)
        assertEquals(Long.MIN_VALUE, clock.nowMillis)
        clock.advanceBy(Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, clock.nowMillis)
    }

    @Test
    fun `ordinary steps are exact`() {
        val clock = MutableUploaderClock(1_000L)
        clock.advanceBy(500L)
        clock.advanceBy(-1_700L)
        assertEquals(-200L, clock.nowMillis)
    }
}

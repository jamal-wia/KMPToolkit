package io.github.jamal_wia.kmptoolkit.video.player.compose

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The keep-screen-on bookkeeping behind every iOS surface: `UIApplication.idleTimerDisabled` is one
 * app-wide flag, shared by any number of surfaces and by the app itself.
 */
class IdleTimerHoldsTest {

    /** A stand-in for `UIApplication.idleTimerDisabled` that records every write. */
    private class Flag(var value: Boolean) {
        val writes: MutableList<Boolean> = mutableListOf()
        val holds: IdleTimerHolds = IdleTimerHolds(read = { value }, write = { value = it; writes += it })
    }

    @Test
    fun `the first hold disables the timer and the last release restores it`() {
        val flag = Flag(value = false)

        flag.holds.acquire()
        assertEquals(true, flag.value)

        flag.holds.release()
        assertEquals(false, flag.value)
        assertEquals(listOf(true, false), flag.writes)
    }

    @Test
    fun `two surfaces keep the timer disabled until both stop`() {
        val flag = Flag(value = false)

        flag.holds.acquire()
        flag.holds.acquire()
        flag.holds.release()
        assertEquals(true, flag.value, "one surface still wants the screen on")

        flag.holds.release()
        assertEquals(false, flag.value)
        assertEquals(listOf(true, false), flag.writes, "no write between the first hold and the last release")
    }

    @Test
    fun `an app that disabled the timer itself keeps it disabled`() {
        val flag = Flag(value = true)

        flag.holds.acquire()
        flag.holds.release()

        assertEquals(true, flag.value)
    }

    @Test
    fun `the value saved is the one at the first hold of each round`() {
        val flag = Flag(value = false)
        flag.holds.acquire()
        flag.holds.release()

        // The app changes its mind between two rounds of playback.
        flag.value = true
        flag.holds.acquire()
        flag.holds.release()

        assertEquals(true, flag.value)
    }

    @Test
    fun `a release without a hold changes nothing`() {
        val flag = Flag(value = false)

        flag.holds.release()
        flag.holds.acquire()
        flag.holds.release()
        flag.holds.release()

        assertEquals(false, flag.value)
        assertEquals(listOf(true, false), flag.writes)
    }
}

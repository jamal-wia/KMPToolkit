package io.github.jamal_wia.kmptoolkit.logging.overlay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `toggle` is one atomic flip: an even number of flips from any threads ends where it began, an odd one flipped. */
class OverlayVisibilityStormTest {

    private fun storm(flips: Int, threads: Int, state: LogOverlayState) {
        val start = CountDownLatch(1)
        val workers = List(threads) {
            thread(isDaemon = true) { start.await(5, TimeUnit.SECONDS); repeat(flips) { state.toggle() } }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }
        assertTrue(workers.none { it.isAlive }, "a toggler is stuck")
    }

    @Test
    fun `an even number of concurrent toggles leaves the state where it started and an odd one flips it`() {
        val state = LogOverlayState()
        storm(flips = 5_000, threads = 8, state = state)
        assertEquals(false, state.isVisible.value)
        storm(flips = 5_001, threads = 8, state = state)
        assertEquals(false, state.isVisible.value, "8 threads x 5001 flips is even")
        storm(flips = 5_001, threads = 7, state = state)
        assertEquals(true, state.isVisible.value, "7 threads x 5001 flips is odd")
    }

    @Test
    fun `records taken as a snapshot are never changed by later writes`() {
        val state = LogOverlayState(maxRecords = 5)
        repeat(5) { state.record(io.github.jamal_wia.kmptoolkit.logging.LogLevel.INFO, "t", "$it") }
        val snapshot = state.records.value
        val ids = snapshot.map { it.id }
        repeat(50) { state.record(io.github.jamal_wia.kmptoolkit.logging.LogLevel.INFO, "t", "later $it") }
        state.clear()
        assertEquals(ids, snapshot.map { it.id })
        assertEquals(5, snapshot.size)
    }
}

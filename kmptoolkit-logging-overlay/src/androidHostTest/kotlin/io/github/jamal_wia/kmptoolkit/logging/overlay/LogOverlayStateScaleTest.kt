package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The buffer's cost with a large bound and contending writers, bounded in real time. */
class LogOverlayStateScaleTest {

    @Test
    fun `a large buffer under eight writers finishes in bounded time and keeps the bound`() {
        val state = LogOverlayState(maxRecords = 20_000)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val started: Long = System.nanoTime()
        val writers: List<Thread> = List(8) { writer ->
            thread(isDaemon = true, name = "scale-writer-$writer") {
                try {
                    repeat(5_000) { i -> state.record(LogLevel.INFO, "w$writer", "m$i") }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        writers.forEach { it.join(30_000) }
        val elapsedMs: Long = (System.nanoTime() - started) / 1_000_000
        assertTrue(writers.none { it.isAlive }, "writers still running after 30 s (elapsed $elapsedMs ms)")
        assertEquals(emptyList(), failures.toList())
        assertEquals(20_000, state.records.value.size)
        assertTrue(elapsedMs < 15_000, "40 000 records into a 20 000 buffer took $elapsedMs ms")
    }

    @Test
    fun `a single record with a huge message is retained as given and does not break the bound`() {
        val state = LogOverlayState(maxRecords = 2)
        repeat(5) { state.record(LogLevel.INFO, "T", "x".repeat(1_000_000)) }
        assertEquals(2, state.records.value.size)
        assertEquals(listOf(4L, 5L), state.records.value.map { it.id })
    }
}

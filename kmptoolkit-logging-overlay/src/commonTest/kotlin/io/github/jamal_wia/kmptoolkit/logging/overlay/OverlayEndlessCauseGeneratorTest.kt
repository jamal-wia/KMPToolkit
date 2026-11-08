package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Throwables whose graph is not a finite, stable structure: a `cause` that is a fresh exception on every read
 * (an endless chain), a throwable listing a very large number of suppressed ones. `asLogSink()` never throws and the event is never lost, whatever the shape.
 */
class OverlayEndlessCauseGeneratorTest {

    private class Endless(private val n: Int = 0) : RuntimeException("level $n") {
        override val cause: Throwable get() = Endless(n + 1)
    }

    private class Wide(count: Int) : RuntimeException("wide") {
        init { repeat(count) { addSuppressed(IllegalStateException("s$it")) } }
    }

    @Test
    fun `an endless generated cause chain is recorded and summarised`() {
        val state = LogOverlayState()
        state.asLogSink().log(LogLevel.ERROR, "T", "endless", Endless())
        val record = state.records.value.single()
        assertEquals("endless", record.message)
        assertTrue(record.throwableText!!.length < 100_000, "the summary is bounded: ${record.throwableText!!.length}")
    }

    @Test
    fun `a throwable with fifty thousand suppressed exceptions is recorded and bounded`() {
        val state = LogOverlayState()
        state.asLogSink().log(LogLevel.ERROR, "T", "wide", Wide(50_000))
        val record = state.records.value.single()
        assertTrue(record.throwableText!!.length < 2_000_000, "bounded: ${record.throwableText!!.length}")
    }
}

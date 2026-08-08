package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [LogOverlayState], derived from `docs/kmptoolkit-logging-overlay/`: a bounded
 * FIFO buffer, a level threshold of its own, an atomic append, and a visibility flag.
 */
class LogOverlayStateTest {

    // --- Construction ---

    @Test
    fun `default max records is the documented constant`() {
        assertEquals(DEFAULT_MAX_RECORDS, LogOverlayState().maxRecords)
        assertEquals(200, DEFAULT_MAX_RECORDS)
    }

    @Test
    fun `default min level keeps every level`() {
        assertEquals(LogLevel.VERBOSE, LogOverlayState().minLevel)
    }

    @Test
    fun `a max records below one is rejected`() {
        assertFailsWith<IllegalArgumentException> { LogOverlayState(maxRecords = 0) }
        assertFailsWith<IllegalArgumentException> { LogOverlayState(maxRecords = -1) }
    }

    @Test
    fun `a max records of one is legal and keeps only the newest record`() {
        val state = LogOverlayState(maxRecords = 1)

        state.record(LogLevel.INFO, "T", "first")
        state.record(LogLevel.INFO, "T", "second")

        assertEquals(listOf("second"), state.records.value.map(LogRecord::message))
    }

    @Test
    fun `a fresh state holds no records and is hidden`() {
        val state = LogOverlayState()

        assertEquals(emptyList(), state.records.value)
        assertFalse(state.isVisible.value)
    }

    // --- Recording ---

    @Test
    fun `record captures level tag and message verbatim`() {
        val state = LogOverlayState()

        state.record(LogLevel.WARN, "Sync", "disk full")

        val record: LogRecord = state.records.value.single()
        assertEquals(LogLevel.WARN, record.level)
        assertEquals("Sync", record.tag)
        assertEquals("disk full", record.message)
        assertNull(record.throwableText)
    }

    @Test
    fun `an empty tag and an empty message are recorded as given`() {
        val state = LogOverlayState()

        state.record(LogLevel.INFO, "", "")

        val record: LogRecord = state.records.value.single()
        assertEquals("", record.tag)
        assertEquals("", record.message)
    }

    @Test
    fun `a throwable is stored as its stack trace text`() {
        val state = LogOverlayState()

        state.record(LogLevel.ERROR, "Net", "request failed", IllegalStateException("boom"))

        val text: String = assertNotNull(state.records.value.single().throwableText)
        assertTrue(text.contains("boom"), "stack trace text should contain the message, was: $text")
        assertTrue(
            text.contains("IllegalStateException"),
            "stack trace text should contain the exception type, was: $text",
        )
    }

    @Test
    fun `records keep insertion order oldest first`() {
        val state = LogOverlayState()

        state.record(LogLevel.INFO, "T", "one")
        state.record(LogLevel.INFO, "T", "two")
        state.record(LogLevel.INFO, "T", "three")

        assertEquals(listOf("one", "two", "three"), state.records.value.map(LogRecord::message))
    }

    @Test
    fun `ids start at one and increase by one`() {
        val state = LogOverlayState()

        repeat(3) { index -> state.record(LogLevel.INFO, "T", "m$index") }

        assertEquals(listOf(1L, 2L, 3L), state.records.value.map(LogRecord::id))
    }

    @Test
    fun `elapsed millis is not negative and never decreases`() {
        val state = LogOverlayState()

        repeat(5) { index -> state.record(LogLevel.INFO, "T", "m$index") }

        val elapsed: List<Long> = state.records.value.map(LogRecord::elapsedMillis)
        assertTrue(elapsed.all { it >= 0L }, "elapsed millis must not be negative, was $elapsed")
        assertEquals(elapsed.sorted(), elapsed, "elapsed millis must be monotonic, was $elapsed")
    }

    // --- Bounded buffer / eviction ---

    @Test
    fun `the buffer never grows past max records`() {
        val state = LogOverlayState(maxRecords = 3)

        repeat(50) { index -> state.record(LogLevel.INFO, "T", "m$index") }

        assertEquals(3, state.records.value.size)
    }

    @Test
    fun `eviction drops the oldest record first`() {
        val state = LogOverlayState(maxRecords = 3)

        listOf("a", "b", "c", "d").forEach { message -> state.record(LogLevel.INFO, "T", message) }

        assertEquals(listOf("b", "c", "d"), state.records.value.map(LogRecord::message))
    }

    @Test
    fun `ids stay unique and increasing across eviction`() {
        val state = LogOverlayState(maxRecords = 2)

        repeat(5) { index -> state.record(LogLevel.INFO, "T", "m$index") }

        assertEquals(listOf(4L, 5L), state.records.value.map(LogRecord::id))
    }

    @Test
    fun `filling exactly to max records evicts nothing`() {
        val state = LogOverlayState(maxRecords = 3)

        listOf("a", "b", "c").forEach { message -> state.record(LogLevel.INFO, "T", message) }

        assertEquals(listOf("a", "b", "c"), state.records.value.map(LogRecord::message))
    }

    // --- Level filtering ---

    @Test
    fun `an event below min level is dropped`() {
        val state = LogOverlayState(minLevel = LogLevel.WARN)

        state.record(LogLevel.VERBOSE, "T", "v")
        state.record(LogLevel.DEBUG, "T", "d")
        state.record(LogLevel.INFO, "T", "i")

        assertEquals(emptyList(), state.records.value)
    }

    @Test
    fun `an event at or above min level is kept`() {
        val state = LogOverlayState(minLevel = LogLevel.WARN)

        state.record(LogLevel.WARN, "T", "w")
        state.record(LogLevel.ERROR, "T", "e")

        assertEquals(listOf("w", "e"), state.records.value.map(LogRecord::message))
    }

    @Test
    fun `a dropped event does not consume an id`() {
        val state = LogOverlayState(minLevel = LogLevel.WARN)

        state.record(LogLevel.INFO, "T", "dropped")
        state.record(LogLevel.ERROR, "T", "kept")

        assertEquals(listOf(1L), state.records.value.map(LogRecord::id))
    }

    // --- clear ---

    @Test
    fun `clear empties the buffer`() {
        val state = LogOverlayState()
        state.record(LogLevel.INFO, "T", "one")
        state.record(LogLevel.INFO, "T", "two")

        state.clear()

        assertEquals(emptyList(), state.records.value)
    }

    @Test
    fun `clear on an already empty buffer is a no-op`() {
        val state = LogOverlayState()

        state.clear()

        assertEquals(emptyList(), state.records.value)
    }

    @Test
    fun `id numbering restarts after clear`() {
        val state = LogOverlayState()
        state.record(LogLevel.INFO, "T", "one")
        state.clear()

        state.record(LogLevel.INFO, "T", "two")

        assertEquals(listOf(1L), state.records.value.map(LogRecord::id))
    }

    @Test
    fun `clear does not change visibility`() {
        val state = LogOverlayState()
        state.show()

        state.clear()

        assertTrue(state.isVisible.value)
    }

    // --- Visibility ---

    @Test
    fun `show and hide drive isVisible`() {
        val state = LogOverlayState()

        state.show()
        assertTrue(state.isVisible.value)

        state.hide()
        assertFalse(state.isVisible.value)
    }

    @Test
    fun `show twice stays visible and hide twice stays hidden`() {
        val state = LogOverlayState()

        state.show()
        state.show()
        assertTrue(state.isVisible.value)

        state.hide()
        state.hide()
        assertFalse(state.isVisible.value)
    }

    @Test
    fun `toggle flips isVisible`() {
        val state = LogOverlayState()

        state.toggle()
        assertTrue(state.isVisible.value)

        state.toggle()
        assertFalse(state.isVisible.value)
    }

    // --- LogSink integration ---

    @Test
    fun `asLogSink records what it is handed`() {
        val state = LogOverlayState()
        val sink: LogSink = state.asLogSink()
        val cause = IllegalArgumentException("bad input")

        sink.log(LogLevel.ERROR, "Auth", "login failed", cause)

        val record: LogRecord = state.records.value.single()
        assertEquals(LogLevel.ERROR, record.level)
        assertEquals("Auth", record.tag)
        assertEquals("login failed", record.message)
        assertTrue(assertNotNull(record.throwableText).contains("bad input"))
    }

    @Test
    fun `asLogSink honors min level`() {
        val state = LogOverlayState(minLevel = LogLevel.ERROR)

        state.asLogSink().log(LogLevel.WARN, "T", "ignored", null)

        assertEquals(emptyList(), state.records.value)
    }

    @Test
    fun `asLogSink honors the buffer bound`() {
        val state = LogOverlayState(maxRecords = 2)
        val sink: LogSink = state.asLogSink()

        repeat(10) { index -> sink.log(LogLevel.INFO, "T", "m$index", null) }

        assertEquals(listOf("m8", "m9"), state.records.value.map(LogRecord::message))
    }

    @Test
    fun `two sinks from the same state feed one buffer`() {
        val state = LogOverlayState()

        state.asLogSink().log(LogLevel.INFO, "T", "first", null)
        state.asLogSink().log(LogLevel.INFO, "T", "second", null)

        assertEquals(listOf("first", "second"), state.records.value.map(LogRecord::message))
    }
}

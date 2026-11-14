package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `asLogSink()` and `record()` are documented as never throwing anything the logger would contain. A
 * throwable whose own `toString()` or `message` fails with an Error the logger contains (Kotlin's `TODO()`
 * raises a `NotImplementedError`, a failed assertion an `AssertionError`) must neither surface from the
 * sink nor cost the overlay the event; any other Error keeps propagating, exactly as through the logger.
 *
 * One documented difference from the logger, deliberately not in the parity table: a sink's
 * `CancellationException` is rethrown by the logger, but a `CancellationException` raised by a throwable's own
 * description is contained by the overlay like any other `Exception`.
 */
class OverlayDescriptionErrorContainmentTest {

    private class UnfinishedMessage : RuntimeException() {
        override val message: String get() = TODO("message not written yet")
    }

    private class FailedAssertionDescription : RuntimeException() {
        override fun toString(): String = throw AssertionError("describe failed an assertion")
        override val message: String get() = throw AssertionError("message failed an assertion")
    }

    private class CancelledMessage : RuntimeException() {
        override val message: String get() = throw CancellationException("message read a cancelled future")
    }

    private class Fatal : Error("vm level failure")

    private class FatalDescription : RuntimeException() {
        override fun toString(): String = throw Fatal()
        override val message: String get() = throw Fatal()
    }

    private fun recordOnce(throwable: Throwable): LogRecord {
        val state = LogOverlayState()
        state.asLogSink().log(LogLevel.ERROR, "T", "boom", throwable)
        return state.records.value.single()
    }

    @Test
    fun `a throwable whose message is a TODO does not make the sink throw and keeps the event`() {
        val record: LogRecord = recordOnce(UnfinishedMessage())
        assertEquals("boom", record.message)
        val text: String = record.throwableText.orEmpty()
        assertTrue(text.contains("UnfinishedMessage"), "the class is not named: $text")
        assertTrue(text.contains("stack trace unavailable"), "no unavailable marker: $text")
    }

    @Test
    fun `a throwable whose description fails an assertion does not make the sink throw and keeps the event`() {
        val record: LogRecord = recordOnce(FailedAssertionDescription())
        assertEquals("boom", record.message)
        val text: String = record.throwableText.orEmpty()
        assertTrue(text.contains("FailedAssertionDescription"), "the class is not named: $text")
        assertTrue(text.contains("stack trace unavailable"), "no unavailable marker: $text")
    }

    @Test
    fun `a throwable whose message throws a CancellationException is contained and keeps the event`() {
        val state = LogOverlayState()
        state.record(LogLevel.ERROR, "T", "boom", CancelledMessage())
        val record: LogRecord = state.records.value.single()
        assertEquals("boom", record.message)
        val text: String = record.throwableText.orEmpty()
        assertTrue(text.contains("CancelledMessage"), "the class is not named: $text")
        assertTrue(text.contains("stack trace unavailable"), "no unavailable marker: $text")
    }

    @Test
    fun `a direct record call contains the same failures as the sink`() {
        val state = LogOverlayState()
        state.record(LogLevel.ERROR, "T", "direct", UnfinishedMessage())
        assertEquals(listOf("direct"), state.records.value.map { it.message })
    }

    @Test
    fun `an Error outside the contained set still propagates from record`() {
        val state = LogOverlayState()
        assertFailsWith<Fatal> { state.record(LogLevel.ERROR, "T", "boom", FatalDescription()) }
    }
}

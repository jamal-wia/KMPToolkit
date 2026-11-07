package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `asLogSink()` is documented as a sink that never throws. An error whose own `toString()` fails
 * (a message built lazily from a field that is null) must neither surface from the sink nor cost the
 * overlay the event: the message is still worth showing.
 */
class OverlaySinkHostileThrowableTest {

    private class Hostile : RuntimeException() {
        override fun toString(): String = throw IllegalStateException("cannot describe myself")
        override val message: String get() = throw IllegalStateException("no message either")
    }

    @Test
    fun `the overlay sink does not throw for an error that cannot describe itself and still keeps the message`() {
        val state = LogOverlayState()
        state.asLogSink().log(LogLevel.ERROR, "T", "payment failed", Hostile())
        assertEquals(listOf("payment failed"), state.records.value.map { it.message })
    }
}

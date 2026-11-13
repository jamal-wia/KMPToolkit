package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overlay's `isContainedDescriptionFailure` mirrors the logger's `isContainedSinkFailure`. The mirror is
 * not shared code (the logger's predicate is internal to a stdlib-only module), so this test is what keeps
 * the two sets identical: each kind of failure is thrown by a sink behind the logger and by a throwable's
 * own `message` into the overlay, and both must either contain it or let it through.
 */
class OverlayContainmentParityTest {

    private class Fatal : Error("vm level failure")

    private class Describing(private val failure: Throwable) : RuntimeException() {
        override fun toString(): String = throw failure
        override val message: String get() = throw failure
    }

    private fun failures(): List<Pair<String, () -> Throwable>> = listOf(
        "IllegalStateException" to { IllegalStateException("x") },
        "AssertionError" to { AssertionError("x") },
        "NotImplementedError" to { NotImplementedError("x") },
        "NoSuchMethodError" to { NoSuchMethodError("x") },
        "StackOverflowError" to { StackOverflowError() },
        "Fatal" to { Fatal() },
    )

    private fun containedByLogger(failure: Throwable): Boolean {
        val sink = LogSink { _, _, _, _ -> throw failure }
        val logger = createLoggerFactory(sinks = listOf(sink)).logger("T")
        return try {
            logger.log(LogLevel.ERROR, null) { "m" }
            true
        } catch (propagated: Throwable) {
            check(propagated === failure) { "the logger threw something else: $propagated" }
            false
        }
    }

    private fun containedByOverlay(failure: Throwable): Boolean {
        val state = LogOverlayState()
        return try {
            state.record(LogLevel.ERROR, "T", "m", Describing(failure))
            true
        } catch (propagated: Throwable) {
            check(propagated === failure) { "the overlay threw something else: $propagated" }
            false
        }
    }

    @Test
    fun `the overlay contains exactly the failures the logger contains`() {
        for ((name: String, create: () -> Throwable) in failures()) {
            val byLogger: Boolean = containedByLogger(create())
            val byOverlay: Boolean = containedByOverlay(create())
            assertEquals(byLogger, byOverlay, "$name: logger contains=$byLogger overlay contains=$byOverlay")
        }
    }

    @Test
    fun `the parity table covers both a contained and an uncontained kind`() {
        val results: List<Boolean> = failures().map { (_, create: () -> Throwable) -> containedByLogger(create()) }
        assertEquals(listOf(true, true, true, true, false, false), results)
    }
}

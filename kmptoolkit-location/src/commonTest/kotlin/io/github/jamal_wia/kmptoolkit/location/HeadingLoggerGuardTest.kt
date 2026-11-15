package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `logSafely` of group loc-heading (breaker round 1, finding m3): the app's [Logger] promises nothing about
 * throwing, and the heading providers log on failure paths and inside platform callbacks, where a throwing
 * logger must lose its own event and nothing else.
 */
class HeadingLoggerGuardTest {

    private class Recording(private val failWith: Throwable? = null) : Logger {
        override val tag: String = "Recording"
        val calls: MutableList<Triple<LogLevel, Throwable?, String>> = mutableListOf()

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            calls += Triple(level, throwable, message())
            failWith?.let { failure: Throwable -> throw failure }
        }
    }

    private class FailingTestError : Error("simulated linkage failure")

    @Test
    fun `an event is passed on with its level throwable and message`() {
        val logger = Recording()
        val cause = IllegalStateException("cause")

        logger.logSafely(LogLevel.WARN, cause) { "something failed" }
        logger.logSafely(LogLevel.DEBUG) { "no throwable" }

        assertEquals(
            listOf(Triple(LogLevel.WARN, cause as Throwable?, "something failed"), Triple(LogLevel.DEBUG, null, "no throwable")),
            logger.calls,
        )
    }

    @Test
    fun `an exception thrown by the logger is absorbed every time`() {
        val logger = Recording(failWith = IllegalStateException("logger failure"))

        repeat(3) { logger.logSafely(LogLevel.WARN, RuntimeException("cause")) { "event" } }

        assertEquals(3, logger.calls.size, "control: the logger was reached each time and threw each time")
    }

    @Test
    fun `an error thrown by the logger still propagates`() {
        val error = FailingTestError()
        val logger = Recording(failWith = error)

        val thrown: FailingTestError = assertFailsWith<FailingTestError> { logger.logSafely(LogLevel.WARN) { "event" } }

        assertSame(error, thrown)
        assertTrue(logger.calls.isNotEmpty(), "control: the logger was reached")
    }
}

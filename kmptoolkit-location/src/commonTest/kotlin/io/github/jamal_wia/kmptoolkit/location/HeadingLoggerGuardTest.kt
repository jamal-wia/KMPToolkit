package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * `guardLogger` of group loc-heading (breaker round 1, finding m3): the app's `Logger` promises nothing about
 * throwing, and the heading providers log on failure paths and inside platform callbacks, where a throwing logger
 * must lose its own event and nothing else. The helper takes a lambda because the logging artifact is not a
 * dependency of the common source set; the providers' tests prove it at every call site with a throwing logger.
 */
class HeadingLoggerGuardTest {

    private class FailingTestError : Error("simulated linkage failure")

    @Test
    fun `an event that does not fail runs exactly once`() {
        var calls = 0

        guardLogger { calls++ }

        assertEquals(1, calls)
    }

    @Test
    fun `an exception thrown while logging is absorbed every time`() {
        var reached = 0

        repeat(3) {
            guardLogger {
                reached++
                throw IllegalStateException("logger failure")
            }
        }

        assertEquals(3, reached, "control: the logging was reached each time and threw each time")
    }

    @Test
    fun `an error thrown while logging still propagates`() {
        val error = FailingTestError()
        var reached = 0

        val thrown: FailingTestError = assertFailsWith<FailingTestError> {
            guardLogger {
                reached++
                throw error
            }
        }

        assertSame(error, thrown)
        assertEquals(1, reached, "control: the logging was reached")
    }
}

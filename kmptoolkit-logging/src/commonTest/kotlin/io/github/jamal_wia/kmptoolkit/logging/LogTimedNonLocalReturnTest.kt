package io.github.jamal_wia.kmptoolkit.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

/**
 * `logTimed` is documented as "Runs [block], then logs how long it took" and as declared `inline` precisely so the
 * block "may `return` non-locally"; only an exception is documented to propagate "without a log event". A block
 * that leaves the enclosing function early (a cache hit, a guard clause) did run to its end as far as the caller
 * is concerned and is a timing the caller asked for.
 */
class LogTimedNonLocalReturnTest {

    private val sink = RecordingSink()
    private val logger: Logger = createLoggerFactory(sinks = listOf(sink)).logger("T")

    private fun loadWithEarlyReturn(cached: Boolean): Int {
        logger.logTimed("load") {
            if (cached) return 1
        }
        return 2
    }

    @Test
    fun `a block that returns non-locally is still timed`() {
        assertEquals(1, loadWithEarlyReturn(cached = true))

        assertEquals(1, sink.events.size, "the early return left no timing line")
        val event: LogEvent = sink.events.single()
        assertEquals(LogLevel.DEBUG, event.level)
        // The duration is nondeterministic but the shape is contract: "<label> [<duration>]".
        val bracketed: String = event.message
            .removeSurrounding("load [", "]")
            .also { assertNotEquals(event.message, it, "unexpected message: ${event.message}") }
        assertNotNull(Duration.parseOrNull(bracketed), "not a parsable duration: ${event.message}")
    }

    @Test
    fun `control - the same block that runs to its end is timed`() {
        assertEquals(2, loadWithEarlyReturn(cached = false))

        assertEquals(1, sink.events.size)
    }
}

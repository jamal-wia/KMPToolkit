package io.github.jamal_wia.kmptoolkit.logging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Pins the level each convenience extension emits at, and [logTimed]'s contract. */
class LoggerExtensionsTest {

    private val sink = RecordingSink()
    private val logger: Logger =
        createLoggerFactory(LogLevel.VERBOSE, listOf(sink)).logger("Ext")

    @Test
    fun `v d i w e emit at their own level in declaration order`() {
        logger.v { "v" }
        logger.d { "d" }
        logger.i { "i" }
        logger.w { "w" }
        logger.e { "e" }

        assertContentEquals(
            listOf(
                LogLevel.VERBOSE,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
            ),
            sink.events.map { it.level },
        )
        assertContentEquals(listOf("v", "d", "i", "w", "e"), sink.events.map { it.message })
    }

    @Test
    fun `v d i carry no throwable while w and e carry the one they are given`() {
        val boom = RuntimeException("boom")

        logger.v { "v" }
        logger.d { "d" }
        logger.i { "i" }
        logger.w(boom) { "w" }
        logger.e(boom) { "e" }

        assertContentEquals(
            listOf(null, null, null, boom, boom),
            sink.events.map { it.throwable },
        )
    }

    @Test
    fun `w and e default to no throwable`() {
        logger.w { "w" }
        logger.e { "e" }

        assertTrue(sink.events.all { it.throwable == null })
    }

    @Test
    fun `logTimed returns the block result and logs the label with a duration`() {
        val result: Int = logger.logTimed("load") { 42 }

        assertEquals(42, result)
        val event: LogEvent = sink.events.single()
        assertEquals(LogLevel.DEBUG, event.level)
        // The duration is nondeterministic but the *shape* is contract: "<label> [<duration>]".
        // Asserting only the prefix and suffix would let "load []" pass.
        val bracketed: String = event.message
            .removeSurrounding("load [", "]")
            .also { assertNotEquals(event.message, it, "unexpected message: ${event.message}") }
        assertNotNull(
            Duration.parseOrNull(bracketed),
            "not a parsable duration: ${event.message}",
        )
    }

    @Test
    fun `logTimed honours an explicit level`() {
        logger.logTimed("load", LogLevel.INFO) { Unit }

        assertEquals(LogLevel.INFO, sink.events.single().level)
    }

    @Test
    fun `logTimed runs the block exactly once`() {
        var runs = 0
        logger.logTimed("load") { runs++ }

        assertEquals(1, runs)
    }

    @Test
    fun `logTimed still returns the result when the event is filtered out`() {
        val filtered: Logger = createLoggerFactory(LogLevel.ERROR, listOf(sink)).logger("Ext")

        val result: String = filtered.logTimed("load") { "value" }

        assertEquals("value", result)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `logTimed propagates an exception from the block without logging`() {
        val boom = IllegalStateException("boom")

        val thrown: IllegalStateException =
            assertFailsWith { logger.logTimed<Unit>("load") { throw boom } }

        assertSame(boom, thrown)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `NoopLogger discards every level and never evaluates a message`() {
        var evaluations = 0

        LogLevel.entries.forEach { level ->
            assertFalse(NoopLogger.isLoggable(level), "level=$level")
            NoopLogger.log(level, null) { evaluations++; "dropped" }
        }
        NoopLogger.v { evaluations++; "v" }
        NoopLogger.d { evaluations++; "d" }
        NoopLogger.i { evaluations++; "i" }
        NoopLogger.w { evaluations++; "w" }
        NoopLogger.e(RuntimeException("boom")) { evaluations++; "e" }

        assertEquals(0, evaluations)
        assertEquals("", NoopLogger.tag)
    }

    @Test
    fun `NoopLogger logTimed still runs the block and returns its result`() {
        var runs = 0

        val result: Int = NoopLogger.logTimed("load") { runs++; 7 }

        assertEquals(7, result)
        assertEquals(1, runs)
    }
}

package io.github.jamal_wia.kmptoolkit.logging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins the contract documented on [createLoggerFactory] and [Logger]. */
class LoggerFactoryTest {

    @Test
    fun `an event at or above minLevel reaches the sink and one below does not`() {
        val sink = RecordingSink()
        val logger: Logger = createLoggerFactory(LogLevel.INFO, listOf(sink)).logger("Auth")

        logger.log(LogLevel.DEBUG, null) { "below" }
        logger.log(LogLevel.INFO, null) { "at" }
        logger.log(LogLevel.ERROR, null) { "above" }

        assertContentEquals(listOf("at", "above"), sink.events.map { it.message })
    }

    @Test
    fun `every level is filtered against the threshold consistently`() {
        LogLevel.entries.forEach { minLevel ->
            val sink = RecordingSink()
            val logger: Logger = createLoggerFactory(minLevel, listOf(sink)).logger("T")

            LogLevel.entries.forEach { level -> logger.log(level, null) { level.name } }

            val expected: List<String> =
                LogLevel.entries.filter { it >= minLevel }.map { it.name }
            assertContentEquals(expected, sink.events.map { it.message }, "minLevel=$minLevel")
        }
    }

    @Test
    fun `isLoggable agrees with what actually reaches the sinks`() {
        LogLevel.entries.forEach { minLevel ->
            val sink = RecordingSink()
            val logger: Logger = createLoggerFactory(minLevel, listOf(sink)).logger("T")

            LogLevel.entries.forEach { level ->
                val loggable: Boolean = logger.isLoggable(level)
                logger.log(level, null) { "x" }
                assertEquals(
                    loggable,
                    sink.events.any { it.level == level },
                    "minLevel=$minLevel level=$level",
                )
            }
        }
    }

    @Test
    fun `a filtered-out event never evaluates its message lambda`() {
        var evaluations = 0
        val logger: Logger =
            createLoggerFactory(LogLevel.WARN, listOf(RecordingSink())).logger("T")

        logger.log(LogLevel.DEBUG, null) { evaluations++; "never built" }

        assertEquals(0, evaluations)
    }

    @Test
    fun `the sink receives level tag materialized message and throwable verbatim`() {
        val sink = RecordingSink()
        val boom = IllegalStateException("boom")
        val logger: Logger = createLoggerFactory(LogLevel.VERBOSE, listOf(sink)).logger("Payments")

        logger.log(LogLevel.WARN, boom) { "card ${1 + 1}" }

        assertEquals(1, sink.events.size)
        val event: LogEvent = sink.events.single()
        assertEquals(LogLevel.WARN, event.level)
        assertEquals("Payments", event.tag)
        assertEquals("card 2", event.message)
        assertSame(boom, event.throwable)
    }

    @Test
    fun `an event with no throwable delivers null rather than a placeholder`() {
        val sink = RecordingSink()
        createLoggerFactory(LogLevel.VERBOSE, listOf(sink)).logger("T").log(LogLevel.INFO, null) {
            "plain"
        }

        assertEquals(null, sink.events.single().throwable)
    }

    @Test
    fun `every sink receives the event in the order the list declared`() {
        val order: MutableList<String> = mutableListOf()
        val first = RecordingSink(name = "first", order = order)
        val second = RecordingSink(name = "second", order = order)
        val logger: Logger =
            createLoggerFactory(LogLevel.VERBOSE, listOf(first, second)).logger("T")

        logger.log(LogLevel.INFO, null) { "fan-out" }

        assertContentEquals(listOf("first", "second"), order)
        assertEquals("fan-out", first.events.single().message)
        assertEquals("fan-out", second.events.single().message)
    }

    @Test
    fun `the message lambda is evaluated once regardless of sink count`() {
        var evaluations = 0
        val logger: Logger = createLoggerFactory(
            LogLevel.VERBOSE,
            listOf(RecordingSink(), RecordingSink(), RecordingSink()),
        ).logger("T")

        logger.log(LogLevel.INFO, null) { evaluations++; "once" }

        assertEquals(1, evaluations)
    }

    @Test
    fun `a logger with no sinks is disabled and never evaluates a message`() {
        var evaluations = 0
        val logger: Logger = createLoggerFactory(LogLevel.VERBOSE, emptyList()).logger("T")

        LogLevel.entries.forEach { level ->
            assertFalse(logger.isLoggable(level), "level=$level")
            logger.log(level, null) { evaluations++; "dropped" }
        }

        assertEquals(0, evaluations)
    }

    @Test
    fun `a throwing sink neither breaks the caller nor starves the sinks after it`() {
        val order: MutableList<String> = mutableListOf()
        val before = RecordingSink(name = "before", order = order)
        val broken = RecordingSink(
            name = "broken",
            failWith = IllegalStateException("sink is down"),
            order = order,
        )
        val after = RecordingSink(name = "after", order = order)
        val logger: Logger =
            createLoggerFactory(LogLevel.VERBOSE, listOf(before, broken, after)).logger("T")

        logger.log(LogLevel.ERROR, null) { "survives" }

        assertContentEquals(listOf("before", "broken", "after"), order)
        assertEquals("survives", after.events.single().message)
    }

    @Test
    fun `a permanently broken sink keeps failing silently across calls`() {
        val broken = RecordingSink(failWith = RuntimeException("always"))
        val healthy = RecordingSink()
        val logger: Logger =
            createLoggerFactory(LogLevel.VERBOSE, listOf(broken, healthy)).logger("T")

        repeat(3) { index -> logger.log(LogLevel.INFO, null) { "call $index" } }

        assertContentEquals(listOf("call 0", "call 1", "call 2"), healthy.events.map { it.message })
    }

    @Test
    fun `an exception from the message lambda propagates to the caller`() {
        // Deliberately asymmetric with a throwing sink: a broken message lambda is a defect in the
        // calling code, and swallowing it would make it unfindable.
        val sink = RecordingSink()
        val logger: Logger = createLoggerFactory(LogLevel.VERBOSE, listOf(sink)).logger("T")

        assertFailsWith<IllegalArgumentException> {
            logger.log(LogLevel.INFO, null) { throw IllegalArgumentException("bad message") }
        }
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `mutating the list passed in afterwards does not change the factory`() {
        val sinks: MutableList<LogSink> = mutableListOf()
        val factory: LoggerFactory = createLoggerFactory(LogLevel.VERBOSE, sinks)
        val added = RecordingSink()
        sinks += added

        val logger: Logger = factory.logger("T")
        logger.log(LogLevel.ERROR, null) { "not delivered" }

        assertTrue(added.events.isEmpty())
        assertFalse(logger.isLoggable(LogLevel.ERROR))
    }

    @Test
    fun `each logger stamps its own tag`() {
        val sink = RecordingSink()
        val factory: LoggerFactory = createLoggerFactory(LogLevel.VERBOSE, listOf(sink))

        factory.logger("Auth").log(LogLevel.INFO, null) { "a" }
        factory.logger("Sync").log(LogLevel.INFO, null) { "b" }

        assertContentEquals(listOf("Auth", "Sync"), sink.events.map { it.tag })
        assertEquals("Auth", factory.logger("Auth").tag)
    }

    @Test
    fun `an empty tag is passed through unchanged rather than substituted`() {
        val sink = RecordingSink()
        createLoggerFactory(LogLevel.VERBOSE, listOf(sink)).logger("").log(LogLevel.INFO, null) {
            "x"
        }

        assertEquals("", sink.events.single().tag)
    }

    @Test
    fun `an empty message is delivered as an empty string`() {
        val sink = RecordingSink()
        createLoggerFactory(LogLevel.VERBOSE, listOf(sink)).logger("T").log(LogLevel.INFO, null) {
            ""
        }

        assertEquals("", sink.events.single().message)
    }
}

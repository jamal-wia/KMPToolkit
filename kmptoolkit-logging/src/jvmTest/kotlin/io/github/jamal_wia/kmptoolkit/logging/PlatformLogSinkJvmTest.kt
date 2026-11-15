package io.github.jamal_wia.kmptoolkit.logging

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop JVM `platformLogSink()` against the contract in its KDoc and in
 * `docs/kmptoolkit-logging/05-platform-notes.md`: what it renders to standard error (level letter, tag,
 * message, the throwable's cause chain), that events from several threads and several sink instances never
 * interleave, and that the logger around it keeps the caller's interrupt flag exactly as on Android.
 *
 * `System.err` is swapped for a stream of the test's own and put back in a `finally`; every test that
 * starts a thread bounds its wait with a hang limit.
 */
class PlatformLogSinkJvmTest {

    private val separator: String = System.lineSeparator()

    @AfterTest
    fun clearInterrupt() {
        Thread.interrupted()
    }

    /** Runs [work] with `System.err` replaced by [stream] and returns what was written to [buffer] meanwhile. */
    private fun capturing(buffer: ByteArrayOutputStream, stream: PrintStream, work: () -> Unit): String {
        val original: PrintStream = System.err
        System.setErr(stream)
        try {
            work()
        } finally {
            System.setErr(original)
        }
        stream.flush()
        return buffer.toString(Charsets.UTF_8)
    }

    private fun captured(work: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        return capturing(buffer, PrintStream(buffer, true, "UTF-8"), work)
    }

    /** Runs [work] on its own thread; fails with its own message when it does not return within the hang bound. */
    private fun returnsInBound(work: () -> Unit) {
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val worker: Thread = thread(isDaemon = true, name = "log-sink-jvm") {
            try {
                work()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.join(HANG_BOUND_MILLIS)
        assertFalse(worker.isAlive, "the work did not return within the hang bound")
        failure.get()?.let { throw it }
    }

    private fun framed(throwable: Throwable, frames: Int = 3): Throwable {
        throwable.stackTrace = Array(frames) { index -> StackTraceElement("fake.Frame", "run$index", "Frame.java", index + 1) }
        return throwable
    }

    // --- rendering ---------------------------------------------------------------------------------

    @Test
    fun `each level is written with its initial, the tag and the message on one line`() {
        val sink: LogSink = platformLogSink()
        val output: String = captured {
            LogLevel.entries.forEach { level -> sink.log(level, "Tag", "message of ${level.name}", null) }
        }
        val expected: String = LogLevel.entries.joinToString(separator = "") { level ->
            "${level.name.first()}/Tag: message of ${level.name}$separator"
        }
        assertEquals(expected, output)
    }

    @Test
    fun `an event without a throwable writes exactly one line and nothing after it`() {
        val output: String = captured { platformLogSink().log(LogLevel.INFO, "T", "plain", null) }
        assertEquals("I/T: plain$separator", output)
    }

    @Test
    fun `a throwable puts its stack trace on its own lines below the message and the event ends in one separator`() {
        val error: Throwable = framed(IllegalStateException("boom"))
        val output: String = captured { platformLogSink().log(LogLevel.ERROR, "T", "the message", error) }
        val expected: String = listOf(
            "E/T: the message",
            "java.lang.IllegalStateException: boom",
            "\tat fake.Frame.run0(Frame.java:1)",
            "\tat fake.Frame.run1(Frame.java:2)",
            "\tat fake.Frame.run2(Frame.java:3)",
        ).joinToString(separator = separator, postfix = separator)
        assertEquals(expected, output)
        assertFalse(output.endsWith(separator + separator), "a blank line follows the trace")
    }

    @Test
    fun `a cause chain is rendered to its end`() {
        val chain: Throwable = framed(
            IllegalStateException("outer", framed(IOException("middle", framed(IllegalArgumentException("root"), 0)), 0)),
            0,
        )
        val output: String = captured { platformLogSink().log(LogLevel.WARN, "T", "m", chain) }
        val expected: String = listOf(
            "W/T: m",
            "java.lang.IllegalStateException: outer",
            "Caused by: java.io.IOException: middle",
            "Caused by: java.lang.IllegalArgumentException: root",
        ).joinToString(separator = separator, postfix = separator)
        assertEquals(expected, output)
    }

    @Test
    fun `an UnknownHostException in the chain is rendered in full, not swallowed as Android's own Log would`() {
        val chain: Throwable = IOException("outer", IOException("middle", UnknownHostException("no.such.host")))
        val output: String = captured { platformLogSink().log(LogLevel.ERROR, "T", "m", chain) }
        assertTrue(output.contains("java.net.UnknownHostException: no.such.host"), output)
        assertTrue(output.contains("Caused by: java.io.IOException: middle"), output)
    }

    @Test
    fun `a multi-line message keeps its own lines and the trace still follows it`() {
        val error: Throwable = framed(RuntimeException("x"), 0)
        val output: String = captured { platformLogSink().log(LogLevel.DEBUG, "T", "first\nsecond", error) }
        assertEquals("D/T: first\nsecond${separator}java.lang.RuntimeException: x$separator", output)
    }

    @Test
    fun `a cyclic cause chain is rendered once and the call returns`() {
        val a = IllegalStateException("a")
        val b = IllegalArgumentException("b", a)
        a.initCause(b)
        var output = ""
        returnsInBound { output = captured { platformLogSink().log(LogLevel.ERROR, "T", "m", a) } }
        assertTrue(output.contains("CIRCULAR REFERENCE"), output)
        assertTrue(output.startsWith("E/T: m$separator"), output)
    }

    @Test
    fun `System err is read at every event so a redirect installed later is followed`() {
        val sink: LogSink = platformLogSink()
        val first: String = captured { sink.log(LogLevel.INFO, "T", "to the first", null) }
        val second: String = captured { sink.log(LogLevel.INFO, "T", "to the second", null) }
        assertEquals("I/T: to the first$separator", first)
        assertEquals("I/T: to the second$separator", second)
    }

    @Test
    fun `a getCause that throws is contained by the logger, writes nothing half-rendered and the next sink still gets the event`() {
        class ThrowingCause : RuntimeException("outer") {
            override val cause: Throwable get() = throw IllegalStateException("getCause failed")
        }
        val second: RecordingSink = RecordingSink("second")
        val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink(), second)).logger("T")
        val output: String = captured { logger.e(ThrowingCause()) { "m" } }
        assertEquals("", output, "the event must not be written half rendered")
        assertEquals("m", second.events.single().message)
    }

    // --- the process-wide lock ---------------------------------------------------------------------

    /** A stream whose `println(String)` is deliberately not atomic: it writes one character at a time and yields between them. */
    private class CharAtATimeStream(private val buffer: ByteArrayOutputStream) : PrintStream(buffer, true, "UTF-8") {
        override fun println(x: String?) {
            for (character: Char in (x ?: "null") + "\n") {
                write(character.code)
                Thread.yield()
            }
        }
    }

    @Test
    fun `concurrent writers on separate sink instances never interleave the lines of an event`() {
        val writers = 8
        val eventsPerWriter = 40
        val buffer = ByteArrayOutputStream()
        val stream: PrintStream = CharAtATimeStream(buffer)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(writers)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val output: String = capturing(buffer, stream) {
            repeat(writers) { writer ->
                thread(isDaemon = true, name = "log-writer-$writer") {
                    try {
                        // One instance per writer: the lock is process-wide, not per instance.
                        val sink: LogSink = platformLogSink()
                        start.await(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS)
                        repeat(eventsPerWriter) { index ->
                            val id = "$writer-$index"
                            sink.log(LogLevel.ERROR, "w$writer", "event $id", framed(IllegalStateException("boom $id")))
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        finished.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(finished.await(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS), "the writers did not finish within the hang bound")
        }
        failure.get()?.let { throw it }

        val lines: List<String> = output.lines().dropLast(1)
        val linesPerEvent = 5
        assertEquals(writers * eventsPerWriter * linesPerEvent, lines.size, "lines lost or split")
        val seen: MutableSet<String> = HashSet()
        lines.chunked(linesPerEvent).forEach { block ->
            val header: Regex = Regex("E/w(\\d+): event (\\d+-\\d+)")
            val match: MatchResult = assertNotNull(header.matchEntire(block[0]), "a block does not start with a header: $block")
            val id: String = match.groupValues[2]
            assertEquals("java.lang.IllegalStateException: boom $id", block[1], "the lines of event $id are interleaved: $block")
            assertEquals("\tat fake.Frame.run0(Frame.java:1)", block[2], block.toString())
            assertEquals("\tat fake.Frame.run1(Frame.java:2)", block[3], block.toString())
            assertEquals("\tat fake.Frame.run2(Frame.java:3)", block[4], block.toString())
            assertTrue(seen.add(id), "event $id was written twice")
        }
        assertEquals(writers * eventsPerWriter, seen.size)
    }

    // --- interrupts and containment, the Android tests' intent on the JVM actuals -------------------

    /** Runs [body] on its own thread and returns whether that thread's interrupt flag was set when [body] ended. */
    private fun interruptFlagAfter(body: () -> Unit): Boolean {
        val flag = AtomicBoolean(false)
        returnsInBound {
            try {
                body()
            } finally {
                flag.set(Thread.currentThread().isInterrupted)
                Thread.interrupted()
            }
        }
        return flag.get()
    }

    @Test
    fun `writing to standard error does not clear the interrupt of an interrupted caller`() {
        var output = ""
        val stillInterrupted: Boolean = interruptFlagAfter {
            val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger("T")
            Thread.currentThread().interrupt()
            output = captured { logger.i { "hello" } }
        }
        assertEquals("I/T: hello$separator", output, "the event of an interrupted thread is lost")
        assertTrue(stillInterrupted, "logging cleared the caller's interrupt flag")
    }

    @Test
    fun `an InterruptedException from a sink is contained, a later sink still gets the event and the flag is back`() {
        val later: RecordingSink = RecordingSink("later")
        val stillInterrupted: Boolean = interruptFlagAfter {
            val logger: Logger = createLoggerFactory(
                LogLevel.DEBUG,
                listOf(LogSink { _, _, _, _ -> throw InterruptedException("woken") }, later),
            ).logger("T")
            logger.i { "m" }
        }
        assertEquals(1, later.events.size, "the sink after the interrupted one lost the event")
        assertTrue(stillInterrupted, "the sink's InterruptedException left the interrupt cleared")
    }

    @Test
    fun `an interrupt cleared by one sink is back even when a later sink raises an Error that propagates`() {
        val thrown: AtomicReference<Throwable?> = AtomicReference(null)
        val stillInterrupted: Boolean = interruptFlagAfter {
            val logger: Logger = createLoggerFactory(
                LogLevel.DEBUG,
                listOf(
                    LogSink { _, _, _, _ -> throw InterruptedException("woken") },
                    LogSink { _, _, _, _ -> throw OutOfMemoryError("sink") },
                ),
            ).logger("T")
            try {
                logger.i { "m" }
            } catch (e: OutOfMemoryError) {
                thrown.set(e)
            }
        }
        assertTrue(thrown.get() is OutOfMemoryError, "the Error reaches the caller")
        assertTrue(stillInterrupted, "the interrupt the first sink's throw cleared is lost")
    }

    @Test
    fun `a LinkageError from a sink is contained like a broken sink and the next sink still gets the event`() {
        val later: RecordingSink = RecordingSink("later")
        val logger: Logger = createLoggerFactory(
            LogLevel.DEBUG,
            listOf(LogSink { _, _, _, _ -> throw NoSuchMethodError("mismatched logging library") }, later),
        ).logger("T")
        logger.i { "m" }
        assertEquals(1, later.events.size)
    }

    private companion object {
        const val HANG_BOUND_MILLIS: Long = 20_000
    }
}

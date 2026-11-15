package io.github.jamal_wia.kmptoolkit.logging

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- one write per event ---------------------------------------------------------------------

    /**
     * A stream modelled on the contract the sink relies on instead of on a lock of its own: `print(String)` is one
     * write under the stream's own lock (as in `PrintStream`), and it is deliberately slow inside, writing the text
     * one byte at a time. Every other way of writing text (`println`, `print(Any)`, `print(Char)`) is counted as a
     * stray call, because it would split an event into several writes, each with its own lock, and two writers could
     * then interleave between them. So the test proves that the sink hands every event over as exactly one
     * `print(String)` carrying the whole text and its line terminator, which is what keeps events whole with no lock
     * of the sink's own. Nothing waits or yields here: long texts and many writers make an overlap near certain if
     * an event were written in pieces.
     */
    private class ByteAtATimeStream(buffer: ByteArrayOutputStream) : PrintStream(buffer, true, "UTF-8") {
        private val inside: AtomicInteger = AtomicInteger(0)
        val maxInside: AtomicInteger = AtomicInteger(0)
        val printCalls: AtomicInteger = AtomicInteger(0)
        val strayCalls: AtomicInteger = AtomicInteger(0)

        override fun print(s: String?) {
            printCalls.incrementAndGet()
            synchronized(this) {
                val now: Int = inside.incrementAndGet()
                maxInside.accumulateAndGet(now) { current: Int, candidate: Int -> maxOf(current, candidate) }
                try {
                    for (character: Char in (s ?: "null")) write(character.code)
                } finally {
                    inside.decrementAndGet()
                }
            }
        }

        override fun println(x: String?) {
            strayCalls.incrementAndGet()
        }

        override fun println() {
            strayCalls.incrementAndGet()
        }

        override fun println(x: Any?) {
            strayCalls.incrementAndGet()
        }

        override fun print(obj: Any?) {
            strayCalls.incrementAndGet()
        }

        override fun print(c: Char) {
            strayCalls.incrementAndGet()
        }
    }

    /** The lines the sink must write for one event, derived from the contract and not from the implementation. */
    private fun expectedLines(level: LogLevel, writer: Int, id: String, frames: Int?): List<String> {
        val header = "${level.name.first()}/w$writer: event $id"
        if (frames == null) return listOf(header)
        return listOf(header, "java.lang.IllegalStateException: boom $id") +
            List(frames) { index -> "\tat fake.Frame.run$index(Frame.java:${index + 1})" }
    }

    @Test
    fun `concurrent writers on separate sink instances never interleave the lines of an event`() {
        val seed = 20_261_115
        val writers = 8
        val eventsPerWriter = 40
        val buffer = ByteArrayOutputStream()
        val stream = ByteAtATimeStream(buffer)
        val expected: ConcurrentHashMap<String, List<String>> = ConcurrentHashMap()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(writers)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val output: String = capturing(buffer, stream) {
            repeat(writers) { writer ->
                thread(isDaemon = true, name = "log-writer-$writer") {
                    try {
                        // One instance per writer: events stay whole across instances, the sink holds no state.
                        val sink: LogSink = platformLogSink()
                        val random = Random(seed * 1000 + writer)
                        start.await(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS)
                        repeat(eventsPerWriter) { index ->
                            val id = "$writer-$index"
                            val level: LogLevel = LogLevel.entries[random.nextInt(LogLevel.entries.size)]
                            val frames: Int? = if (random.nextBoolean()) random.nextInt(0, 40) else null
                            expected[id] = expectedLines(level, writer, id, frames)
                            val error: Throwable? = frames?.let { count -> framed(IllegalStateException("boom $id"), count) }
                            sink.log(level, "w$writer", "event $id", error)
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        finished.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(
                finished.await(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS),
                "seed=$seed: the writers did not finish within the hang bound",
            )
        }
        failure.get()?.let { throw it }

        assertEquals(0, stream.strayCalls.get(), "seed=$seed: the sink wrote an event in pieces (println, print(Any) or print(Char))")
        assertEquals(writers * eventsPerWriter, stream.printCalls.get(), "seed=$seed: not exactly one print(String) per event")
        assertEquals(1, stream.maxInside.get(), "seed=$seed: two writers were inside the stream's write at once")
        val header = Regex("[VDIWE]/w(\\d+): event (\\d+-\\d+)")
        val blocks: MutableList<MutableList<String>> = mutableListOf()
        output.lines().dropLast(1).forEach { line ->
            if (header.matches(line)) {
                blocks += mutableListOf(line)
            } else {
                val open: MutableList<String> =
                    blocks.lastOrNull() ?: throw AssertionError("seed=$seed: a line belongs to no event: $line")
                open += line
            }
        }
        assertEquals(writers * eventsPerWriter, blocks.size, "seed=$seed: events lost or merged")
        val lastIndex: MutableMap<Int, Int> = HashMap()
        blocks.forEach { block ->
            val match: MatchResult = header.matchEntire(block[0]) ?: throw AssertionError("seed=$seed: $block")
            val writer: Int = match.groupValues[1].toInt()
            val id: String = match.groupValues[2]
            assertEquals(expected[id], block, "seed=$seed: the lines of event $id are interleaved or altered")
            val index: Int = id.substringAfter('-').toInt()
            assertTrue(index > (lastIndex[writer] ?: -1), "seed=$seed: writer $writer's events are out of order at $id")
            lastIndex[writer] = index
        }
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

package io.github.jamal_wia.kmptoolkit.logging

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Breaker round 2 of group logging-permission (LOG-1, the desktop JVM `platformLogSink()`), attacking the round 1
 * fix: the sink now holds no lock of its own and writes each event with one `System.err.print(text + separator)`.
 * Derived from the sink's KDoc and `docs/kmptoolkit-logging/05-platform-notes.md` ("Desktop JVM"): `System.err` is
 * read at every event, so an app that redirects it with `System.setErr` is followed, and loggers on several threads
 * never glue lines together or separate a trace from its event; a sink that fails does not break the logger
 * (LoggerFactory KDoc).
 *
 * Attacks: a redirected standard error whose `print(String)` writes line by line (a line-buffering or per-thread
 * capturing stream, the shape Gradle's test worker installs); a huge multi-line event pushed through a stock stream
 * in many small chunks by several threads; a charset-encoding stream (UTF-16) with unpaired surrogates at the event
 * edges; `System.setErr(null)`; a stream whose device fails with an IOException (PrintStream's checkError
 * semantics), then recovers.
 *
 * Every wait has a hang bound; nothing sleeps; every random choice comes from a fixed seed named in the message.
 */
class PlatformLogSinkJvmBreakerR2Test {

    @AfterTest
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun <T> withErr(stream: PrintStream?, work: () -> T): T {
        val original: PrintStream = System.err
        System.setErr(stream)
        try {
            return work()
        } finally {
            System.setErr(original)
        }
    }

    private fun logger(tag: String): Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger(tag)

    private val sep: String = System.lineSeparator()

    // --- a redirected standard error that writes line by line --------------------------------------

    /**
     * An app's standard error whose `print(String)` hands the text on one line at a time, each line one locked write
     * of the base class: what a line-buffering, per-thread capturing or line-forwarding PrintStream does (Gradle's
     * test worker installs one). [afterFirstLine] runs on the writing thread between the first line of a text and
     * the rest, with no lock of the stream held, so the test can let another thread in exactly there.
     */
    private class LineByLineStream(
        private val target: ByteArrayOutputStream,
        private val afterFirstLine: (Thread) -> Unit,
        private val onEnter: (Thread) -> Unit,
        private val onExit: (Thread) -> Unit,
    ) : PrintStream(target, true, "UTF-8") {
        override fun print(s: String?) {
            onEnter(Thread.currentThread())
            val text: String = s ?: "null"
            var start = 0
            var first = true
            while (start < text.length) {
                val end: Int = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
                super.print(text.substring(start, end))
                if (first) {
                    first = false
                    afterFirstLine(Thread.currentThread())
                }
                start = end
            }
            onExit(Thread.currentThread())
        }
    }

    /**
     * Thread A logs a two-line event; the app's standard error writes it line by line. Between A's two lines, thread
     * B logs its own two-line event and is given every chance to finish it (A waits until B is inside the stream and
     * done, or until B is parked or blocked before the stream, which is what any serialisation of the sink's events
     * looks like). The contract (05, "Desktop JVM": `System.setErr` is followed, and loggers on several threads never
     * glue lines together or separate a trace from its event) says the two events come out each in one piece. With
     * one `print(String)` and no lock of the sink's own, B's lines land between A's. Mutation by reading: a sink that
     * serialises its events on the stream's own monitor when the stream is not the JDK's own class turns this green.
     */
    @Test
    fun `a redirected standard error that writes line by line still gets every event of several threads in one piece`() {
        val target = ByteArrayOutputStream()
        val aMidEvent = CountDownLatch(1)
        val bLogging = CountDownLatch(1)
        val bEntered = CountDownLatch(1)
        val bDone = CountDownLatch(1)
        val bThread = AtomicReference<Thread>()
        val aThread = AtomicReference<Thread>()
        val stream = LineByLineStream(
            target,
            afterFirstLine = { current: Thread ->
                if (current === aThread.get() && aMidEvent.count > 0L) {
                    aMidEvent.countDown()
                    letBIn(bLogging, bEntered, bDone, bThread)
                }
            },
            onEnter = { current: Thread -> if (current === bThread.get()) bEntered.countDown() },
            onExit = { current: Thread -> if (current === bThread.get()) bDone.countDown() },
        )
        val log: Logger = logger("T")
        withErr(stream) {
            val a: Thread = thread(start = false, isDaemon = true, name = "line-writer-a") { log.i { "a1\na2" } }
            val b: Thread = thread(start = false, isDaemon = true, name = "line-writer-b") {
                if (aMidEvent.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
                    bLogging.countDown()
                    log.i { "b1\nb2" }
                }
            }
            aThread.set(a)
            bThread.set(b)
            b.start()
            a.start()
            a.join(BOUND_SECONDS * 1000)
            b.join(BOUND_SECONDS * 1000)
            assertFalse(a.isAlive || b.isAlive, "a writer did not finish within the hang bound")
        }
        val lines: List<String> = target.toString(Charsets.UTF_8).split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
        val aFirst: List<String> = listOf("I/T: a1", "a2", "I/T: b1", "b2")
        val bFirst: List<String> = listOf("I/T: b1", "b2", "I/T: a1", "a2")
        assertTrue(lines == aFirst || lines == bFirst, "the two events were interleaved line by line: $lines")
    }

    /**
     * Runs on thread A between its first and second line: waits until B announced it is logging, then until B is
     * inside the stream and has finished, or is parked or blocked before reaching the stream. Hang bounds only.
     */
    private fun letBIn(
        bLogging: CountDownLatch,
        bEntered: CountDownLatch,
        bDone: CountDownLatch,
        bThread: AtomicReference<Thread>,
    ) {
        if (!bLogging.await(BOUND_SECONDS, TimeUnit.SECONDS)) return
        val b: Thread = bThread.get()
        // At most BOUND_SECONDS in steps of STEP_MILLIS: ends on B's own progress or B's own state.
        repeat((BOUND_SECONDS * 1000 / STEP_MILLIS).toInt()) {
            if (bEntered.await(STEP_MILLIS, TimeUnit.MILLISECONDS)) {
                bDone.await(BOUND_SECONDS, TimeUnit.SECONDS)
                return
            }
            val state: Thread.State = b.state
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return
        }
    }

    /**
     * The round 1 deadlock again, with the app's own PrintStream subclass as standard error (a tee or a capture that
     * overrides only the byte write). For such a stream the JDK's `printStackTrace` holds the stream's monitor, not
     * an internal lock, while it calls the throwable's `getMessage`; this `getMessage` logs while thread A logs a
     * plain event. Guards the fix of the line-by-line finding above: serialising on that same monitor is safe, any
     * other lock taken before the stream's turns this red (run in a child JVM, a real deadlock cannot be undone).
     */
    @Test
    fun `printStackTrace on an app's PrintStream subclass whose getMessage logs does not deadlock against a concurrent event`() {
        val result: String = ChildJvm.run(PlatformLogSinkSubclassAbbaChild::class.java.name)
        assertEquals("RESULT ok", result.lineSequence().first { it.startsWith("RESULT") }, result)
    }

    // --- a huge multi-line event through a stock stream, many small chunks ---------------------------

    /** A device that takes at most [chunk] bytes per call, so one event reaches it in many writes. */
    private class SmallChunkDevice(private val chunk: Int) : OutputStream() {
        val written: ByteArrayOutputStream = ByteArrayOutputStream()

        override fun write(b: Int) {
            synchronized(written) { written.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            while (at < off + len) {
                val n: Int = minOf(chunk, off + len - at)
                synchronized(written) { written.write(b, at, n) }
                at += n
            }
        }
    }

    @Test
    fun `huge multi-line events from several threads reach a stock stream each in one piece`() {
        val seed = 20_261_122
        val writers = 4
        val eventsPerWriter = 6
        val device = SmallChunkDevice(chunk = 7)
        // A buffered stock stream, as System.err is: the event passes the 8 KiB buffer many times.
        val stream = PrintStream(java.io.BufferedOutputStream(device, 8192), false, "UTF-8")
        val log: Logger = logger("H")
        val start = CountDownLatch(1)
        val expected: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        withErr(stream) {
            val threads: List<Thread> = (0 until writers).map { w: Int ->
                thread(isDaemon = true, name = "huge-$w") {
                    val random = Random(seed * 1000 + w)
                    start.await(BOUND_SECONDS, TimeUnit.SECONDS)
                    repeat(eventsPerWriter) { e: Int ->
                        val id = "w${w}e$e"
                        val lineCount: Int = 200 + random.nextInt(400)
                        val message: String = (0 until lineCount).joinToString("\n") { l: Int -> "$id-$l-" + "x".repeat(random.nextInt(120)) }
                        expected[id] = "I/H: $message"
                        log.i { message }
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join(BOUND_SECONDS * 1000) }
            assertFalse(threads.any { it.isAlive }, "seed=$seed: a writer did not finish within the hang bound")
            stream.flush()
        }
        val lines: List<String> = device.written.toString(Charsets.UTF_8).split(sep).filter { it.isNotEmpty() }
        // Walk the output: every event starts with "I/H: <id>-0-" and its lines follow without anything in between.
        var i = 0
        var events = 0
        while (i < lines.size) {
            val head: String = lines[i]
            assertTrue(head.startsWith("I/H: w"), "seed=$seed: line $i does not start an event: ${head.take(40)}")
            val id: String = head.removePrefix("I/H: ").substringBefore('-')
            val want: List<String> = expected.getValue(id).split("\n")
            assertEquals(want, lines.subList(i, minOf(lines.size, i + want.size)), "seed=$seed: event $id is not in one piece")
            i += want.size
            events++
        }
        assertEquals(writers * eventsPerWriter, events, "seed=$seed: events lost or duplicated")
    }

    // --- a charset-encoding stream and unpaired surrogates at the event edges ----------------------

    @Test
    fun `a UTF-16 standard error keeps every event apart even when a message ends or starts with an unpaired surrogate`() {
        val target = ByteArrayOutputStream()
        val stream = PrintStream(target, true, "UTF-16")
        val log: Logger = logger("U")
        withErr(stream) {
            log.i { "end\uD83D" } // a high surrogate with nothing after it in the message
            log.i { "\uDE00start" } // a low surrogate with nothing before it
            log.i { "pair😀" }
            log.i { "" }
        }
        val lines: List<String> = target.toString(Charsets.UTF_16).split(sep)
        assertEquals(5, lines.size, "an event swallowed a separator or split: $lines")
        assertTrue(lines[0].startsWith("I/U: end") && lines[0].length == "I/U: end".length + 1, "first: ${lines[0]}")
        assertTrue(lines[1].startsWith("I/U: ") && lines[1].endsWith("start") && lines[1].length == "I/U: start".length + 1, "second: ${lines[1]}")
        assertEquals("I/U: pair😀", lines[2])
        assertEquals("I/U: ", lines[3])
        assertEquals("", lines[4])
    }

    // --- standard error set to null ----------------------------------------------------------------

    @Test
    fun `standard error set to null breaks no caller and the next event after a redirect is written`() {
        val log: Logger = logger("N")
        val target = ByteArrayOutputStream()
        val escaped = AtomicReference<Throwable?>(null)
        withErr(null) {
            try {
                log.e(IllegalStateException("boom")) { "while null" }
            } catch (t: Throwable) {
                escaped.set(t)
            }
        }
        assertNull(escaped.get(), "a null standard error broke the caller")
        withErr(PrintStream(target, true, "UTF-8")) { log.i { "after" } }
        assertEquals("I/N: after$sep", target.toString(Charsets.UTF_8))
    }

    // --- a device failing with IOException, then recovering ----------------------------------------

    private class FlakyDevice(private val failing: AtomicBoolean) : OutputStream() {
        val written: ByteArrayOutputStream = ByteArrayOutputStream()

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failing.get()) throw IOException("broken pipe")
            written.write(b, off, len)
        }
    }

    @Test
    fun `a device failing with IOException breaks no caller keeps the interrupt and the event after recovery is whole`() {
        val failing = AtomicBoolean(true)
        val device = FlakyDevice(failing)
        val stream = PrintStream(device, true, "UTF-8")
        val log: Logger = logger("F")
        val interruptedAfter = AtomicBoolean(false)
        withErr(stream) {
            Thread.currentThread().interrupt()
            log.w(RuntimeException("cause")) { "lost" }
            interruptedAfter.set(Thread.interrupted())
            // Control: the failure happened and PrintStream recorded it instead of throwing.
            assertTrue(stream.checkError(), "control: the failing device was never written to")
            failing.set(false)
            log.i { "after" }
        }
        assertTrue(interruptedAfter.get(), "the caller's interrupt was cleared by a failed write")
        val text: String = device.written.toString(Charsets.UTF_8)
        // endsWith: bytes the stream kept from the failed write are the stream's business; the event must be whole.
        assertTrue(text.endsWith("I/F: after$sep"), "the event after recovery is missing or torn: $text")
    }

    private companion object {
        const val BOUND_SECONDS: Long = 20L
        const val STEP_MILLIS: Long = 20L
    }
}

/**
 * The child JVM of the subclass ABBA test (breaker round 2, LOG-1): standard error is an app's PrintStream subclass
 * that overrides only the byte write, so the JDK locks its monitor in `printStackTrace`. Prints `RESULT ok` when both
 * threads finish, or `RESULT deadlock ...` with the JVM's own deadlock detection and both stacks, then halts.
 */
internal object PlatformLogSinkSubclassAbbaChild {

    private const val BOUND_SECONDS: Long = 20L
    private const val STEP_MILLIS: Long = 20L

    /** A tee-like capture: an app's subclass, not the JDK's own class. */
    private class CapturingStream(private val captured: ByteArrayOutputStream) : PrintStream(captured, true, "UTF-8") {
        override fun write(buf: ByteArray, off: Int, len: Int) {
            synchronized(captured) { captured.write(buf, off, len) }
        }
    }

    /** Returns once [a] is parked or blocked after it announced it is logging, or ended; a hang bound only. */
    private fun awaitParked(a: Thread, aLogging: CountDownLatch) {
        if (!aLogging.await(BOUND_SECONDS, TimeUnit.SECONDS)) return
        val step = CountDownLatch(1)
        repeat((BOUND_SECONDS * 1000 / STEP_MILLIS).toInt()) {
            if (!a.isAlive) return
            val state: Thread.State = a.state
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) return
            step.await(STEP_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out: PrintStream = System.out
        val captured = ByteArrayOutputStream()
        val err = CapturingStream(captured)
        System.setErr(err)
        val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger("sub")
        val aLogging = CountDownLatch(1)
        val bHoldsErr = CountDownLatch(1)
        val choreographed = AtomicBoolean(false)
        val threadA: AtomicReference<Thread> = AtomicReference(null)

        val hostile: RuntimeException = object : RuntimeException() {
            override val message: String
                get() {
                    if (choreographed.compareAndSet(false, true)) {
                        bHoldsErr.countDown()
                        awaitParked(threadA.get(), aLogging)
                        logger.i { "logged from getMessage while printStackTrace holds the subclass" }
                    }
                    return "hostile"
                }
        }
        val a = Thread({
            bHoldsErr.await(BOUND_SECONDS, TimeUnit.SECONDS)
            aLogging.countDown()
            logger.i { "plain event from thread A" }
        }, "sub-abba-A").apply { isDaemon = true }
        threadA.set(a)
        val b = Thread({ hostile.printStackTrace() }, "sub-abba-B").apply { isDaemon = true }
        a.start()
        b.start()
        a.join(BOUND_SECONDS * 1000 * 2)
        b.join(BOUND_SECONDS * 1000 * 2)

        if (a.isAlive || b.isAlive) {
            val deadlocked: LongArray? = java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads()
            out.println("RESULT deadlock a=${a.state} b=${b.state} jvmDetectedDeadlockedThreads=${deadlocked?.size ?: 0}")
            listOf(a, b).forEach { thread ->
                out.println("--- ${thread.name} ${thread.state}")
                thread.stackTrace.take(14).forEach { frame -> out.println("    at $frame") }
            }
            out.flush()
            Runtime.getRuntime().halt(3)
        }
        err.flush()
        val text: String = synchronized(captured) { captured.toString(Charsets.UTF_8) }
        val complete: Boolean = text.contains("I/sub: plain event from thread A") &&
            text.contains("I/sub: logged from getMessage while printStackTrace holds the subclass") &&
            text.contains("hostile")
        out.println(if (complete) "RESULT ok" else "RESULT incomplete\n$text")
        out.flush()
        kotlin.system.exitProcess(0)
    }
}

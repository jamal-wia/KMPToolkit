package io.github.jamal_wia.kmptoolkit.logging

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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
 * Breaker round 3 of group logging-permission (LOG-1, the desktop JVM `platformLogSink()`), attacking the round 2
 * fix: the sink calls `err.print(event)` directly when `System.err` is exactly `java.io.PrintStream`, and otherwise
 * runs the call inside `synchronized(err)`. Derived from the sink's KDoc and
 * `docs/kmptoolkit-logging/05-platform-notes.md` ("Desktop JVM"): `System.err` is read at every event and an app's
 * `System.setErr` is followed; the lines of events from several threads never interleave, also on an app's
 * `PrintStream` subclass; a throwable whose getter logs does not deadlock against a concurrent event; the sink takes
 * no lock of its own; a sink that fails does not break the logger (LoggerFactory KDoc).
 *
 * Attacks: the round 1 deadlock (printStackTrace whose getMessage logs, against a concurrent event) on three more
 * subclass shapes, in child JVMs: a subclass that overrides nothing, one whose `print(String)` writes line by line,
 * and one whose `print(String)` takes a lock of its own before the base class's; `System.setErr` swapped in the middle
 * of an event on a subclass (the instance locked is the instance written, and the lock is per stream, so a writer
 * that already follows the new stream is not held up); a seeded `setErr` storm over subclass and stock streams; a
 * subclass whose `print` throws (its monitor must be free afterwards); huge multi-line events through a line-by-line
 * subclass from several threads.
 *
 * Every wait has a hang bound; nothing sleeps; every random choice comes from a fixed seed named in the message.
 */
class PlatformLogSinkJvmBreakerR3Test {

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

    // --- the round 1 deadlock on three more subclass shapes (child JVMs) ----------------------------

    private fun assertChildOk(mode: String) {
        val result: String = ChildJvmWithArgs.run(PlatformLogSinkR3AbbaChild::class.java.name, mode)
        assertEquals("RESULT ok", result.lineSequence().first { it.startsWith("RESULT") }, "mode=$mode\n$result")
    }

    /**
     * Standard error is an app's subclass that overrides nothing, so its `print(String)` is the JDK's own and already
     * one locked write: the sink locks its monitor needlessly. That must stay harmless: `printStackTrace` holds the
     * same monitor while its getMessage logs, so there is one lock. Mutation by reading: a lock of the sink's own taken
     * before `synchronized(err)` turns this red (deadlock).
     */
    @Test
    fun `printStackTrace whose getMessage logs does not deadlock on a subclass that overrides nothing`() {
        assertChildOk("plain")
    }

    /**
     * Standard error is the line-by-line subclass of the round 2 finding (the shape the fix exists for), thread A logs
     * a three-line event while thread B's `printStackTrace` holds the stream and its getMessage logs. No deadlock, and
     * A's three lines come out together. Mutation by reading: a sink lock of its own taken first turns this red.
     */
    @Test
    fun `printStackTrace whose getMessage logs does not deadlock on a line by line subclass and the event stays whole`() {
        assertChildOk("lines")
    }

    /**
     * Standard error is a subclass whose `print(String)` takes a lock of its own and then calls the base class (which
     * takes the stream's monitor inside). The JDK's `println` and `printStackTrace` on such a stream take the monitor
     * first and the subclass's lock second; so does the sink. Same order everywhere, so the getter that logs under
     * `printStackTrace` does not deadlock against a concurrent event. Mutations (run locally): a sink that takes no lock
     * for a subclass (the round 2 code: A takes the subclass's lock, then waits for the monitor B holds) and a sink that
     * takes an own lock first both turn this red.
     */
    @Test
    fun `printStackTrace whose getMessage logs does not deadlock on a subclass whose print takes a lock of its own`() {
        assertChildOk("ownlock")
    }

    // --- System.err swapped in the middle of an event on a subclass ---------------------------------

    /** Splits `print(String)` into one base-class write per line; [afterFirstLine] runs between the first and the rest. */
    private class LineByLineStream(
        target: ByteArrayOutputStream,
        private val afterFirstLine: (Thread) -> Unit = {},
    ) : PrintStream(target, true, "UTF-8") {
        override fun print(s: String?) {
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
        }
    }

    /**
     * Thread A logs a two-line event on line-by-line subclass S1. Between A's first and second line the app swaps
     * `System.err` to another line-by-line subclass S2, and thread B logs there. B must finish while A is still inside
     * its event (the sink locks only the stream it writes, S2 for B), A's event must end whole in S1 (the instance
     * locked and written is the one read at the start of the event), and B's whole in S2. Mutation (run locally): a
     * lock shared by every stream (a global or class-wide monitor) turns this red. A sink that re-reads `System.err`
     * between locking and writing is not caught: no hook exists between those two reads; the local read is checked by
     * reading.
     */
    @Test
    fun `System setErr swapped in the middle of an event on a subclass holds up no writer of the new stream and tears no event`() {
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        val s2 = LineByLineStream(second)
        val aThread = AtomicReference<Thread>()
        val bFinishedInside = AtomicBoolean(false)
        val bFailure = AtomicReference<Throwable?>(null)
        val log: Logger = logger("S")
        val s1 = LineByLineStream(first) { current: Thread ->
            if (current === aThread.get() && !bFinishedInside.get()) {
                System.setErr(s2)
                val b: Thread = thread(isDaemon = true, name = "swap-b") {
                    try {
                        log.i { "b1\nb2" }
                    } catch (t: Throwable) {
                        bFailure.set(t)
                    }
                }
                b.join(BOUND_SECONDS * 1000)
                bFinishedInside.set(!b.isAlive)
            }
        }
        withErr(s1) {
            val a: Thread = thread(start = false, isDaemon = true, name = "swap-a") { log.i { "a1\na2" } }
            aThread.set(a)
            a.start()
            a.join(BOUND_SECONDS * 2 * 1000)
            assertFalse(a.isAlive, "thread A did not finish within the hang bound")
        }
        assertNull(bFailure.get(), "thread B failed")
        assertTrue(bFinishedInside.get(), "thread B, writing to the new stream, was held up by A's event on the old one")
        assertEquals("I/S: a1\na2$sep", first.toString(Charsets.UTF_8), "A's event is not whole in the stream it started on")
        assertEquals("I/S: b1\nb2$sep", second.toString(Charsets.UTF_8), "B's event is not whole in the new stream")
    }

    // --- a seeded setErr storm over subclass and stock streams --------------------------------------

    @Test
    fun `a System setErr storm over line by line subclasses and a stock stream puts every event whole into exactly one stream`() {
        val seed = 20_261_123
        val writers = 6
        val eventsPerWriter = 60
        val swaps = 300
        val buffers: List<ByteArrayOutputStream> = List(3) { ByteArrayOutputStream() }
        val streams: List<PrintStream> = listOf(
            LineByLineStream(buffers[0]),
            LineByLineStream(buffers[1]),
            PrintStream(buffers[2], true, "UTF-8"),
        )
        val start = CountDownLatch(1)
        val finished = CountDownLatch(writers + 1)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        withErr(streams[0]) {
            repeat(writers) { writer: Int ->
                thread(isDaemon = true, name = "r3-storm-writer-$writer") {
                    try {
                        val random = Random(seed * 100 + writer)
                        val sink: LogSink = platformLogSink()
                        start.await(BOUND_SECONDS, TimeUnit.SECONDS)
                        repeat(eventsPerWriter) { index: Int ->
                            val lines: Int = random.nextInt(1, 5)
                            val message: String = (0 until lines).joinToString("\n") { l: Int -> "m$writer-$index-$l" }
                            val frames: Int = random.nextInt(0, 8)
                            val error: Throwable? = if (random.nextBoolean()) {
                                null
                            } else {
                                IllegalStateException("t$writer-$index").also { error: IllegalStateException ->
                                    error.stackTrace =
                                        Array(frames) { frame: Int -> StackTraceElement("x.F", "f$frame", "F.java", frame + 1) }
                                }
                            }
                            sink.log(LogLevel.WARN, "w$writer", "e$writer-$index|$message", error)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        finished.countDown()
                    }
                }
            }
            thread(isDaemon = true, name = "r3-err-swapper") {
                try {
                    val random = Random(seed)
                    start.await(BOUND_SECONDS, TimeUnit.SECONDS)
                    repeat(swaps) { System.setErr(streams[random.nextInt(streams.size)]) }
                } finally {
                    finished.countDown()
                }
            }
            start.countDown()
            assertTrue(finished.await(BOUND_SECONDS, TimeUnit.SECONDS), "seed=$seed: the storm did not end within the hang bound")
        }
        failure.get()?.let { throw AssertionError("seed=$seed: a writer failed", it) }

        // Every event is "W/w<w>: e<w>-<i>|m<w>-<i>-0", then "m<w>-<i>-<l>" lines, then an optional trace.
        val header = Regex("W/w(\\d+): e(\\d+-\\d+)\\|m\\2-0")
        val seen: MutableMap<String, Int> = HashMap()
        buffers.forEachIndexed { which: Int, buffer: ByteArrayOutputStream ->
            var open: String? = null
            buffer.toString(Charsets.UTF_8).split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }.forEach { line: String ->
                val match: MatchResult? = header.matchEntire(line)
                if (match != null) {
                    val id: String = match.groupValues[2]
                    open = id
                    seen.merge(id, 1, Int::plus)
                } else {
                    val id: String = open ?: throw AssertionError("seed=$seed: stream $which has a line of no event: $line")
                    assertTrue(
                        Regex("m${Regex.escape(id)}-\\d+").matches(line) ||
                            line == "java.lang.IllegalStateException: t$id" ||
                            line.startsWith("\tat x.F.f"),
                        "seed=$seed: stream $which carries a line of another event inside event $id: $line",
                    )
                }
            }
        }
        assertEquals(writers * eventsPerWriter, seen.size, "seed=$seed: events lost")
        assertTrue(seen.values.all { it == 1 }, "seed=$seed: an event was written twice: ${seen.filterValues { it != 1 }}")
    }

    // --- a subclass whose print throws --------------------------------------------------------------

    /**
     * An app's subclass whose `print(String)` throws an unchecked exception while armed. The logger contains it, the
     * caller does not hold the stream's monitor afterwards, and another thread's next event is written within the hang
     * bound. Mutation by reading: a lock taken by hand (a `ReentrantLock` without `finally`) or a monitor kept by the
     * failure turns this red.
     */
    @Test
    fun `a subclass whose print throws is contained and its monitor is free for the next event of another thread`() {
        val target = ByteArrayOutputStream()
        val armed = AtomicBoolean(true)
        val stream: PrintStream = object : PrintStream(target, true, "UTF-8") {
            override fun print(s: String?) {
                if (armed.get()) throw IllegalStateException("the capture is closed")
                super.print(s)
            }
        }
        val log: Logger = logger("X")
        val escaped = AtomicReference<Throwable?>(null)
        val heldAfter = AtomicBoolean(true)
        withErr(stream) {
            try {
                log.e(RuntimeException("cause")) { "lost" }
            } catch (t: Throwable) {
                escaped.set(t)
            }
            heldAfter.set(Thread.holdsLock(stream))
            armed.set(false)
            val done = CountDownLatch(1)
            thread(isDaemon = true, name = "after-throwing-print") {
                log.i { "next" }
                done.countDown()
            }
            assertTrue(done.await(BOUND_SECONDS, TimeUnit.SECONDS), "the stream's monitor stayed held after the failed print")
        }
        assertNull(escaped.get(), "the failing print broke the caller")
        assertFalse(heldAfter.get(), "the caller still holds the stream's monitor after the failed print")
        assertEquals("I/X: next$sep", target.toString(Charsets.UTF_8))
    }

    // --- huge multi-line events through a line-by-line subclass -------------------------------------

    @Test
    fun `huge multi-line events from several threads reach a line by line subclass each in one piece`() {
        val seed = 20_261_124
        val writers = 4
        val eventsPerWriter = 5
        val target = ByteArrayOutputStream()
        val stream = LineByLineStream(target)
        val log: Logger = logger("H")
        val start = CountDownLatch(1)
        val expected: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        withErr(stream) {
            val threads: List<Thread> = (0 until writers).map { w: Int ->
                thread(isDaemon = true, name = "r3-huge-$w") {
                    val random = Random(seed * 1000 + w)
                    start.await(BOUND_SECONDS, TimeUnit.SECONDS)
                    repeat(eventsPerWriter) { e: Int ->
                        val id = "w${w}e$e"
                        val lineCount: Int = 200 + random.nextInt(400)
                        val message: String =
                            (0 until lineCount).joinToString("\n") { l: Int -> "$id-$l-" + "y".repeat(random.nextInt(80)) }
                        expected[id] = "I/H: $message"
                        log.i { message }
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join(BOUND_SECONDS * 1000) }
            assertFalse(threads.any { it.isAlive }, "seed=$seed: a writer did not finish within the hang bound")
        }
        val lines: List<String> = target.toString(Charsets.UTF_8).split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
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

    private companion object {
        const val BOUND_SECONDS: Long = 20L
    }
}

/** Starts a child JVM on this test's classpath with arguments and returns its whole output once it printed `RESULT`. */
internal object ChildJvmWithArgs {
    fun run(mainClass: String, vararg args: String, boundSeconds: Long = 90L): String {
        val java: String = File(File(System.getProperty("java.home"), "bin"), "java").path
        val command: List<String> = listOf(java, "-cp", System.getProperty("java.class.path"), mainClass) + args
        val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = LinkedBlockingQueue<String>()
        val all: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val reader: Thread = thread(isDaemon = true, name = "child-reader-r3") {
            process.inputStream.bufferedReader().forEachLine { line: String -> all += line; lines.put(line) }
        }
        try {
            var result: String? = null
            while (result == null) {
                val line: String = lines.poll(boundSeconds, TimeUnit.SECONDS) ?: break
                if (line.startsWith("RESULT")) result = line
            }
            process.waitFor(boundSeconds, TimeUnit.SECONDS)
            reader.join(5_000L)
            val output: String = synchronized(all) { all.joinToString("\n") }
            return if (result == null) "RESULT none (the child printed no result within the bound)\n$output" else output
        } finally {
            process.destroyForcibly()
            process.waitFor(10L, TimeUnit.SECONDS)
        }
    }
}

/**
 * The child JVM of the round 3 subclass deadlock tests (LOG-1). `args[0]` picks standard error's shape: `plain` (a
 * subclass overriding nothing), `lines` (its `print(String)` writes line by line), `ownlock` (its `print(String)`
 * takes a lock of its own, then calls the base class). Thread B runs `printStackTrace()` of a throwable whose
 * getMessage waits until thread A is blocked or parked inside its own log call and then logs; prints `RESULT ok`, or
 * `RESULT deadlock ...` with both stacks and the JVM version, then halts.
 */
internal object PlatformLogSinkR3AbbaChild {

    private const val BOUND_SECONDS: Long = 20L
    private const val STEP_MILLIS: Long = 20L

    private class LinesStream(captured: ByteArrayOutputStream) : PrintStream(captured, true, "UTF-8") {
        override fun print(s: String?) {
            val text: String = s ?: "null"
            var start = 0
            while (start < text.length) {
                val end: Int = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
                super.print(text.substring(start, end))
                start = end
            }
        }
    }

    private class OwnLockStream(captured: ByteArrayOutputStream) : PrintStream(captured, true, "UTF-8") {
        private val own = Any()

        override fun print(s: String?) {
            synchronized(own) { super.print(s) }
        }
    }

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
        val mode: String = args.firstOrNull() ?: "plain"
        val captured = ByteArrayOutputStream()
        val err: PrintStream = when (mode) {
            "lines" -> LinesStream(captured)
            "ownlock" -> OwnLockStream(captured)
            else -> object : PrintStream(captured, true, "UTF-8") {}
        }
        System.setErr(err)
        val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger("r3")
        val aLogging = CountDownLatch(1)
        val bHoldsErr = CountDownLatch(1)
        val choreographed = AtomicBoolean(false)
        val threadA: AtomicReference<Thread> = AtomicReference(null)
        val aStateWhenGetterLogged: AtomicReference<Thread.State?> = AtomicReference(null)

        val hostile: RuntimeException = object : RuntimeException() {
            override val message: String
                get() {
                    if (choreographed.compareAndSet(false, true)) {
                        bHoldsErr.countDown()
                        awaitParked(threadA.get(), aLogging)
                        aStateWhenGetterLogged.set(threadA.get().state)
                        logger.i { "logged from getMessage\nwhile printStackTrace holds the stream" }
                    }
                    return "hostile"
                }
        }
        val a = Thread({
            bHoldsErr.await(BOUND_SECONDS, TimeUnit.SECONDS)
            aLogging.countDown()
            logger.i { "a1\na2\na3" }
        }, "r3-abba-A").apply { isDaemon = true }
        threadA.set(a)
        val b = Thread({ hostile.printStackTrace() }, "r3-abba-B").apply { isDaemon = true }
        a.start()
        b.start()
        a.join(BOUND_SECONDS * 1000 * 2)
        b.join(BOUND_SECONDS * 1000 * 2)

        val jvm: String = System.getProperty("java.version")
        if (a.isAlive || b.isAlive) {
            val deadlocked: LongArray? = java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads()
            out.println("RESULT deadlock mode=$mode jvm=$jvm a=${a.state} b=${b.state} jvmDetectedDeadlockedThreads=${deadlocked?.size ?: 0}")
            listOf(a, b).forEach { thread: Thread ->
                out.println("--- ${thread.name} ${thread.state}")
                thread.stackTrace.take(16).forEach { frame: StackTraceElement -> out.println("    at $frame") }
            }
            out.flush()
            Runtime.getRuntime().halt(3)
        }
        err.flush()
        val lines: List<String> = synchronized(captured) { captured.toString(Charsets.UTF_8) }
            .split('\n').map { it.removeSuffix("\r") }
        val aAt: Int = lines.indexOf("I/r3: a1")
        val aWhole: Boolean = aAt >= 0 && lines.subList(aAt, minOf(lines.size, aAt + 3)) == listOf("I/r3: a1", "a2", "a3")
        val getterAt: Int = lines.indexOf("I/r3: logged from getMessage")
        val getterWhole: Boolean = getterAt >= 0 && lines.getOrNull(getterAt + 1) == "while printStackTrace holds the stream"
        // Control: A really was waiting when the getter logged, so the two paths did overlap.
        val overlapped: Boolean = aStateWhenGetterLogged.get() == Thread.State.BLOCKED || aStateWhenGetterLogged.get() == Thread.State.WAITING
        val complete: Boolean = aWhole && getterWhole && lines.any { it.endsWith(": hostile") } && overlapped
        out.println(
            if (complete) {
                "RESULT ok"
            } else {
                "RESULT incomplete mode=$mode jvm=$jvm aWhole=$aWhole getterWhole=$getterWhole aState=${aStateWhenGetterLogged.get()}\n" +
                    lines.joinToString("\n")
            },
        )
        out.flush()
        kotlin.system.exitProcess(0)
    }
}

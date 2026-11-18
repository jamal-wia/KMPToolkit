package io.github.jamal_wia.kmptoolkit.logging

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group logging-permission (LOG-1, the desktop JVM `platformLogSink()`), derived from the sink's
 * KDoc and `docs/kmptoolkit-logging/05-platform-notes.md` ("Desktop JVM"): one event is written in one step, a
 * `message`, `toString` or `cause` override never runs while the sink's lock is held, `System.err` is read at
 * every event, and a sink that fails does not break the logger (LoggerFactory KDoc).
 *
 * Attacks: an ABBA deadlock between the sink's process-wide lock and the lock `Throwable.printStackTrace` holds on
 * `System.err` while it calls the throwable's own `getMessage` (run in a child JVM, because a real deadlock cannot be
 * undone inside the test JVM and would wedge every later test of the class that logs); `System.setErr` swapped while
 * writers storm; a standard error whose write throws an unchecked exception (the lock must not be wedged); an
 * interrupted writer queued behind a blocked one; Compose leaking onto the JVM classpath.
 *
 * Every wait has a hang bound; nothing sleeps; every random choice comes from a fixed seed named in the message.
 */
class PlatformLogSinkJvmBreakerR1Test {

    @AfterTest
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun <T> withErr(stream: PrintStream, work: () -> T): T {
        val original: PrintStream = System.err
        System.setErr(stream)
        try {
            return work()
        } finally {
            System.setErr(original)
        }
    }

    // --- deadlock: the sink's lock against System.err's own lock -----------------------------------

    /**
     * Thread B runs `hostile.printStackTrace()`, the JDK's default rendering (also what the default uncaught
     * exception handler does). The JDK holds `System.err`'s lock for the whole trace and calls the throwable's
     * `getMessage` under it; this `getMessage` logs one line through a logger whose sink is the platform sink.
     * Thread A logs a plain event at the same moment. With a sink lock taken before `System.err`'s lock, A holds
     * the sink lock and waits for `System.err`, B holds `System.err` and waits for the sink lock: a deadlock that
     * hangs both threads forever. Nothing here breaks the logging contract: no sink logs into its own factory, and
     * the KDoc promises that no caller code runs while the sink's lock is held, which is exactly the property an
     * ABBA needs to be impossible. Mutation by reading: any lock acquired by the sink before it writes to a stream
     * whose own lock the JDK holds while calling user code turns this red.
     */
    @Test
    fun `printStackTrace of a throwable whose getMessage logs does not deadlock against a concurrent event`() {
        val result: String = ChildJvm.run(PlatformLogSinkAbbaChild::class.java.name)
        assertEquals("RESULT ok", result.lineSequence().first { it.startsWith("RESULT") }, result)
    }

    // --- System.err swapped mid-storm --------------------------------------------------------------

    @Test
    fun `System setErr swapped while writers storm puts every event whole into exactly one stream`() {
        val seed = 20_261_116
        val writers = 6
        val eventsPerWriter = 60
        val swaps = 200
        val buffers: List<ByteArrayOutputStream> = List(2) { ByteArrayOutputStream() }
        val streams: List<PrintStream> = buffers.map { buffer -> PrintStream(buffer, true, "UTF-8") }
        val start = CountDownLatch(1)
        val finished = CountDownLatch(writers + 1)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val original: PrintStream = System.err
        try {
            System.setErr(streams[0])
            repeat(writers) { writer ->
                thread(isDaemon = true, name = "swap-writer-$writer") {
                    try {
                        val random = Random(seed * 100 + writer)
                        val sink: LogSink = platformLogSink()
                        start.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
                        repeat(eventsPerWriter) { index ->
                            val frames: Int = random.nextInt(0, 12)
                            val error: Throwable = IllegalStateException("t$writer-$index").also { error ->
                                error.stackTrace =
                                    Array(frames) { frame -> StackTraceElement("x.F", "f$frame", "F.java", frame + 1) }
                            }
                            sink.log(LogLevel.WARN, "w$writer", "e$writer-$index", error)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        finished.countDown()
                    }
                }
            }
            thread(isDaemon = true, name = "err-swapper") {
                try {
                    start.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
                    repeat(swaps) { swap -> System.setErr(streams[(swap + 1) % 2]) }
                } finally {
                    finished.countDown()
                }
            }
            start.countDown()
            assertTrue(finished.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "seed=$seed: the storm did not end within the hang bound")
        } finally {
            System.setErr(original)
        }
        failure.get()?.let { throw AssertionError("seed=$seed: a writer failed", it) }

        val header = Regex("W/w(\\d+): e(\\d+-\\d+)")
        val seen: MutableMap<String, Int> = HashMap()
        buffers.forEachIndexed { which, buffer ->
            var open: String? = null
            buffer.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }.forEach { line ->
                val match: MatchResult? = header.matchEntire(line)
                if (match != null) {
                    val id: String = match.groupValues[2]
                    open = id
                    seen.merge(id, 1, Int::plus)
                } else {
                    val id: String = open ?: throw AssertionError("seed=$seed: stream $which has a line of no event: $line")
                    assertTrue(
                        line == "java.lang.IllegalStateException: t$id" || line.startsWith("\tat x.F.f"),
                        "seed=$seed: stream $which carries a line of another event inside event $id: $line",
                    )
                }
            }
        }
        assertEquals(writers * eventsPerWriter, seen.size, "seed=$seed: events lost")
        assertTrue(seen.values.all { it == 1 }, "seed=$seed: an event was written twice: ${seen.filterValues { it != 1 }}")
    }

    // --- a throwing standard error must not wedge the lock -----------------------------------------

    /** An output stream whose write throws an unchecked exception, which `PrintStream` does not swallow. */
    private class ExplodingStream(private val armed: AtomicBoolean) : OutputStream() {
        val written: ByteArrayOutputStream = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (armed.get()) throw IllegalStateException("the device is gone")
            written.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (armed.get()) throw IllegalStateException("the device is gone")
            written.write(b, off, len)
        }
    }

    @Test
    fun `an unchecked exception from standard error is contained and the next event from another thread is written`() {
        val armed = AtomicBoolean(true)
        val target = ExplodingStream(armed)
        val stream = PrintStream(target, true, "UTF-8")
        val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger("X")
        withErr(stream) {
            // Control: the sink itself lets the failure out (PrintStream does not catch it) ...
            assertFailsWith<IllegalStateException> { platformLogSink().log(LogLevel.INFO, "X", "direct", null) }
            // ... and the logger contains it.
            logger.i { "first" }
            armed.set(false)
            val done = CountDownLatch(1)
            thread(isDaemon = true, name = "after-explosion") {
                logger.i { "second" }
                done.countDown()
            }
            assertTrue(done.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "the lock stayed held after the failed write")
        }
        // endsWith, not equals: bytes a PrintStream buffered before the failed flush may be written later; that is
        // the stream's business, the attack is only that the sink's lock is free again.
        val written: String = target.written.toString(Charsets.UTF_8)
        assertTrue(written.endsWith("I/X: second" + System.lineSeparator()), "the second event is missing: $written")
    }

    // --- an interrupted writer queued behind a blocked one -----------------------------------------

    /** A stream whose first write blocks until [release] opens, so a second writer queues behind it. */
    private class GateStream(private val entered: CountDownLatch, private val release: CountDownLatch) : OutputStream() {
        val written: ByteArrayOutputStream = ByteArrayOutputStream()
        private val first: AtomicBoolean = AtomicBoolean(true)

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                // The blocked writer models a full pipe. Only the queued writer is ever interrupted, never this one.
                try {
                    release.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            synchronized(written) { written.write(b, off, len) }
        }
    }

    @Test
    fun `a caller interrupted while it waits behind a blocked writer still writes its event and keeps its interrupt`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = GateStream(entered, release)
        val stream = PrintStream(target, true, "UTF-8")
        val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger("Q")
        val secondInterrupted = AtomicBoolean(false)
        val secondStarted = CountDownLatch(1)
        withErr(stream) {
            val first: Thread = thread(isDaemon = true, name = "blocked-writer") { logger.i { "blocked" } }
            assertTrue(entered.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "control: the first writer never reached the stream")
            val second: Thread = thread(isDaemon = true, name = "queued-writer") {
                secondStarted.countDown()
                logger.i { "queued" }
                secondInterrupted.set(Thread.currentThread().isInterrupted)
            }
            assertTrue(secondStarted.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS))
            second.interrupt()
            release.countDown()
            first.join(HANG_BOUND_SECONDS * 1000)
            second.join(HANG_BOUND_SECONDS * 1000)
            assertFalse(first.isAlive || second.isAlive, "a writer did not finish within the hang bound")
        }
        val text: String = target.written.toString(Charsets.UTF_8)
        assertTrue(text.contains("I/Q: blocked") && text.contains("I/Q: queued"), "an event was lost: $text")
        assertTrue(secondInterrupted.get(), "the queued caller's interrupt was cleared")
    }

    // --- no Compose on the desktop classpath -------------------------------------------------------

    @Test
    fun `the jvm artifact pulls no Compose runtime and no logging overlay onto the classpath`() {
        listOf("androidx.compose.runtime.Composer", "androidx.compose.runtime.Composition").forEach { name ->
            assertFailsWith<ClassNotFoundException>("$name is on the desktop classpath of kmptoolkit-logging") {
                Class.forName(name)
            }
        }
        // Control: the class loader does find this module's own JVM sink.
        Class.forName("io.github.jamal_wia.kmptoolkit.logging.LogSink_jvmKt")
    }

    internal companion object {
        const val HANG_BOUND_SECONDS: Long = 30L
    }
}

/** Starts a child JVM on this test's classpath and returns its whole output once it printed a `RESULT` line. */
internal object ChildJvm {
    fun run(mainClass: String, boundSeconds: Long = 90L): String {
        val java: String = File(File(System.getProperty("java.home"), "bin"), "java").path
        val process: Process = ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), mainClass))
            .redirectErrorStream(true)
            .start()
        val lines = LinkedBlockingQueue<String>()
        val all: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val reader: Thread = thread(isDaemon = true, name = "child-reader") {
            process.inputStream.bufferedReader().forEachLine { line: String -> all += line; lines.put(line) }
        }
        try {
            var result: String? = null
            while (result == null) {
                val line: String = lines.poll(boundSeconds, TimeUnit.SECONDS) ?: break
                if (line.startsWith("RESULT")) result = line
            }
            // The child prints its diagnostics after the RESULT line; let it finish them.
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
 * The child JVM of the ABBA test: prints `RESULT ok` when both threads finish, or `RESULT deadlock ...` with the
 * JVM's own deadlock detection and both stacks, then halts (the deadlocked threads cannot be stopped).
 */
internal object PlatformLogSinkAbbaChild {

    private const val BOUND_SECONDS: Long = 20L

    /** Returns once [a] is parked or blocked after it set [aLogging], or ended; a bound only, never an assertion. */
    private fun awaitParked(a: Thread, aLogging: AtomicBoolean) {
        // timing-hygiene-ignore: TH2 bound hang deadline only, the wait ends on the thread's own state
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        while (a.isAlive) {
            val state: Thread.State = a.state
            if (aLogging.get() && (state == Thread.State.WAITING || state == Thread.State.BLOCKED)) return
            // timing-hygiene-ignore: TH2 bound hang deadline only, the wait ends on the thread's own state
            if (System.nanoTime() > deadline) return
            Thread.onSpinWait()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out: PrintStream = System.out
        val captured = ByteArrayOutputStream()
        // A stock PrintStream, exactly as the JVM's own System.err is one.
        val err = PrintStream(captured, true, "UTF-8")
        System.setErr(err)
        val logger: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(platformLogSink())).logger("abba")
        val aLogging = AtomicBoolean(false)
        val bHoldsErr = CountDownLatch(1)
        val choreographed = AtomicBoolean(false)
        val threadA: AtomicReference<Thread> = AtomicReference(null)
        val messageCalls = AtomicInteger(0)

        val hostile: RuntimeException = object : RuntimeException() {
            override val message: String
                get() {
                    messageCalls.incrementAndGet()
                    if (choreographed.compareAndSet(false, true)) {
                        bHoldsErr.countDown()
                        awaitParked(threadA.get(), aLogging)
                        logger.i { "logged from getMessage while printStackTrace holds System.err" }
                    }
                    return "hostile"
                }
        }
        val a = Thread({
            bHoldsErr.await(BOUND_SECONDS, TimeUnit.SECONDS)
            aLogging.set(true)
            logger.i { "plain event from thread A" }
        }, "abba-A").apply { isDaemon = true }
        threadA.set(a)
        val b = Thread({ hostile.printStackTrace() }, "abba-B").apply { isDaemon = true }
        a.start()
        b.start()
        a.join(BOUND_SECONDS * 1000)
        b.join(BOUND_SECONDS * 1000)

        if (a.isAlive || b.isAlive) {
            val deadlocked: LongArray? = ManagementFactory.getThreadMXBean().findDeadlockedThreads()
            out.println("RESULT deadlock a=${a.state} b=${b.state} jvmDetectedDeadlockedThreads=${deadlocked?.size ?: 0}")
            listOf(a, b).forEach { thread ->
                out.println("--- ${thread.name} ${thread.state}")
                thread.stackTrace.take(14).forEach { frame -> out.println("    at $frame") }
            }
            out.flush()
            Runtime.getRuntime().halt(3)
        }
        err.flush()
        val text: String = captured.toString(Charsets.UTF_8)
        val complete: Boolean = text.contains("I/abba: plain event from thread A") &&
            text.contains("I/abba: logged from getMessage while printStackTrace holds System.err") &&
            text.contains("hostile")
        out.println(if (complete) "RESULT ok" else "RESULT incomplete messageCalls=${messageCalls.get()}\n$text")
        out.flush()
        exitProcess(0)
    }
}

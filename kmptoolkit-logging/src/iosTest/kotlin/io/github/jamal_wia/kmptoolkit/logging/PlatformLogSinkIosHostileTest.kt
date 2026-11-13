package io.github.jamal_wia.kmptoolkit.logging

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_apply
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_t
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.open
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-attack of the iOS `platformLogSink()` fix (one `println` of header, line break and stack trace under a
 * process-wide lock, the trace rendered before the lock is taken): a throwable whose `toString` logs through the
 * same sink, a suppressed graph with a cycle, the order of events written by one thread, an event whose
 * message is large, and a logger in front of the sink whose throwable cannot be rendered.
 *
 * Every call that could block runs on a global queue and is waited for with a bound that fails with its own
 * message, so a lock taken twice (a non-recursive `NSLock`) fails the test instead of hanging the run.
 */
@OptIn(ExperimentalForeignApi::class)
class PlatformLogSinkIosHostileTest {

    private fun <T> captured(body: () -> T): Pair<T, List<String>> {
        val manager: NSFileManager = NSFileManager.defaultManager
        // timing-hygiene-ignore: TH4 private-dir a UUID directory of this call, removed once the capture is read; nothing else is listed
        val directory: String = NSTemporaryDirectory() + "kmptoolkit-ios-hostile-" + NSUUID().UUIDString
        manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val path: String = "$directory/capture.txt"
        val saved: Int = dup(STDOUT_FILENO)
        val fd: Int = open(path, O_WRONLY or O_CREAT or O_TRUNC, 420)
        dup2(fd, STDOUT_FILENO)
        val result: T
        try {
            result = body()
        } finally {
            dup2(saved, STDOUT_FILENO)
            close(saved)
            close(fd)
        }
        val text: String = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
        manager.removeItemAtPath(directory, error = null)
        return result to text.split('\n')
    }

    /** Runs [work] on a global queue; true when it finished within about 20 seconds (hang bound, not a measurement). */
    private fun finishesInBound(work: () -> Unit): Boolean {
        val done: dispatch_semaphore_t = dispatch_semaphore_create(0)
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            try {
                work()
            } finally {
                dispatch_semaphore_signal(done)
            }
        }
        return dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 20_000_000_000L)) == 0L
    }

    private class Chatty(private val sink: LogSink) : Throwable("chatty") {
        override fun toString(): String {
            sink.log(LogLevel.INFO, "inner", "logged-from-toString", null)
            return "Chatty-rendered"
        }
    }

    @Test
    fun `a throwable whose toString logs through the same sink does not deadlock and both events are written`() {
        val sink: LogSink = platformLogSink()
        var finished = false
        val (_, lines: List<String>) = captured {
            finished = finishesInBound { sink.log(LogLevel.ERROR, "outer", "outer-message", Chatty(sink)) }
        }
        assertTrue(finished, "the sink did not return: the trace is rendered under the lock and toString re-entered it")
        assertTrue("I/inner: logged-from-toString" in lines, "inner event missing: ${lines.take(6)}")
        val header: Int = lines.indexOf("E/outer: outer-message")
        assertTrue(header >= 0, "outer header missing: ${lines.take(6)}")
        assertEquals("Chatty-rendered", lines[header + 1], "the trace starts directly under its header")
    }

    @Test
    fun `a suppressed graph with a cycle is printed once and ends`() {
        val a = IllegalStateException("a")
        val b = IllegalArgumentException("b", a)
        a.addSuppressed(b)
        b.addSuppressed(a)
        val sink: LogSink = platformLogSink()
        var finished = false
        val (_, lines: List<String>) = captured {
            finished = finishesInBound { sink.log(LogLevel.WARN, "sup", "m", a) }
        }
        assertTrue(finished, "stackTraceToString of a suppressed cycle did not return")
        val header: Int = lines.indexOf("W/sup: m")
        assertTrue(header >= 0, lines.take(6).toString())
        assertTrue(lines[header + 1].contains("IllegalStateException: a"), lines[header + 1])
        assertTrue(lines.any { "Suppressed" in it }, "no suppressed section: ${lines.take(12)}")
        assertTrue(lines.any { "CIRCULAR REFERENCE" in it }, "the cycle is not marked: ${lines.take(12)}")
    }

    @Test
    fun `a nested cause chain with suppressed throwables at several levels is rendered down to the deepest cause`() {
        val root = IllegalStateException("deepest")
        var current: Throwable = root
        repeat(30) { n ->
            val link = RuntimeException("link-$n", current)
            link.addSuppressed(IllegalArgumentException("sup-$n"))
            current = link
        }
        val sink: LogSink = platformLogSink()
        var finished = false
        val (_, lines: List<String>) = captured {
            finished = finishesInBound { sink.log(LogLevel.ERROR, "nest", "m", current) }
        }
        assertTrue(finished)
        assertTrue(lines.any { "deepest" in it }, "deepest cause missing")
        assertTrue(lines.any { "sup-29" in it } && lines.any { "sup-0" in it }, "suppressed levels missing")
    }

    @Test
    fun `one thread writing alternating events keeps their order and each trace under its own header`() {
        val sink: LogSink = platformLogSink()
        val (_, lines: List<String>) = captured {
            repeat(500) { n ->
                val failure: Throwable? = if (n % 2 == 0) IllegalStateException("trace-$n") else null
                sink.log(LogLevel.DEBUG, "seq", "event-$n", failure)
            }
        }
        val headers: List<Int> = lines.indices.filter { lines[it].startsWith("D/seq: event-") }
        assertEquals((0 until 500).map { "D/seq: event-$it" }, headers.map { lines[it] }, "headers in order")
        for (n in 0 until 500 step 2) {
            assertTrue(lines[headers[n] + 1].contains("trace-$n"), "event $n: ${lines[headers[n] + 1]}")
        }
        for (n in 1 until 500 step 2) {
            assertTrue(lines[headers[n] + 1].startsWith("D/seq: event-${n + 1}") || n == 499, "event $n has a stray line below it: ${lines[headers[n] + 1]}")
        }
    }

    @Test
    fun `eight threads writing a message of a quarter of a megabyte each keep every header whole and every trace beneath it`() {
        val sink: LogSink = platformLogSink()
        val big: String = "x".repeat(256 * 1024)
        val (_, lines: List<String>) = captured {
            dispatch_apply(8uL, dispatch_get_global_queue(0, 0u)) { worker ->
                repeat(10) { n ->
                    sink.log(LogLevel.ERROR, "big$worker", "$n:$big", IllegalStateException("trace-$worker-$n"))
                }
            }
        }
        val headers: List<Int> = lines.indices.filter { lines[it].startsWith("E/big") }
        assertEquals(80, headers.size, "headers")
        for (index: Int in headers) {
            val line: String = lines[index]
            val worker: String = line.substringAfter("E/big").substringBefore(":")
            val n: String = line.substringAfter(": ").substringBefore(":")
            assertEquals("E/big$worker: $n:$big", line, "torn header")
            assertTrue(lines[index + 1].contains("trace-$worker-$n"), "trace of $worker/$n is not under its header: ${lines[index + 1].take(80)}")
        }
    }

    @Test
    fun `a logger in front of the sink contains a throwable that cannot be rendered and later events are intact`() {
        class Unrenderable : Throwable("never printed") {
            override fun toString(): String = throw IllegalStateException("toString failed")
        }
        val later: MutableList<String> = mutableListOf()
        val factory: LoggerFactory = createLoggerFactory(
            LogLevel.DEBUG,
            listOf(platformLogSink(), LogSink { _, _, message, _ -> later += message }),
        )
        val (_, lines: List<String>) = captured {
            val log: Logger = factory.logger("hostile")
            log.e(Unrenderable()) { "poisoned" }
            log.e { "after-poison" }
        }
        assertEquals(listOf("poisoned", "after-poison"), later, "the second sink must still see both events")
        assertTrue("E/hostile: after-poison" in lines, "the event after the unrenderable one was lost or torn: ${lines.take(6)}")
    }
}

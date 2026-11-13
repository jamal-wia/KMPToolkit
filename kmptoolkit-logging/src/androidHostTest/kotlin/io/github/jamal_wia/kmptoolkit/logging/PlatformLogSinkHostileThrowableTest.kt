package io.github.jamal_wia.kmptoolkit.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Re-attack of the Android `platformLogSink()` fix (`mustRenderHere`: an identity-based walk of the cause chain):
 * a `getCause()` that throws, a suppressed graph, a huge message, an interrupted thread, and a throwable that
 * cannot be rendered, each also through a logger factory with a second sink behind the platform sink.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 35])
class PlatformLogSinkHostileThrowableTest {

    private val sink: LogSink = platformLogSink()

    @BeforeTest
    fun clear() {
        ShadowLog.clear()
    }

    @AfterTest
    fun clearAfter() {
        ShadowLog.clear()
        Thread.interrupted()
    }

    /** Runs [work] on its own thread and fails with its own message when it does not return within the hang bound. */
    private fun returnsInBound(work: () -> Unit) {
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val worker: Thread = Thread { try { work() } catch (t: Throwable) { failure.set(t) } }
        worker.start()
        worker.join(20_000)
        assertTrue(!worker.isAlive, "the sink did not return within the hang bound")
        failure.get()?.let { throw it }
    }

    private class ThrowingCause : RuntimeException("outer") {
        override val cause: Throwable get() = throw IllegalStateException("getCause failed")
    }

    @Test
    fun `a getCause that throws is contained by the logger and the next sink still gets the event`() {
        val second: RecordingSink = RecordingSink("second")
        val log: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(sink, second)).logger("T")
        log.e(ThrowingCause()) { "m" }
        assertEquals(1, second.events.size)
        assertEquals("m", second.events.single().message)
    }

    @Test
    fun `an UnknownHostException only in the suppressed list is passed through like the platform rule says`() {
        val outer = IOException("outer")
        outer.addSuppressed(UnknownHostException("suppressed.host"))
        sink.log(LogLevel.ERROR, "T", "m", outer)
        val item: ShadowLog.LogItem = ShadowLog.getLogs().single()
        assertSame(outer, item.throwable, "the platform walks causes only, so a suppressed host needs no rendering")
        assertEquals("m", item.msg)
    }

    @Test
    fun `a suppressed cycle is handed to the platform and the call returns`() {
        val a = IllegalStateException("a")
        val b = IllegalArgumentException("b", a)
        a.addSuppressed(b)
        b.addSuppressed(a)
        returnsInBound { sink.log(LogLevel.WARN, "T", "m", a) }
        assertEquals(1, ShadowLog.getLogs().size)
    }

    @Test
    fun `a cause chain with suppressed throwables and a late UnknownHostException is rendered by the sink`() {
        var current: Throwable = UnknownHostException("late.host")
        repeat(80) { n ->
            val link = IOException("link-$n", current)
            link.addSuppressed(IllegalArgumentException("sup-$n"))
            current = link
        }
        returnsInBound { sink.log(LogLevel.ERROR, "T", "m", current) }
        val item: ShadowLog.LogItem = ShadowLog.getLogs().single()
        assertNull(item.throwable)
        assertTrue(item.msg.contains("late.host"), "deepest cause missing")
        assertTrue(item.msg.contains("sup-79"), "suppressed of the head missing")
    }

    @Test
    fun `a five megabyte message with a rendered trace is logged whole`() {
        val big: String = "y".repeat(5 * 1024 * 1024)
        val host = UnknownHostException("big.host")
        sink.log(LogLevel.INFO, "T", big, host)
        val item: ShadowLog.LogItem = ShadowLog.getLogs().single()
        assertTrue(item.msg.startsWith(big), "message cut")
        assertTrue(item.msg.contains("big.host"), "trace missing")
    }

    @Test
    fun `an interrupted thread keeps its flag and the event is logged with its rendered trace`() {
        val host = UnknownHostException("interrupt.host")
        Thread.currentThread().interrupt()
        sink.log(LogLevel.ERROR, "T", "m", host)
        assertTrue(Thread.currentThread().isInterrupted, "the sink cleared the caller's interrupt flag")
        assertTrue(ShadowLog.getLogs().single().msg.contains("interrupt.host"))
    }

    @Test
    fun `a rendered throwable that cannot print itself is contained and the next sink still gets the event`() {
        class Unprintable : UnknownHostException("x") {
            override fun toString(): String = throw IllegalStateException("toString failed")
        }
        val second: RecordingSink = RecordingSink("second")
        val log: Logger = createLoggerFactory(LogLevel.DEBUG, listOf(sink, second)).logger("T")
        log.e(Unprintable()) { "m" }
        log.e { "after" }
        assertEquals(listOf("m", "after"), second.events.map { it.message })
        assertTrue(ShadowLog.getLogs().any { it.msg == "after" }, "the event after the unprintable one was lost")
    }
}

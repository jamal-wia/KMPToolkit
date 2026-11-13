package io.github.jamal_wia.kmptoolkit.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.UnknownHostException
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
 * The contract of `platformLogSink()`: for any throwable with an `UnknownHostException` in its cause chain the
 * trace is rendered by the sink and never swallowed, and the sink never hands `android.util.Log` a throwable it
 * cannot render.
 *
 * `android.util.Log.getStackTraceString` walks the WHOLE cause chain with no bound: it answers an empty string
 * for a chain with an `UnknownHostException` at any depth, and it never returns on a cyclic chain (A caused by B
 * caused by A, built with `initCause`; the JDK refuses only self-causation). These tests therefore never call the
 * platform renderer: they read what the sink passed to `Log` through [ShadowLog] and walk that chain with their
 * own bound ([platformWouldSwallowOrSpin]). The text part of an entry is the sink's own, so it is read directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 35])
class PlatformLogSinkLongChainTest {

    private val sink: LogSink = platformLogSink()

    @BeforeTest
    fun clear() {
        ShadowLog.clear()
    }

    @AfterTest
    fun clearAfter() {
        ShadowLog.clear()
    }

    private fun chainOf(links: Int, tail: Throwable): Throwable {
        var current: Throwable = tail
        repeat(links) { current = IOException("link", current) }
        return current
    }

    /**
     * True when the platform renderer would lose or never finish the trace of [passed]: an `UnknownHostException`
     * in the chain, or a chain that does not end within [bound] links. A null [passed] is safe (nothing to walk).
     */
    private fun platformWouldSwallowOrSpin(passed: Throwable?, bound: Int = 100_000): Boolean {
        var t: Throwable? = passed
        var n = 0
        while (t != null) {
            if (t is UnknownHostException) return true
            t = t.cause
            if (++n > bound) return true
        }
        return false
    }

    private fun logOnce(throwable: Throwable): ShadowLog.LogItem {
        sink.log(LogLevel.ERROR, "T", "m", throwable)
        return ShadowLog.getLogs().single()
    }

    @Test
    fun `control an UnknownHostException at link 63 is rendered by the sink`() {
        val item: ShadowLog.LogItem = logOnce(chainOf(62, UnknownHostException("h63")))
        assertNull(item.throwable)
        assertTrue(item.msg.contains("UnknownHostException: h63"), item.msg)
    }

    @Test
    fun `control an UnknownHostException at link 64 the last link the old bounded walk saw is rendered by the sink`() {
        val item: ShadowLog.LogItem = logOnce(chainOf(63, UnknownHostException("h64")))
        assertNull(item.throwable)
        assertTrue(item.msg.contains("UnknownHostException: h64"), item.msg)
    }

    @Test
    fun `an UnknownHostException at link 65 is rendered by the sink and not left to the platform to swallow`() {
        val item: ShadowLog.LogItem = logOnce(chainOf(64, UnknownHostException("h65")))
        assertNull(item.throwable, "the chain has an UnknownHostException at link 65: the platform renders it as an empty string")
        assertTrue(item.msg.contains("UnknownHostException: h65"), item.msg)
    }

    @Test
    fun `an UnknownHostException at link 70 is rendered by the sink and not left to the platform to swallow`() {
        val item: ShadowLog.LogItem = logOnce(chainOf(69, UnknownHostException("h70")))
        assertNull(item.throwable, "the chain has an UnknownHostException at link 70: the platform renders it as an empty string")
        assertTrue(item.msg.contains("UnknownHostException: h70"), item.msg)
    }

    @Test
    fun `a long chain without an UnknownHostException is passed through unchanged`() {
        val thrown: Throwable = chainOf(200, IllegalStateException("tail"))
        val item: ShadowLog.LogItem = logOnce(thrown)
        assertSame(thrown, item.throwable)
        assertEquals("m", item.msg)
    }

    @Test
    fun `a cyclic cause chain is rendered by the sink and not handed to the platform whose own walk never ends`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        val item: ShadowLog.LogItem = logOnce(a)
        assertTrue(!platformWouldSwallowOrSpin(item.throwable, bound = 1_000), "the sink passed a cyclic cause chain to android.util.Log")
        assertNull(item.throwable)
        assertTrue(item.msg.contains("IOException: a"), item.msg)
        assertTrue(item.msg.contains("CIRCULAR REFERENCE"), item.msg)
    }

    @Test
    fun `a cyclic cause chain that contains an UnknownHostException is rendered by the sink`() {
        val a = IOException("a")
        val host = UnknownHostException("cyclic.host")
        host.initCause(a)
        val b = IOException("b", host)
        a.initCause(b)
        val item: ShadowLog.LogItem = logOnce(b)
        assertNull(item.throwable)
        assertTrue(item.msg.contains("UnknownHostException: cyclic.host"), item.msg)
        assertTrue(item.msg.contains("CIRCULAR REFERENCE"), item.msg)
    }
}

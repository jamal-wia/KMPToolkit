package io.github.jamal_wia.kmptoolkit.logging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The Android sink promises that logcat renders a real stack trace for the throwable it is given.
 * `android.util.Log.getStackTraceString` returns an empty string for a throwable with an
 * `UnknownHostException` anywhere in its cause chain (so a bare `Log.e(tag, msg, throwable)` shows
 * the message and nothing else), so the sink has to render such a chain itself.
 *
 * What logcat shows for a call is the message followed by `Log.getStackTraceString(throwable)` of
 * the throwable passed alongside it; [shown] reproduces exactly that from the entries
 * [ShadowLog] records. Pinned to API 35 (the module's Robolectric level) and 24, the library minimum.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 35])
class PlatformLogSinkStackTraceTest {

    private val sink: LogSink = platformLogSink()

    @BeforeTest
    fun clear() = ShadowLog.clear()

    @AfterTest
    fun clearAfter() = ShadowLog.clear()

    private fun shown(): String = ShadowLog.getLogs().joinToString("\n") { item: ShadowLog.LogItem ->
        val trace: String = item.throwable?.let { Log.getStackTraceString(it) }.orEmpty()
        item.msg + "\n" + trace
    }

    private fun hasStackFrames(text: String): Boolean = text.contains("\tat ")

    @Test
    fun `an ordinary throwable is rendered with its stack trace`() {
        sink.log(LogLevel.ERROR, "T", "boom", IllegalStateException("ordinary"))

        val text: String = shown()
        assertTrue(text.contains("boom"), text)
        assertTrue(text.contains("IllegalStateException: ordinary"), text)
        assertTrue(hasStackFrames(text), text)
    }

    @Test
    fun `an UnknownHostException is rendered with its stack trace`() {
        sink.log(LogLevel.ERROR, "T", "lookup failed", UnknownHostException("no.such.host"))

        val text: String = shown()
        assertTrue(text.contains("lookup failed"), text)
        assertTrue(text.contains("UnknownHostException: no.such.host"), text)
        assertTrue(hasStackFrames(text), text)
    }

    @Test
    fun `an UnknownHostException deep in a cause chain renders the whole chain`() {
        val chain = IOException("outer", RuntimeException("middle", UnknownHostException("deep.host")))
        sink.log(LogLevel.WARN, "T", "wrapped", chain)

        val text: String = shown()
        assertTrue(text.contains("IOException: outer"), text)
        assertTrue(text.contains("RuntimeException: middle"), text)
        assertTrue(text.contains("UnknownHostException: deep.host"), text)
    }

    @Test
    fun `every level renders an UnknownHostException chain`() {
        for (level: LogLevel in LogLevel.entries) {
            ShadowLog.clear()
            sink.log(level, "T", "at $level", RuntimeException("x", UnknownHostException("h-$level")))

            val text: String = shown()
            assertTrue(text.contains("UnknownHostException: h-$level"), "$level: $text")
            assertTrue(text.contains("at $level"), "$level: $text")
        }
    }

    @Test
    fun `an event without a throwable is just the message`() {
        sink.log(LogLevel.INFO, "T", "plain", null)

        val logs: List<ShadowLog.LogItem> = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals("plain", logs.single().msg)
        assertNull(logs.single().throwable)
    }
}

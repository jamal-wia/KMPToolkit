package io.github.jamal_wia.kmptoolkit.logging

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
 * Two details of the rendered path of the Android `platformLogSink()` that the KDoc states and no test pinned: a
 * throwable that only IS an `UnknownHostException` (a subclass, which the platform's `instanceof` rule also
 * swallows) is rendered by the sink, and the rendered trace goes on its own lines below the message.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 35])
class PlatformLogSinkRenderingTest {

    private val sink: LogSink = platformLogSink()

    private class NetworkDown : UnknownHostException("network is down")

    @BeforeTest
    fun clear() {
        ShadowLog.clear()
    }

    @AfterTest
    fun clearAfter() {
        ShadowLog.clear()
    }

    @Test
    fun `a subclass of UnknownHostException in the chain is rendered by the sink and not left to the platform`() {
        val chain: Throwable = IOException("outer", IOException("middle", NetworkDown()))
        sink.log(LogLevel.ERROR, "T", "m", chain)
        val item: ShadowLog.LogItem = ShadowLog.getLogs().single()
        assertNull(item.throwable, "the platform answers an empty string for any UnknownHostException, subclasses included")
        assertTrue(item.msg.contains("NetworkDown"), item.msg)
        assertTrue(item.msg.contains("network is down"), item.msg)
    }

    @Test
    fun `the rendered trace starts on its own line directly below the message`() {
        sink.log(LogLevel.WARN, "T", "the message", UnknownHostException("h"))
        val lines: List<String> = ShadowLog.getLogs().single().msg.lines()
        assertEquals("the message", lines[0])
        assertTrue(lines[1].startsWith("java.net.UnknownHostException: h"), lines[1])
    }
}

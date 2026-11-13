package io.github.jamal_wia.kmptoolkit.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The call budget of the Android path, counted rather than timed: one platform `Log` call per event whatever the
 * throwable is (plain, with an `UnknownHostException` in the chain, cyclic), and a message lambda that is evaluated
 * exactly once per event however many sinks follow the platform one. A logging call runs on whatever thread the
 * app logs from, the main thread included, so a second `Log` call or a second evaluation per event would double
 * the cost of every log line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 35])
class PlatformLogSinkCallBudgetTest {

    @BeforeTest
    fun clear() {
        ShadowLog.clear()
    }

    @AfterTest
    fun clearAfter() {
        ShadowLog.clear()
    }

    private class Cycle(label: String) : Exception(label) {
        var next: Throwable? = null
        override val cause: Throwable? get() = next
    }

    private fun throwableFor(index: Int): Throwable? = when (index % 4) {
        0 -> null
        1 -> IOException("plain $index")
        2 -> IOException("outer $index", UnknownHostException("host$index"))
        else -> Cycle("a$index").also { first -> first.next = Cycle("b$index").also { second -> second.next = first } }
    }

    @Test
    fun `one thousand events cost exactly one platform Log call each`() {
        val sink: LogSink = platformLogSink()
        repeat(1_000) { index ->
            sink.log(LogLevel.entries[index % LogLevel.entries.size], "Budget", "event $index", throwableFor(index))
        }
        val items: List<ShadowLog.LogItem> = ShadowLog.getLogs().filter { item -> item.tag == "Budget" }
        assertEquals(1_000, items.size)
        assertEquals(1_000, items.map { item -> item.msg.substringBefore('\n') }.toSet().size, "every event reached the platform once and in its own call")
    }

    @Test
    fun `the message lambda runs once per event with three sinks behind the platform sink`() {
        val evaluated = AtomicInteger()
        val seenBySinks = AtomicInteger()
        val counting = LogSink { _, _, _, _ -> seenBySinks.incrementAndGet() }
        val log: Logger = createLoggerFactory(LogLevel.VERBOSE, listOf(platformLogSink(), counting, counting)).logger("Budget")

        repeat(1_000) { index ->
            log.log(LogLevel.INFO, throwableFor(index)) {
                evaluated.incrementAndGet()
                "event $index"
            }
        }

        assertEquals(1_000, evaluated.get())
        assertEquals(2_000, seenBySinks.get())
        assertEquals(1_000, ShadowLog.getLogs().count { item -> item.tag == "Budget" })
    }

    @Test
    fun `an event below the threshold costs no evaluation and no platform call`() {
        val evaluated = AtomicInteger()
        val log: Logger = createLoggerFactory(LogLevel.WARN, listOf(platformLogSink())).logger("Budget")

        repeat(1_000) { log.log(LogLevel.INFO, null) { evaluated.incrementAndGet().toString() } }

        assertEquals(0, evaluated.get())
        assertEquals(0, ShadowLog.getLogs().count { item -> item.tag == "Budget" })
    }
}

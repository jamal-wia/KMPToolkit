package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The overlay buffer and panel under churn: writer threads, clear, hide/show and a host that enters
 * and leaves composition, all bounded in count and in real time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class LogOverlayChurnUiTest {

    @Test
    fun `eight writers with clear and hide show while the host enters and leaves thirty times keep the bound`() =
        runComposeUiTest {
            val state = LogOverlayState(maxRecords = MAX)
            val sink = state.asLogSink()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val stop = AtomicBoolean(false)
            val boom = IllegalStateException("boom")
            val writers: List<Thread> = List(8) { writer ->
                thread(name = "overlay-writer-$writer", isDaemon = true) {
                    try {
                        repeat(PER_WRITER) { i ->
                            sink.log(LogLevel.INFO, "w$writer", "m$i", if (i % 10 == 0) boom else null)
                        }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            val churner: Thread = thread(name = "overlay-churn", isDaemon = true) {
                try {
                    while (!stop.get()) {
                        state.clear()
                        state.hide()
                        state.show()
                        Thread.sleep(1)
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
            var hostShown by mutableStateOf(true)
            setContent {
                if (hostShown) LogOverlayHost(state = state) { Text("app") }
            }

            repeat(30) { round ->
                hostShown = false
                waitForIdle()
                hostShown = true
                waitForIdle()
                val snapshot: List<LogRecord> = state.records.value
                assertTrue(snapshot.size <= MAX, "round $round: ${snapshot.size} records exceed the bound $MAX")
                val ids: List<Long> = snapshot.map(LogRecord::id)
                assertEquals(ids.distinct(), ids, "round $round: duplicate ids in one snapshot")
                assertEquals(ids.sorted(), ids, "round $round: ids out of order in one snapshot")
            }

            writers.forEach { it.join(30_000) }
            stop.set(true)
            churner.join(5_000)
            assertTrue(writers.none { it.isAlive } && !churner.isAlive, "a thread is stuck")
            assertEquals(emptyList(), failures.toList())
            state.show()
            waitForIdle()
            val count: Int = state.records.value.size
            assertTrue(count <= MAX)
            onNodeWithText("Logs ($count)").assertIsDisplayed()
        }

    @Test
    fun `a throwable whose text cannot be built does not break the sink or the buffer`() {
        val state = LogOverlayState(maxRecords = 3)
        val hostile = object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("no message")
            override fun toString(): String = throw IllegalStateException("no text")
        }

        runCatching { state.record(LogLevel.ERROR, "T", "m", hostile) }
        state.record(LogLevel.INFO, "T", "after")

        assertTrue(state.records.value.any { it.message == "after" })
    }

    @Test
    fun `an expanded row does not survive clear to expand the record that reuses its id`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.ERROR, "Net", "first", IllegalStateException("first-trace"))
        setContent { LogOverlayPanel(state = state) }

        onNodeWithText("[Net] first").performClick()
        onNodeWithText("first-trace", substring = true).assertIsDisplayed()

        state.clear()
        state.record(LogLevel.ERROR, "Net", "second", IllegalStateException("second-trace"))
        waitForIdle()

        onNodeWithText("[Net] second").assertIsDisplayed()
        onNodeWithText("second-trace", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val MAX = 50
        const val PER_WRITER = 2_500
    }
}

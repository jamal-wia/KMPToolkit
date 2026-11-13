package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.e
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boss round of unit F: the overlay sink installed the documented way, beside another sink in one
 * `LoggerFactory`, fed throwables that strain the overlay's rendering. Whatever the overlay does with
 * them, the factory's promise holds: the caller is not broken and every other sink still receives the
 * event. And a cause chain that is long but also reachable through suppressed exceptions (so the
 * overlay's breadth-first depth measure lets it through to the full renderer) still renders without
 * overflowing the stack, because the node cap bounds the printer's recursion.
 */
class BossOverlayBehindLoggerTest {

    private class UnfinishedMessage : RuntimeException() {
        override val message: String get() = TODO("message not written yet")
    }

    private class Event(val tag: String, val message: String, val throwable: Throwable?)

    private class RecordingSink : LogSink {
        val events: MutableList<Event> = mutableListOf()
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            events += Event(tag, message, throwable)
        }
    }

    @Test
    fun `a throwable the overlay cannot describe never breaks the caller or the sink after the overlay`() {
        val overlay = LogOverlayState()
        val after = RecordingSink()
        val logger = createLoggerFactory(sinks = listOf(overlay.asLogSink(), after)).logger("T")
        val failure = UnfinishedMessage()

        logger.e(failure) { "boom" }

        assertEquals(1, after.events.size)
        assertEquals("boom", after.events.single().message)
        assertTrue(after.events.single().throwable === failure)
    }

    @Test
    fun `a long cause chain also reachable as suppressed renders without overflowing the stack`() {
        // 1 023 nodes in all: just under the overlay's node cap, so the full renderer runs and recurses
        // once per cause on its depth-first walk.
        val causes = 1_022
        var chain: Throwable = RuntimeException("c0")
        val all: MutableList<Throwable> = mutableListOf(chain)
        repeat(causes - 1) {
            chain = RuntimeException("c${it + 1}", chain)
            all += chain
        }
        val root = RuntimeException("root", chain)
        all.forEach { cause: Throwable -> root.addSuppressed(cause) }
        val overlay = LogOverlayState()

        overlay.asLogSink().log(LogLevel.ERROR, "T", "boom", root)

        val record: LogRecord = overlay.records.value.single()
        assertEquals("boom", record.message)
        assertTrue(record.throwableText.orEmpty().contains("c0"), "the deepest cause is missing from the text")
    }
}

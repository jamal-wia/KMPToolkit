package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `asLogSink()` never throws and never hangs, whatever graph the throwable holds. The JDK refuses only a throwable
 * that is its own cause; `initCause` happily builds a cycle of two or of three thousand, and `addSuppressed` a
 * ring of suppressed exceptions. Also a graph whose breadth-first depth is small but whose depth-first rendering
 * order runs down a long chain (shortcuts through the suppressed list).
 */
class OverlayCyclicThrowableGraphTest {

    private fun record(throwable: Throwable): String? {
        val state = LogOverlayState()
        var failure: Throwable? = null
        val worker: Thread = thread(name = "overlay-cyclic", isDaemon = true) {
            try {
                state.asLogSink().log(LogLevel.ERROR, "T", "boom", throwable)
            } catch (t: Throwable) {
                failure = t
            }
        }
        worker.join(20_000)
        assertFalse(worker.isAlive, "the sink is stuck on a cyclic graph")
        assertEquals(null, failure?.toString(), "the sink threw")
        return state.records.value.single().throwableText
    }

    @Test
    fun `two exceptions that are each other's cause are recorded`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        assertNotNull(record(a))
    }

    @Test
    fun `a ring of three thousand causes is recorded`() {
        val ring: List<RuntimeException> = List(3_000) { RuntimeException("n$it") }
        ring.forEachIndexed { i, e -> e.initCause(ring[(i + 1) % ring.size]) }
        val text: String = record(ring[0]).orEmpty()
        assertTrue(text.startsWith("java.lang.RuntimeException: n0"), text.take(120))
    }

    @Test
    fun `a ring of suppressed exceptions is recorded`() {
        val ring: List<RuntimeException> = List(50) { RuntimeException("s$it") }
        ring.forEachIndexed { i, e -> e.addSuppressed(ring[(i + 1) % ring.size]) }
        assertNotNull(record(ring[0]))
    }

    @Test
    fun `a cause ring of mixed cause and suppressed edges is recorded`() {
        val nodes: List<RuntimeException> = List(2_000) { RuntimeException("m$it") }
        nodes.forEachIndexed { i, e ->
            if (i % 2 == 0) e.initCause(nodes[(i + 1) % nodes.size]) else e.addSuppressed(nodes[(i + 1) % nodes.size])
        }
        assertNotNull(record(nodes[0]))
    }

    @Test
    fun `shortcuts through the suppressed list do not hide a thousand deep rendering path`() {
        val chain: List<RuntimeException> = List(1_000) { RuntimeException("c$it") }
        for (i in 0 until chain.size - 1) chain[i].initCause(chain[i + 1])
        val root = RuntimeException("root")
        // Every 60th link is also a direct (suppressed) child of the root, so each node is at most 60 hops from it.
        for (i in chain.indices step 60) root.addSuppressed(chain[i])
        val text: String = record(root).orEmpty()
        assertTrue(text.isNotEmpty())
    }
}

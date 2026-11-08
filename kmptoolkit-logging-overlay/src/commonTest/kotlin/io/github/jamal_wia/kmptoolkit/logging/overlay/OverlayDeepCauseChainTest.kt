package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hostile input: the overlay sink promises never to throw, and never catches an Error to keep that promise. */
class OverlayDeepCauseChainTest {

    @Test
    fun `a throwable with a very long cause chain does not make the sink throw`() {
        val state = LogOverlayState()
        var chain: Throwable = RuntimeException("root")
        repeat(20_000) { chain = RuntimeException("wrap $it", chain) }
        val error: Throwable? = runCatching { state.asLogSink().log(LogLevel.ERROR, "tag", "boom", chain) }.exceptionOrNull()
        assertEquals(null, error?.toString(), "the sink promised never to throw")
        assertEquals(1, state.records.value.size, "the event was lost from the overlay")
        val text: String = state.records.value.single().throwableText.orEmpty()
        assertTrue("wrap 19999" in text, "the outermost throwable is named: $text")
        assertTrue("more causes not shown" in text, "the cut is announced: $text")
    }

    @Test
    fun `a chain within the limit renders exactly its stack trace`() {
        val state = LogOverlayState()
        var chain: Throwable = RuntimeException("root")
        repeat(10) { chain = RuntimeException("wrap $it", chain) }
        state.record(LogLevel.ERROR, "tag", "boom", chain)
        assertEquals(chain.stackTraceToString(), state.records.value.single().throwableText)
    }
}

package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `asLogSink()` is documented as a sink that never throws. A retry loop that wraps its previous failure
 * thousands of times deep is the same hostile input as any deep cause chain, and it does not become harmless
 * when the exception type has value equality (a Kotlin `data class`-style exception, or any class that
 * overrides `equals`): the graph is measured by instance, not by value.
 */
class OverlayEqualThrowablesChainTest {

    private class Retry(val code: Int, cause: Throwable?) : RuntimeException("retry $code", cause) {
        override fun equals(other: Any?): Boolean = other is Retry && other.code == code
        override fun hashCode(): Int = code
    }

    @Test
    fun `a twenty thousand deep chain of value-equal exceptions is recorded without the sink throwing`() {
        var chain: Throwable = Retry(1, null)
        repeat(20_000) { chain = Retry(1, chain) }
        val state = LogOverlayState()

        state.asLogSink().log(LogLevel.ERROR, "T", "gave up", chain)

        assertEquals(listOf("gave up"), state.records.value.map { it.message })
        val text: String = state.records.value.single().throwableText.orEmpty()
        assertTrue("more causes not shown" in text, "the summary path was not taken: $text")
    }

    @Test
    fun `a ten deep chain of value-equal exceptions is within limits and renders in full`() {
        var chain: Throwable = Retry(1, null)
        repeat(10) { chain = Retry(1, chain) }
        val state = LogOverlayState()

        state.asLogSink().log(LogLevel.ERROR, "T", "gave up", chain)

        assertEquals(chain.stackTraceToString(), state.records.value.single().throwableText)
    }

    private class HostileHash : RuntimeException("hostile") {
        override fun hashCode(): Int = throw IllegalStateException("hashCode")
        override fun equals(other: Any?): Boolean = throw IllegalStateException("equals")
    }

    @Test
    fun `a throwable whose hashCode throws is recorded with its real stack trace text`() {
        val hostile = RuntimeException("outer", HostileHash())
        val state = LogOverlayState()

        state.asLogSink().log(LogLevel.ERROR, "T", "boom", hostile)

        // Where the platform's own printer copes with a hostile hashCode (the JDK's uses an identity set) the
        // record is exactly its text. Kotlin/Native's keeps a HashSet and throws on this very input, so there the
        // documented placeholder is the answer; either way the sink does not throw.
        val expected: String = runCatching { hostile.stackTraceToString() }
            .getOrElse { "RuntimeException (stack trace unavailable: IllegalStateException)" }
        assertEquals(expected, state.records.value.single().throwableText)
    }
}

package io.github.jamal_wia.kmptoolkit.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS twin of the Android long-chain test: the iOS `platformLogSink()` prints
 * `throwable.stackTraceToString()` itself, so a long or cyclic cause chain must neither hang nor
 * throw out of the sink, and the rendering it prints must keep the deepest cause and end a cycle.
 *
 * Standard output is not captured here (`PlatformLogSinkIosConcurrencyTest` redirects it), so the sink
 * call proves "returns without throwing" and the rendering the sink prints is checked through the same
 * `stackTraceToString()` call on the same chain.
 * A cycle cannot be built with constructors on Kotlin/Native (the `cause` of a `Throwable` is a
 * constructor value), so [Node] overrides `cause`, which `Throwable` declares open.
 */
class PlatformLogSinkIosChainTest {

    private class Node(message: String) : Throwable(message) {
        var next: Throwable? = null
        override val cause: Throwable? get() = next
    }

    private fun chainOf(links: Int): Throwable {
        var current: Throwable = IllegalStateException("root of $links")
        repeat(links - 1) { index -> current = IllegalStateException("link ${index + 2}", current) }
        return current
    }

    @Test
    fun a_chain_of_65_links_is_printed_down_to_its_root_without_throwing() {
        val chain: Throwable = chainOf(65)
        platformLogSink().log(LogLevel.ERROR, "chain", "message", chain)
        assertTrue("root of 65" in chain.stackTraceToString())
    }

    @Test
    fun a_chain_of_1000_links_is_printed_down_to_its_root_without_throwing() {
        val chain: Throwable = chainOf(1000)
        platformLogSink().log(LogLevel.ERROR, "chain", "message", chain)
        assertTrue("root of 1000" in chain.stackTraceToString())
    }

    @Test
    fun a_two_link_cycle_ends_and_says_so() {
        val a = Node("a")
        val b = Node("b")
        a.next = b
        b.next = a
        platformLogSink().log(LogLevel.ERROR, "cycle", "message", a)
        assertTrue("CIRCULAR REFERENCE" in a.stackTraceToString())
    }

    @Test
    fun a_cycle_that_does_not_start_at_its_head_ends_and_says_so() {
        val a = Node("a")
        val b = Node("b")
        val c = Node("c")
        a.next = b
        b.next = c
        c.next = b
        platformLogSink().log(LogLevel.ERROR, "cycle", "message", a)
        assertTrue("CIRCULAR REFERENCE" in a.stackTraceToString())
    }

    @Test
    fun a_throwable_that_is_its_own_cause_ends() {
        val a = Node("self")
        a.next = a
        platformLogSink().log(LogLevel.ERROR, "cycle", "message", a)
        assertEquals(true, a.stackTraceToString().isNotEmpty())
    }
}

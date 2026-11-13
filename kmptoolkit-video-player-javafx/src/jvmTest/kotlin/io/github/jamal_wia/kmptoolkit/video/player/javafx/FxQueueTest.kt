package io.github.jamal_wia.kmptoolkit.video.player.javafx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [FxQueue]'s ordering rule over a manual queue: nothing here touches JavaFX, so the rule is
 * checked without a toolkit. A block "runs on the FX thread" while [drain] runs it, or while a
 * test sets [onFx].
 */
class FxQueueTest {

    private val pendingBlocks: ArrayDeque<Runnable> = ArrayDeque()
    private val edges: MutableList<Int> = mutableListOf()
    private var onFx: Boolean = false
    private var queueFailure: IllegalStateException? = null

    private val queue: FxQueue = FxQueue(
        runLater = { block ->
            queueFailure?.let { throw it }
            pendingBlocks.addLast(block)
        },
        isFxThread = { onFx },
        onEdge = { delta -> edges += delta },
    )

    /** Runs every queued block in order, as the FX thread would, including blocks queued meanwhile. */
    private fun drain() {
        val before: Boolean = onFx
        onFx = true
        try {
            while (pendingBlocks.isNotEmpty()) pendingBlocks.removeFirst().run()
        } finally {
            onFx = before
        }
    }

    @Test
    fun aPostOnTheFxThreadWithNothingQueuedRunsInline() {
        onFx = true
        var ran = false

        queue.post { ran = true }

        assertTrue(ran, "nothing was queued, so the block runs before post() returns")
        assertTrue(pendingBlocks.isEmpty(), "an inline block is never handed to runLater")
        assertEquals(emptyList(), edges, "an inline block is not counted as queued work")
    }

    @Test
    fun aPostFromAnotherThreadIsQueuedEvenWhenNothingIsWaiting() {
        var ran = false

        queue.post { ran = true }

        assertFalse(ran, "off the FX thread the block waits for the FX thread")
        assertEquals(1, queue.pending)
        drain()
        assertTrue(ran)
        assertEquals(0, queue.pending)
    }

    @Test
    fun aPostOnTheFxThreadBehindQueuedWorkWaitsForIt() {
        val order: MutableList<String> = mutableListOf()
        queue.post { order += "queued from another thread" }
        onFx = true

        queue.post { order += "posted on the FX thread" }

        assertEquals(emptyList(), order, "inline would overtake the block queued before it")
        drain()
        assertEquals(listOf("queued from another thread", "posted on the FX thread"), order)
    }

    @Test
    fun aPostMadeFromInsideAQueuedBlockRunsInlineOnceNothingIsLeftWaiting() {
        val order: MutableList<String> = mutableListOf()
        queue.post {
            order += "outer start"
            queue.post { order += "inner" }
            order += "outer end"
        }

        drain()

        assertEquals(listOf("outer start", "inner", "outer end"), order)
    }

    @Test
    fun aPostLaterOnTheFxThreadWithNothingQueuedIsStillQueued() {
        onFx = true
        var ran = false

        queue.postLater { ran = true }

        assertFalse(ran, "postLater never runs inline")
        assertEquals(1, queue.pending)
        drain()
        assertTrue(ran)
    }

    @Test
    fun aPostLaterRunsAfterWhatWasQueuedBeforeIt() {
        val order: MutableList<String> = mutableListOf()
        queue.postLater { order += "first" }
        queue.post { order += "second" }
        queue.postLater { order += "third" }

        drain()

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun theEdgesAreOnePlusAndOneMinusAroundEachQueuedBlockAndNothingMoreWhileItRuns() {
        val seenInside: MutableList<List<Int>> = mutableListOf()
        queue.post { seenInside += edges.toList() }
        queue.post { seenInside += edges.toList() }

        assertEquals(emptyList(), edges, "nothing is counted at enqueue")
        drain()

        assertEquals(listOf(listOf(1), listOf(1, -1, 1)), seenInside)
        assertEquals(listOf(1, -1, 1, -1), edges)
    }

    @Test
    fun aBlockThatThrowsStillEndsItsEdgeAndLeavesTheCounterAtZero() {
        queue.post { error("boom") }

        val thrown: IllegalStateException = assertFailsWith { drain() }

        assertEquals("boom", thrown.message)
        assertEquals(listOf(1, -1), edges)
        assertEquals(0, queue.pending)
    }

    @Test
    fun aCallReturnsTheResultOfTheBlockRunOnTheFxThread() = runBlocking {
        val call: Deferred<Int> = async(start = CoroutineStart.UNDISPATCHED) { queue.call { 42 } }
        assertEquals(1, queue.pending, "off the FX thread the call is queued")

        drain()

        assertEquals(42, withTimeout(TIMEOUT_MS) { call.await() })
    }

    @Test
    fun aCallRethrowsWhatTheBlockThrew() = runBlocking {
        val failure: IllegalArgumentException = IllegalArgumentException("from the block")
        // The failure is caught inside the async so it does not cancel the surrounding scope.
        val call: Deferred<Result<Int>> = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { queue.call<Int> { throw failure } }
        }

        drain()

        val outcome: Result<Int> = withTimeout(TIMEOUT_MS) { call.await() }
        // Coroutine stack-trace recovery may hand back a copy, so the type and message are compared.
        val thrown: Throwable? = outcome.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "the block's exception type, was $thrown")
        assertEquals(failure.message, thrown.message)
        assertEquals(listOf(1, -1), edges, "a failed call block still ends its edge")
    }

    @Test
    fun aCallOnTheFxThreadWithNothingQueuedRunsInline() = runBlocking {
        onFx = true

        val result: String = withTimeout(TIMEOUT_MS) { queue.call { "inline" } }

        assertEquals("inline", result)
        assertTrue(pendingBlocks.isEmpty())
        assertEquals(emptyList(), edges)
    }

    @Test
    fun aCallOnTheFxThreadBehindQueuedWorkWaitsForIt() = runBlocking {
        val order: MutableList<String> = mutableListOf()
        queue.post { order += "queued first" }
        onFx = true

        val call: Deferred<Boolean> = async(start = CoroutineStart.UNDISPATCHED) { queue.call { order.add("call") } }
        assertEquals(emptyList(), order, "inline would overtake the queued block")
        drain()
        withTimeout(TIMEOUT_MS) { call.await() }

        assertEquals(listOf("queued first", "call"), order)
    }

    @Test
    fun aPostThatCouldNotBeQueuedIsSwallowedAndNeverCounted() {
        queueFailure = IllegalStateException("Toolkit not initialized")
        var ran = false

        queue.post { ran = true }
        queue.postLater { ran = true }

        assertFalse(ran)
        assertEquals(0, queue.pending, "a block that was never queued must not hold later calls back")
        assertEquals(emptyList(), edges)
    }

    @Test
    fun aCounterLeftCleanByAFailedQueueingLetsTheNextPostRunInline() {
        queueFailure = IllegalStateException("Toolkit not initialized")
        queue.post { }
        queueFailure = null
        onFx = true
        var ran = false

        queue.post { ran = true }

        assertTrue(ran, "the failed block must not count as queued work")
    }

    @Test
    fun aCallThatCouldNotBeQueuedThrowsAndIsNeverCounted() {
        queueFailure = IllegalStateException("Toolkit not initialized")

        val thrown: IllegalStateException = assertFailsWith { runBlocking { queue.call { 1 } } }

        assertEquals("Toolkit not initialized", thrown.message)
        assertEquals(0, queue.pending)
        assertEquals(emptyList(), edges)
    }

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
    }
}

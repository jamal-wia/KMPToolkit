package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Group sto-desktop, round 12 (observation O4 of the phase A breaker, round 10): what `NativeExecutor` promises about a value
 * when the CALLER'S dispatcher breaks the coroutines contract by throwing from `dispatch`.
 *
 * The KDoc promise "a value is never dropped" holds for a dispatcher that keeps its contract. When `dispatch` throws, the resumption
 * of the awaiting coroutine is lost inside kotlinx.coroutines: the executor's `CompletableDeferred` is already completed, so the
 * value is neither delivered nor handed to `discard`, and the caller stays suspended. The executor cannot tell a dispatch that threw
 * before it queued the resumption from one that queued it and then threw, and wiping a value that the caller may still read would
 * hand it a zeroed key, which a store reads as "wrong key": so nothing is wiped. This class pins that stated limit (so a change
 * of it is a conscious one) and the part that must hold even then: the native thread survives and the next call is served. The control
 * runs the same call on a dispatcher that keeps its contract and sees the value delivered.
 *
 * No sleeps: the native thread is passed by a FIFO barrier call (one native thread, calls run in order), every wait is a latch with a
 * hang bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeExecutorRound12Test {

    private class BreakableDispatcher : CoroutineDispatcher() {
        @Volatile var broken: Boolean = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (broken) throw IllegalStateException("a dispatcher that cannot dispatch")
            block.run()
        }
    }

    private class Attempt(val result: CompletableDeferred<Result<NativeOutcome<ByteArray>>>, val discarded: ConcurrentLinkedQueue<ByteArray>, val job: Job)

    /** Starts one unlimited call whose block waits for [gate], on [dispatcher]; [beforeGate] runs once the call is on the native thread. */
    private fun NativeExecutor.start(dispatcher: BreakableDispatcher, gate: CountDownLatch, beforeGate: () -> Unit, value: ByteArray): Attempt {
        val discarded: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()
        val result: CompletableDeferred<Result<NativeOutcome<ByteArray>>> = CompletableDeferred()
        val job: Job = Job()
        val entered = CountDownLatch(1)
        val body: suspend () -> NativeOutcome<ByteArray> = {
            call(null, discard = { late: ByteArray -> discarded.add(late) }) {
                entered.countDown()
                check(gate.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)) { "hang bound: the gate was not opened" }
                value
            }
        }
        body.startCoroutine(Continuation(dispatcher + job) { outcome: Result<NativeOutcome<ByteArray>> -> result.complete(outcome) })
        check(entered.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)) { "hang bound: the call did not reach the native thread" }
        beforeGate()
        return Attempt(result, discarded, job)
    }

    /** After this returns the native thread has finished every call queued before it. */
    private fun NativeExecutor.passAll(): NativeOutcome<Int> = runBlocking { withContext(Dispatchers.Default) { call(null) { 0 } } }

    @Test
    fun `a caller whose dispatcher throws from dispatch is left unresumed, its value is not wiped and the executor serves on`() {
        NativeExecutor().use { executor: NativeExecutor ->
            val dispatcher = BreakableDispatcher()
            val gate = CountDownLatch(1)
            val value: ByteArray = byteArrayOf(1, 2, 3)
            val attempt: Attempt = executor.start(dispatcher, gate, beforeGate = { dispatcher.broken = true }, value = value)
            gate.countDown()
            assertIs<NativeOutcome.Completed<Int>>(executor.passAll(), "the native thread survived the failed delivery and serves the next call")
            assertEquals(0, executor.pendingCalls, "nothing is left queued or running")
            assertFalse(attempt.result.isCompleted, "the resumption was lost with the failed dispatch: the caller is still suspended (the stated limit)")
            assertTrue(attempt.discarded.isEmpty(), "the value is not wiped: the executor cannot know the caller will never read it")
            assertEquals(listOf<Byte>(1, 2, 3), value.toList(), "the value itself is intact")
            attempt.job.cancel()
            assertFalse(attempt.result.isCompleted, "a cancellation does not reach a coroutine that was already resumed")
            assertTrue(attempt.discarded.isEmpty(), "and does not discard the value either")
        }
    }

    @Test
    fun `control - the same call on a dispatcher that keeps its contract is delivered and nothing is discarded`() {
        NativeExecutor().use { executor: NativeExecutor ->
            val dispatcher = BreakableDispatcher()
            val gate = CountDownLatch(1)
            val value: ByteArray = byteArrayOf(1, 2, 3)
            val attempt: Attempt = executor.start(dispatcher, gate, beforeGate = {}, value = value)
            gate.countDown()
            assertIs<NativeOutcome.Completed<Int>>(executor.passAll())
            assertTrue(attempt.result.isCompleted, "control: the caller was resumed")
            val outcome: NativeOutcome<ByteArray> = attempt.result.getCompleted().getOrThrow()
            assertIs<NativeOutcome.Completed<ByteArray>>(outcome)
            assertSame(value, outcome.value, "the very array the call returned")
            assertTrue(attempt.discarded.isEmpty(), "delivered, so not discarded")
            attempt.job.cancel()
        }
    }

    private companion object {
        const val LATCH_BOUND_SECONDS: Long = 30
    }
}

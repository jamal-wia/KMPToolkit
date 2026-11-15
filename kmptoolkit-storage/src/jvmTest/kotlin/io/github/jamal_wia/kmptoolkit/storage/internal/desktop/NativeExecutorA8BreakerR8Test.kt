package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Group sto-desktop, phase A breaker round 8 (decisions.md D-23), part A8: `NativeExecutor.call` (plan 3.5, plan 4.11, the
 * native executor of 6a) against its KDoc:
 *
 * "A call that has not started when its limit passes (or its caller is cancelled) is withdrawn from the queue and never runs,
 * so a timed-out write is never applied later."
 *
 * Round 9 closed the pre-cancelled caller (A7-m2, `ensureActive` first). The same promise breaks for a caller whose call is
 * QUEUED (behind a busy native thread) when its limit passes or it is cancelled: `call` withdraws the task only when the
 * caller's coroutine RESUMES (`task.abandon()` after `await`/`withTimeoutOrNull` returns or throws). Cancellation and the time
 * limit do not resume a coroutine on the spot: the resumption is dispatched, and until the caller's dispatcher runs it the task
 * is still QUEUED. If the busy call ends in that window, the native thread takes the task (QUEUED to RUNNING) and runs it for a
 * caller that is already cancelled, or that will be told [NativeOutcome.TimedOut] while its write is applied afterwards. Any
 * busy dispatcher (a saturated `Dispatchers.IO`/`Default`, a UI dispatcher) opens the window; it needs no unlucky scheduling
 * on the native side, because the native thread is exactly the one that just became free.
 *
 * Determinism: the caller runs on [GateDispatcher], which parks its dispatches while held, so the window is held open by the
 * test instead of hoped for; virtual time (the test scheduler) fires the limit. A probe call queued after the victim tells the
 * test when the native thread has passed the victim (FIFO, one thread), so no wait depends on timing. Mutation by reading: with
 * the withdrawal done at the moment of cancellation (an `invokeOnCancellation` / `onCancelling` handler that runs
 * `task.withdraw()`), both tests are green; on the tip both are red.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class NativeExecutorA8BreakerR8Test {

    /** Parks every dispatch while [held]; [release] hands the parked ones to [inner]. Delays (the limit) go to the test scheduler. */
    private class GateDispatcher(private val inner: TestDispatcher) : CoroutineDispatcher(), Delay by inner {
        @Volatile var held: Boolean = false
        private val parked: ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>> = ConcurrentLinkedQueue()

        val parkedCount: Int get() = parked.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) parked.add(context to block) else inner.dispatch(context, block)
        }

        fun release() {
            held = false
            while (true) {
                val (context: CoroutineContext, block: Runnable) = parked.poll() ?: return
                inner.dispatch(context, block)
            }
        }
    }

    private fun CountDownLatch.awaitBounded(what: String) {
        check(await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)) { "hang bound: $what did not happen within $LATCH_BOUND_SECONDS s" }
    }

    @Test
    fun `a caller cancelled while its call is queued never has its call run`() = runTest(timeout = HANG_BOUND) {
        NativeExecutor().use { executor: NativeExecutor ->
            val holderEntered = CountDownLatch(1)
            val holderRelease = CountDownLatch(1)
            val holder = launch(Dispatchers.Default) {
                executor.call(null) {
                    holderEntered.countDown()
                    holderRelease.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)
                }
            }
            holderEntered.awaitBounded("the holder's call starting")

            val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
            val victimRan = AtomicBoolean(false)
            val victim = launch(gate) { executor.call(null) { victimRan.set(true) } }
            runCurrent()
            assertEquals(2, executor.pendingCalls, "control: the holder runs and the victim's call is queued behind it")

            val passed = CountDownLatch(1)
            val probe = launch(Dispatchers.Default) { executor.call(null) { passed.countDown() } }

            gate.held = true
            victim.cancel()
            assertEquals(1, gate.parkedCount, "control: the victim is cancelled and its resumption is waiting for its dispatcher")
            assertFalse(victimRan.get(), "control: the victim's call has not started when its caller is cancelled")

            holderRelease.countDown()
            passed.awaitBounded("the probe call queued after the victim")
            val ranForCancelledCaller: Boolean = victimRan.get()

            gate.release()
            runCurrent()
            victim.join()
            holder.join()
            probe.join()
            assertTrue(victim.isCancelled, "control: the victim ended cancelled")
            assertEquals(0, executor.pendingCalls, "nothing is left queued or running")
            assertFalse(
                ranForCancelledCaller,
                "KDoc: 'a call that has not started when ... its caller is cancelled is withdrawn from the queue and never runs'; " +
                    "the call was still queued when its caller was cancelled, and it ran because the withdrawal waits for the " +
                    "caller's dispatcher to resume the caller",
            )
        }
    }

    @Test
    fun `a call whose limit passes while it is queued is never applied after its caller was told TimedOut`() = runTest(timeout = HANG_BOUND) {
        NativeExecutor().use { executor: NativeExecutor ->
            val holderEntered = CountDownLatch(1)
            val holderRelease = CountDownLatch(1)
            val holder = launch(Dispatchers.Default) {
                executor.call(null) {
                    holderEntered.countDown()
                    holderRelease.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)
                }
            }
            holderEntered.awaitBounded("the holder's call starting")

            val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
            val decided = CountDownLatch(1)
            val victimEntered = AtomicBoolean(false)
            val victimRelease = CountDownLatch(1)
            val applied = AtomicBoolean(false)
            val outcome: AtomicReference<NativeOutcome<Unit>?> = AtomicReference(null)
            val victim = launch(gate) {
                outcome.set(
                    executor.call(LIMIT) {
                        victimEntered.set(true)
                        decided.countDown()
                        victimRelease.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)
                        applied.set(true)
                    },
                )
            }
            runCurrent()
            assertEquals(2, executor.pendingCalls, "control: the holder runs and the victim's call is queued behind it")

            val probePassed = CountDownLatch(1)
            val probe = launch(Dispatchers.Default) {
                executor.call(null) {
                    decided.countDown()
                    probePassed.countDown()
                }
            }

            gate.held = true
            advanceTimeBy(LIMIT)
            runCurrent()
            assertEquals(1, gate.parkedCount, "control: the limit passed and the victim's resumption is waiting for its dispatcher")
            assertFalse(victimEntered.get(), "control: the victim's call has not started when its limit passes")

            holderRelease.countDown()
            decided.awaitBounded("the native thread reaching the victim's call or the probe after it")
            gate.release()
            runCurrent()
            victim.join()
            val told: NativeOutcome<Unit>? = outcome.get()
            val appliedWhenTold: Boolean = applied.get()
            victimRelease.countDown()
            probePassed.awaitBounded("the probe call queued after the victim")
            holder.join()
            probe.join()
            assertEquals(0, executor.pendingCalls, "nothing is left queued or running")
            assertTrue(told != null, "control: the victim's caller got an outcome")
            assertFalse(
                victimEntered.get(),
                "KDoc: 'a call that has not started when its limit passes ... is withdrawn from the queue and never runs, so a " +
                    "timed-out write is never applied later'; the caller was told $told (applied at that moment: $appliedWhenTold), " +
                    "and the call it was told about ran afterwards (applied now: ${applied.get()})",
            )
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
        val LIMIT = 10.seconds
        const val LATCH_BOUND_SECONDS: Long = 30
    }
}

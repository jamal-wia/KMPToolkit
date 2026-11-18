package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Group sto-desktop, round 10 (finding M1 of phase A, `NativeExecutor`): the task state machine of `NativeExecutor.call`,
 * window by window. The table of windows is in `reports/round-10.md`; each test names the window it closes.
 *
 * KDoc under test: a call that has not started when its caller is cancelled or its limit passes is withdrawn from the queue AT
 * THAT INSTANT and never runs. The caller's own dispatcher is held by [GateDispatcher] so that the resumption of the caller
 * cannot be what withdraws the call; the limit is virtual time. Fixed seeds, no sleeps, every wait is a latch with a hang bound.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class NativeExecutorRound10Test {

    /** Parks every dispatch while [held]; [release] hands the parked ones to [inner]. Delays (the limit) go to the test scheduler. */
    private class GateDispatcher(private val inner: TestDispatcher) : CoroutineDispatcher(), Delay by inner {
        @Volatile var held: Boolean = false
        private val parked: ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>> = ConcurrentLinkedQueue()

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

    /** A native thread that is busy until [release]; the call that keeps it busy is made on a real thread. */
    private class Holder(executor: NativeExecutor, scope: CoroutineScope) {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val job: Job = scope.launch(Dispatchers.Default) {
            executor.call(null) {
                entered.countDown()
                release.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)
            }
        }

        fun awaitRunning() = check(entered.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)) { "hang bound: the holder's call did not start" }

        fun end() = release.countDown()
    }

    /** After this returns the native thread has passed every call queued before it (one thread, FIFO). */
    private suspend fun NativeExecutor.passAll() {
        withContext(Dispatchers.Default) { call(null) { 0 } }
    }

    /**
     * Runs [body], and whatever it does (a failed assertion included) ends the busy call and hands every parked resumption on, so a
     * red test fails at once instead of waiting for the hang bound.
     */
    private suspend fun <T> releasingAfter(holder: Holder, gate: GateDispatcher, body: suspend () -> T): T =
        try {
            body()
        } finally {
            holder.end()
            gate.release()
        }

    private fun <T> startOn(context: CoroutineContext, body: suspend () -> T): CompletableDeferred<Result<T>> {
        val result: CompletableDeferred<Result<T>> = CompletableDeferred()
        body.startCoroutine(Continuation(context) { outcome: Result<T> -> result.complete(outcome) })
        return result
    }

    // ---- window W1/W2 (queued, caller leaves, dispatcher busy) at scale: the queue never holds a call whose caller is gone ----

    @Test
    fun `callers cancelled one by one while their dispatcher is busy leave the queue at the cancellation and never run`() =
        runTest(timeout = HANG_BOUND) {
            for (limit: Duration? in listOf(null, LIMIT)) {
                val seed: Long = SEED + (limit?.inWholeSeconds ?: 0L)
                val random = Random(seed)
                NativeExecutor().use { executor: NativeExecutor ->
                    val holder = Holder(executor, this)
                    holder.awaitRunning()
                    val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
                    releasingAfter(holder, gate) {
                        val ran = AtomicInteger()
                        val victims: List<Job> = List(VICTIMS) { launch(gate) { executor.call(limit) { ran.incrementAndGet() } } }
                        runCurrent()
                        assertEquals(VICTIMS + 1, executor.pendingCalls, "seed=$seed limit=$limit control: the holder runs, every victim is queued")

                        gate.held = true
                        val order: List<Job> = victims.shuffled(random)
                        order.forEach { it.cancel() }
                        assertEquals(
                            1,
                            executor.pendingCalls,
                            "seed=$seed limit=$limit: cancelled callers whose dispatcher has not run yet must already be out of the queue",
                        )

                        holder.end()
                        executor.passAll()
                        assertEquals(0, ran.get(), "seed=$seed limit=$limit: a call whose caller was cancelled ran")
                        gate.release()
                        runCurrent()
                        victims.joinAll()
                        holder.job.join()
                        assertTrue(victims.all { it.isCancelled }, "seed=$seed limit=$limit control: every victim ended cancelled")
                        assertEquals(0, executor.pendingCalls, "seed=$seed limit=$limit: nothing is left")
                    }
                }
            }
        }

    // ---- window W1 through an ancestor: the cancellation arrives through the parent chain, with and without a limit ----

    @Test
    fun `cancelling the scope of the callers while their dispatcher is busy withdraws every queued call, with and without a limit`() =
        runTest(timeout = HANG_BOUND) {
            for (limit: Duration? in listOf(null, LIMIT)) {
                NativeExecutor().use { executor: NativeExecutor ->
                    val holder = Holder(executor, this)
                    holder.awaitRunning()
                    val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
                    releasingAfter(holder, gate) {
                        val parent: CompletableJob = Job()
                        val scope = CoroutineScope(parent + gate)
                        val ran = AtomicInteger()
                        val victims: List<Job> = List(VICTIMS) { scope.launch { executor.call(limit) { ran.incrementAndGet() } } }
                        runCurrent()
                        assertEquals(VICTIMS + 1, executor.pendingCalls, "limit=$limit control: every victim is queued")

                        gate.held = true
                        parent.cancel()
                        assertEquals(1, executor.pendingCalls, "limit=$limit: the cancellation of an ancestor withdraws the queued calls at once")

                        holder.end()
                        executor.passAll()
                        assertEquals(0, ran.get(), "limit=$limit: a call whose caller's scope was cancelled ran")
                        gate.release()
                        runCurrent()
                        victims.joinAll()
                        holder.job.join()
                        assertEquals(0, executor.pendingCalls, "limit=$limit: nothing is left")
                    }
                }
            }
        }

    // ---- window W2 with the dispatcher free: the limit path must not wait for an answer the handler already made impossible ----

    @Test
    fun `a limit that passes while the call is queued answers TimedOut and the call never runs even after the busy call ends`() =
        runTest(timeout = HANG_BOUND) {
            NativeExecutor().use { executor: NativeExecutor ->
                val holder = Holder(executor, this)
                holder.awaitRunning()
                val ran = AtomicBoolean(false)
                val outcome: AtomicReference<NativeOutcome<Unit>?> = AtomicReference(null)
                val victim: Job = launch(StandardTestDispatcher(testScheduler)) { outcome.set(executor.call(LIMIT) { ran.set(true) }) }
                runCurrent()
                assertEquals(2, executor.pendingCalls, "control: the victim is queued behind the holder")

                advanceTimeBy(LIMIT)
                runCurrent()
                victim.join()
                assertEquals(NativeOutcome.TimedOut, outcome.get(), "the caller is told TimedOut at its limit (no wait for a task already withdrawn)")
                assertEquals(1, executor.pendingCalls, "the withdrawn call is out of the queue")

                holder.end()
                executor.passAll()
                holder.job.join()
                assertFalse(ran.get(), "a call whose caller was told TimedOut never runs")
                assertEquals(0, executor.pendingCalls, "nothing is left")
            }
        }

    // ---- window W2 at scale: the limit (not a cancellation) passes while the callers' dispatcher is busy ----

    @Test
    fun `calls whose limit passes while their dispatcher is busy leave the queue at the limit and never run`() = runTest(timeout = HANG_BOUND) {
        NativeExecutor().use { executor: NativeExecutor ->
            val holder = Holder(executor, this)
            holder.awaitRunning()
            val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
            releasingAfter(holder, gate) {
                val ran = AtomicInteger()
                val victims: List<Job> = List(VICTIMS) { launch(gate) { executor.call(LIMIT) { ran.incrementAndGet() } } }
                runCurrent()
                assertEquals(VICTIMS + 1, executor.pendingCalls, "control: every victim is queued behind the holder")

                gate.held = true
                advanceTimeBy(LIMIT)
                runCurrent()
                assertTrue(victims.all { it.isActive }, "control: no victim has been resumed, their dispatcher is busy")
                assertEquals(1, executor.pendingCalls, "the limit withdraws the queued calls at once, before their callers are resumed")

                holder.end()
                executor.passAll()
                assertEquals(0, ran.get(), "a call whose limit had passed ran")
                gate.release()
                runCurrent()
                victims.joinAll()
                holder.job.join()
                assertEquals(0, executor.pendingCalls, "nothing is left")
            }
        }
    }

    // ---- window W3 (cancelled between the pre-check and the offer): the task never reaches the queue ----

    @Test
    fun `a caller cancelled after the pre-check and before its call is queued has its call withdrawn before the native thread can see it`() =
        runTest(timeout = HANG_BOUND) {
            val threadMayRun = CountDownLatch(1)
            val caller = AtomicReference<Job?>(null)
            val ran = AtomicBoolean(false)
            // The cancellation lands inside `ensureStarted`, i.e. after `ensureActive()` and before the offer; the native thread of
            // this executor does not start its loop until the test lets it, so nothing but the queue itself can show the task.
            val parkedFactory = ThreadFactory { runnable: Runnable ->
                caller.get()!!.cancel()
                Thread({
                    threadMayRun.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)
                    runnable.run()
                }, "round10-parked").apply { isDaemon = true }
            }
            NativeExecutor(parkedFactory).use { executor: NativeExecutor ->
                val victim: Job = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) { executor.call(null) { ran.set(true) } }
                caller.set(victim)
                victim.start()
                victim.join()
                assertTrue(victim.isCancelled, "control: the caller was cancelled by the thread factory, inside the call")
                assertEquals(0, executor.pendingCalls, "the withdrawn task must not be left in the queue behind a thread that has not started")
                threadMayRun.countDown()
                executor.passAll()
                assertFalse(ran.get(), "the call of a cancelled caller ran")
            }
        }

    @Test
    fun `a caller cancelled between the pre-check and the offer never has its call run by a live native thread`() = runTest(timeout = HANG_BOUND) {
        // Same window, with a native thread that is free and waiting: an offer before the cancellation is watched would hand the
        // call to it. Fresh executors, so the window is entered every time (the thread is made inside the call).
        val seed: Long = SEED
        repeat(ATTEMPTS) { attempt: Int ->
            val caller = AtomicReference<Job?>(null)
            val ran = AtomicBoolean(false)
            val factory = ThreadFactory { runnable: Runnable ->
                caller.get()!!.cancel()
                Thread(runnable, "round10-live").apply { isDaemon = true }
            }
            NativeExecutor(factory).use { executor: NativeExecutor ->
                val victim: Job = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) { executor.call(null) { ran.set(true) } }
                caller.set(victim)
                victim.start()
                victim.join()
                executor.passAll()
                assertFalse(ran.get(), "seed=$seed attempt=$attempt: the call of a caller cancelled before the offer ran")
                assertEquals(0, executor.pendingCalls, "seed=$seed attempt=$attempt: nothing is left")
            }
        }
    }

    // ---- close() against a cancelled caller, in both orders (QUEUED to ABANDONED against QUEUED to REFUSED) ----

    @Test
    fun `close and the cancellation of a queued caller in either order leave nothing and never run the call`() = runTest(timeout = HANG_BOUND) {
        for (closeFirst: Boolean in listOf(true, false)) {
            val executor = NativeExecutor()
            val holder = Holder(executor, this)
            holder.awaitRunning()
            val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
            releasingAfter(holder, gate) {
                val ran = AtomicBoolean(false)
                val victim: Job = launch(gate) { executor.call(null) { ran.set(true) } }
                runCurrent()
                assertEquals(2, executor.pendingCalls, "closeFirst=$closeFirst control: the victim is queued")

                gate.held = true
                if (closeFirst) {
                    executor.close()
                    victim.cancel()
                } else {
                    victim.cancel()
                    executor.close()
                }
                holder.end()
                gate.release()
                runCurrent()
                victim.join()
                holder.job.join()
                assertFalse(ran.get(), "closeFirst=$closeFirst: the call of a cancelled caller ran")
                assertTrue(victim.isCancelled, "closeFirst=$closeFirst: the caller ended cancelled")
                assertEquals(0, executor.pendingCalls, "closeFirst=$closeFirst: nothing is left")
                // Release is terminal; a new executor is the way to start again.
                NativeExecutor().use { fresh: NativeExecutor ->
                    assertEquals(5, assertIs<NativeOutcome.Completed<Int>>(fresh.call(null) { 5 }).value, "closeFirst=$closeFirst: re-init works")
                }
            }
        }
    }

    // ---- every registration the executor makes on the caller's job is released on every way out (retention proof) ----

    /** Something a call's block captures; the call's task holds the block, and a watch left on the caller's job would hold the task. */
    private class Payload

    /**
     * Starts one call in a context whose Job is [longLived] (so the executor's watch is registered on it and survives the call if it
     * is not released) and answers a weak reference to what the call's block captured. Nothing of the payload stays in this frame.
     */
    private fun callCapturing(
        executor: NativeExecutor,
        context: CoroutineContext,
        limit: Duration?,
    ): Pair<WeakReference<Payload>, CompletableDeferred<Result<NativeOutcome<Int>>>> {
        val payload = Payload()
        val answered: CompletableDeferred<Result<NativeOutcome<Int>>> = startOn(context) { executor.call(limit) { payload.hashCode() } }
        return WeakReference(payload) to answered
    }

    private fun assertCollected(reference: WeakReference<Payload>, what: String) {
        var attempts = 0
        while (reference.get() != null && attempts < GC_ATTEMPTS) {
            System.gc()
            attempts++
        }
        assertEquals(null, reference.get(), "$what: the caller's long-lived job still holds the call (its watch was not released)")
    }

    @Test
    fun `the cancellation watch on the caller's job is released on every way out`() = runTest(timeout = HANG_BOUND) {
        NativeExecutor().use { executor: NativeExecutor ->
            val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
            val longLived: CompletableJob = Job()

            val (completedRef, completed) = callCapturing(executor, gate + longLived, null)
            runCurrent()
            assertIs<NativeOutcome.Completed<Int>>(completed.await().getOrThrow(), "control: the call completed")
            assertCollected(completedRef, "completes")

            val (limitedRef, limited) = callCapturing(executor, gate + longLived, LIMIT)
            runCurrent()
            assertIs<NativeOutcome.Completed<Int>>(limited.await().getOrThrow(), "control: the limited call completed")
            assertCollected(limitedRef, "completes with a limit")

            val holder = Holder(executor, this)
            holder.awaitRunning()
            val (timedOutRef, timedOut) = callCapturing(executor, gate + longLived, LIMIT)
            runCurrent()
            assertEquals(2, executor.pendingCalls, "control: the call is queued behind the holder, and the job is still active")
            assertTrue(longLived.isActive, "control: the long-lived job is active")
            advanceTimeBy(LIMIT)
            runCurrent()
            assertEquals(NativeOutcome.TimedOut, timedOut.await().getOrThrow(), "control: the call timed out")
            assertCollected(timedOutRef, "times out")

            holder.end()
            holder.job.join()
            executor.passAll()
            assertEquals(0, executor.pendingCalls)
            longLived.cancel()
        }
    }

    private companion object {
        val HANG_BOUND: Duration = 120.seconds
        val LIMIT: Duration = 10.seconds
        const val LATCH_BOUND_SECONDS: Long = 30
        const val VICTIMS: Int = 40
        const val ATTEMPTS: Int = 300
        const val SEED: Long = 20261510L
        const val GC_ATTEMPTS: Int = 100
    }
}

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Group sto-desktop, phase A breaker round 10 (decisions D-23), attack on the round-11 fix of A9-m1: a limited call is now queued
 * INSIDE its limit's scope, after the watch on that scope (`NativeExecutor.call` / `enqueue`). Contract (class KDoc and D-P15): "a call
 * that has not started when its limit passes (or its caller is cancelled) is withdrawn from the queue and never runs"; "every watch is
 * registered before the call is queued"; "the thread is made before the call is queued: a thread that cannot be made answers that one
 * call `Threw`, the call is never queued ... and the next call tries again"; "a native thread that has died is replaced by the next call".
 *
 * The new order moves the offer behind the arming of the timer and the watch on the limit's scope, so the round-5 rule (thread first,
 * queue second) now has a longer gap: the native thread `ensureStarted` saw alive can die before the offer, and `enqueue` then makes the
 * replacement AFTER the offer. These tests put the limit, the caller's cancellation and a failing thread start exactly in that window,
 * deterministically: the first native thread is a stand-in that never runs the loop and ends when the caller's timer is armed (the
 * caller's dispatcher is its own `Delay`), and the replacement's `ThreadFactory.newThread` (called on the caller's thread, inside
 * `enqueue`, with the task already in the queue: control `pendingCalls == 1`) passes the limit, cancels the caller or throws.
 * A seeded storm then races the limit and the cancellation (from another thread, released at the arming) against the watch, the
 * `isQueued` check and the offer, on a busy executor: whatever the interleaving, the caller is answered, nothing stays queued and a
 * withdrawn call never runs once the busy call ends.
 *
 * No sleeps, no clock: latches and joins with hang bounds, `Random(seed)` with `seed=` in every message.
 *
 * Mutation by reading: an `enqueue` that does not look at `withdraw()` before answering `Threw` (answers `Threw` for a call the limit
 * already withdrew) turns the "passes the limit then cannot start" test red (outcome); a handler that withdraws without `queue.remove`
 * and a WITHDRAWN branch without `queue.remove` together turn the window tests red (`pendingCalls`); an offer before the watch on the
 * limit's scope (the round-10 order) turns test 1 red when the replacement thread takes the task before the limit is watched.
 */
@OptIn(InternalCoroutinesApi::class)
class NativeExecutorA10BreakerR10Test {

    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()
    private val threads: MutableList<Thread> = CopyOnWriteArrayList()
    private val pools: MutableList<ExecutorService> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        threads.forEach { it.join(HANG_BOUND_MS) }
        pools.forEach { it.shutdownNow() }
    }

    private fun await(latch: CountDownLatch, what: String) {
        check(latch.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "hang bound: $what did not happen within ${HANG_BOUND_MS / 1000} s" }
    }

    private fun daemon(name: String, body: Runnable): Thread = Thread(body, name).apply {
        isDaemon = true
        threads += this
    }

    /** The caller's dispatcher: a thread of its own, and the `Delay` that `withTimeoutOrNull` arms; it records the timer and runs [onArm]. */
    private inner class ArmingDispatcher : CoroutineDispatcher(), Delay {
        private val pool: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, "breaker10-a10-caller").apply { isDaemon = true }
        }.also { pools += it }
        val timer: AtomicReference<Runnable?> = AtomicReference(null)
        val armed: AtomicInteger = AtomicInteger()

        @Volatile var onArm: () -> Unit = {}

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pool.execute(block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            throw UnsupportedOperationException("delay() is not used by NativeExecutor.call")
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            armed.incrementAndGet()
            timer.set(block)
            onArm()
            return DisposableHandle { }
        }

        /** Passes the limit: cancels the `withTimeoutOrNull` scope synchronously, on the calling thread. */
        fun fire() {
            (timer.get() ?: error("control: no timer was armed")).run()
        }
    }

    private class ThreadLimitReached : RuntimeException("the replacement native thread cannot be made (breaker A10 seam)")

    /**
     * The window: thread 0 is a stand-in that is alive when `call` checks it and ends when the caller's timer is armed (before the
     * offer); thread 1 is made by `enqueue` after the offer, and [inWindow] runs on the caller's thread right then. Later threads are
     * real native threads.
     */
    private inner class Window(private val inWindow: (NativeExecutor) -> Unit) {
        private val standInEnds = CountDownLatch(1)
        val made: AtomicInteger = AtomicInteger()
        val pendingInWindow: AtomicReference<Int?> = AtomicReference(null)
        lateinit var executor: NativeExecutor
        lateinit var standIn: Thread

        val factory: ThreadFactory = ThreadFactory { runnable: Runnable ->
            when (made.getAndIncrement()) {
                0 -> daemon("breaker10-a10-stand-in") { await(standInEnds, "the end of the stand-in thread") }.also { standIn = it }
                1 -> {
                    pendingInWindow.set(executor.pendingCalls)
                    inWindow(executor)
                    daemon("breaker10-a10-native", runnable)
                }
                else -> daemon("breaker10-a10-native", runnable)
            }
        }

        /** Ends the stand-in and waits until it is dead: from then on `enqueue` must make a replacement after its offer. */
        fun endStandIn() {
            standInEnds.countDown()
            standIn.join(HANG_BOUND_MS)
            check(!standIn.isAlive) { "hang bound: the stand-in thread did not end" }
        }
    }

    private class Run(val outcome: Any?, val runs: Int)

    /** One limited call from [dispatcher] in a window; answers its outcome (or what it threw) after a FIFO probe on a real thread. */
    private fun limitedCallInWindow(window: Window, dispatcher: ArmingDispatcher, callerJob: AtomicReference<Job?>): Run {
        val executor = NativeExecutor(window.factory).also { executors += it }
        window.executor = executor
        val runs = AtomicInteger()
        val result: AtomicReference<Any?> = AtomicReference(null)
        val answered = CountDownLatch(1)
        dispatcher.onArm = { window.endStandIn() }
        CoroutineScope(Job() + dispatcher).launch {
            callerJob.set(coroutineContext[Job])
            try {
                result.set(executor.call(10.seconds) { runs.incrementAndGet(); 7 })
            } catch (escaped: Throwable) {
                result.set(escaped)
            } finally {
                answered.countDown()
            }
        }
        await(answered, "the caller's answer")
        assertEquals(1, dispatcher.armed.get(), "control: the caller armed its limit")
        assertEquals(1, window.pendingInWindow.get(), "control: the replacement thread was made after the offer (the task was queued)")
        // FIFO, one native thread: when this answers, a task left in the queue has run.
        val probe: NativeOutcome<Int> = runBlocking {
            withTimeoutOrNull(HANG_BOUND_MS.milliseconds) { executor.call(null) { 0 } } ?: fail("hang bound: the FIFO probe never answered")
        }
        assertEquals(0, assertIs<NativeOutcome.Completed<Int>>(probe, "control: the executor serves the next call").value)
        assertEquals(0, executor.pendingCalls, "nothing may be left queued or running")
        return Run(result.get(), runs.get())
    }

    @Test
    fun `a limit that passes while the replacement thread is being made after the offer withdraws the call for good`() {
        val dispatcher = ArmingDispatcher()
        val window = Window { _: NativeExecutor -> dispatcher.fire() }
        val run: Run = limitedCallInWindow(window, dispatcher, AtomicReference(null))
        assertSame(NativeOutcome.TimedOut, run.outcome, "the limit passed before the call started: the caller is told TimedOut (${run.outcome})")
        assertEquals(0, run.runs, "KDoc: a call that has not started when its limit passes is withdrawn and never runs; it ran ${run.runs} time(s)")
        assertEquals(2, window.made.get(), "no thread beyond the stand-in, its replacement")
    }

    @Test
    fun `a caller cancelled while the replacement thread is being made after the offer never has its call run`() {
        val dispatcher = ArmingDispatcher()
        val callerJob: AtomicReference<Job?> = AtomicReference(null)
        val window = Window { _: NativeExecutor -> (callerJob.get() ?: error("control: no caller job")).cancel() }
        val run: Run = limitedCallInWindow(window, dispatcher, callerJob)
        assertIs<CancellationException>(run.outcome, "a cancelled caller leaves with its CancellationException: ${run.outcome}")
        assertEquals(0, run.runs, "KDoc: a call whose caller is cancelled before it started never runs; it ran ${run.runs} time(s)")
    }

    @Test
    fun `a replacement thread that cannot be made after the offer answers that call Threw and the next thread never runs it`() {
        val dispatcher = ArmingDispatcher()
        val thrown: AtomicReference<ThreadLimitReached?> = AtomicReference(null)
        val window = Window { _: NativeExecutor -> throw ThreadLimitReached().also { thrown.set(it) } }
        val run: Run = limitedCallInWindow(window, dispatcher, AtomicReference(null))
        val threw: NativeOutcome.Threw = assertIs<NativeOutcome.Threw>(run.outcome, "KDoc: a thread that cannot be made answers that call Threw: ${run.outcome}")
        assertSame<Throwable?>(thrown.get(), threw.cause, "the cause is the failure of the thread start, unchanged")
        assertEquals(0, run.runs, "the call was answered Threw and then run by the next call's thread")
        assertEquals(3, window.made.get(), "control: the probe made the next thread")
    }

    @Test
    fun `a limit that passes and then a failing thread start after the offer answer the caller once and never run the call`() {
        val dispatcher = ArmingDispatcher()
        val window = Window { _: NativeExecutor ->
            dispatcher.fire()
            throw ThreadLimitReached()
        }
        val run: Run = limitedCallInWindow(window, dispatcher, AtomicReference(null))
        assertTrue(
            run.outcome === NativeOutcome.TimedOut || run.outcome is NativeOutcome.Threw,
            "the caller must get one outcome (TimedOut or Threw), not hang or throw: ${run.outcome}",
        )
        assertEquals(0, run.runs, "a call whose limit passed before it started ran later on the probe's thread")
    }

    @Test
    fun `a seeded race of the limit and the cancellation against the queueing of a call leaves nothing queued and never runs it`() {
        val seed = SEED
        val random = Random(seed)
        val executor = NativeExecutor(ThreadFactory { runnable: Runnable -> daemon("breaker10-a10-storm-native", runnable) }).also { executors += it }
        val busyStarted = CountDownLatch(1)
        val releaseBusy = CountDownLatch(1)
        val busyAnswered = CountDownLatch(1)
        daemon("breaker10-a10-busy-caller") {
            runBlocking { executor.call(null) { busyStarted.countDown(); await(releaseBusy, "the release of the busy call"); 0 } }
            busyAnswered.countDown()
        }.start()
        await(busyStarted, "the busy call's start")
        val racer: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, "breaker10-a10-racer").apply { isDaemon = true }
        }.also { pools += it }
        val dispatcher = ArmingDispatcher()
        val runs = AtomicInteger()
        val problems: MutableList<String> = mutableListOf()
        repeat(ATTEMPTS) { attempt: Int ->
            val mode: Int = random.nextInt(3)
            val callerJob: AtomicReference<Job?> = AtomicReference(null)
            val racerDone = CountDownLatch(1)
            val answered = CountDownLatch(1)
            val result: AtomicReference<Any?> = AtomicReference(null)
            dispatcher.onArm = {
                val timer: Runnable = dispatcher.timer.get() ?: error("no timer")
                val job: Job = callerJob.get() ?: error("no caller job")
                racer.execute {
                    try {
                        when (mode) {
                            0 -> timer.run()
                            1 -> job.cancel()
                            else -> {
                                timer.run()
                                job.cancel()
                            }
                        }
                    } finally {
                        racerDone.countDown()
                    }
                }
            }
            CoroutineScope(Job() + dispatcher).launch {
                callerJob.set(coroutineContext[Job])
                try {
                    result.set(executor.call(10.seconds) { runs.incrementAndGet(); 1 })
                } catch (escaped: Throwable) {
                    result.set(escaped)
                } finally {
                    answered.countDown()
                }
            }
            await(answered, "seed=$seed attempt=$attempt: the caller's answer")
            await(racerDone, "seed=$seed attempt=$attempt: the racer")
            val outcome: Any? = result.get()
            val expected: Boolean = when (mode) {
                0 -> outcome === NativeOutcome.TimedOut
                1 -> outcome is CancellationException
                else -> outcome === NativeOutcome.TimedOut || outcome is CancellationException
            }
            if (!expected) problems += "seed=$seed attempt=$attempt mode=$mode: unexpected answer $outcome"
            val pending: Int = executor.pendingCalls
            if (pending != 1) problems += "seed=$seed attempt=$attempt mode=$mode: pendingCalls=$pending after the caller left (only the busy call may remain)"
        }
        releaseBusy.countDown()
        await(busyAnswered, "seed=$seed: the busy call's answer")
        val probe: NativeOutcome<Int> = runBlocking {
            withTimeoutOrNull(HANG_BOUND_MS.milliseconds) { executor.call(null) { 0 } } ?: fail("seed=$seed: hang bound: the FIFO probe never answered")
        }
        assertIs<NativeOutcome.Completed<Int>>(probe, "seed=$seed: control: the executor serves the next call")
        assertEquals(emptyList(), problems.take(10), "seed=$seed: ${problems.size} problem(s)")
        assertEquals(0, runs.get(), "seed=$seed: a call withdrawn by its limit or its caller's cancellation before it started ran later")
        assertEquals(0, executor.pendingCalls, "seed=$seed: nothing may be left queued or running")
        assertEquals(ATTEMPTS, dispatcher.armed.get(), "seed=$seed: control: every attempt armed its limit")
    }

    private companion object {
        const val HANG_BOUND_MS: Long = 30_000
        const val SEED: Int = 20261534
        const val ATTEMPTS: Int = 3000
    }
}

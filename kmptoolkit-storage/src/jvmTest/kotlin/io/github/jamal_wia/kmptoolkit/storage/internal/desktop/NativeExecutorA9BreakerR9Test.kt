package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Group sto-desktop, phase A breaker round 9 (decisions.md D-23), part A9: the round-10 withdrawal of a queued native call at the
 * instant its caller is cancelled or its limit passes (`NativeExecutor.withdrawOnCancelling`, `invokeOnCompletion(onCancelling)`,
 * registered on the caller's job before the offer and on the limit's scope inside `withTimeoutOrNull`), attacked window by window.
 *
 * KDoc under test (`NativeExecutor`): "A call that has not started when its limit passes (or its caller is cancelled) is withdrawn
 * from the queue and never runs, so a timed-out write is never applied later. The withdrawal happens at the instant of the
 * cancellation or of the limit, in the thread that cancels"; "The cancellation handler is registered before the call is queued, so
 * there is no moment at which a call is queued and its caller's cancellation is not watched"; "A value is never dropped".
 *
 * - **W5 forced.** The executor's window table leaves one instant open "by reading": the limit passes between the offer and the
 *   registration of the watch on the limit's scope, and the native thread starts the call in between. The seam is on the caller's
 *   side, as in `NativeExecutorA6BreakerR6Test`: the caller's dispatcher is also its coroutine `Delay`, and `withTimeoutOrNull`
 *   calls its `invokeOnTimeout` on the caller's thread after the offer and before its block (kotlinx 1.11 `Timeout.kt` `setupTimeout`).
 *   There the timer fires at once (the limit has passed: the call is still queued, it has not started), the native thread is let
 *   free, and only then does `invokeOnTimeout` return. A sentinel call queued behind the victim makes the wait end in every design:
 *   the native thread's next block, the victim's or the sentinel's, opens it.
 * - **Handler storm.** The handler runs "in whichever thread cancels". A seeded storm fires the caller cancellations and the limit
 *   timers (the very `Runnable` kotlinx gives the `Delay`, run as a timer thread would run it) from several real threads at once,
 *   together with `close()` and, in half of the rounds, with the end of the busy call. The callers' dispatchers are held, so no
 *   resumption can be what withdraws a call.
 * - **Re-entrant handler.** The handler runs on the native thread itself when a native block (or a `discard` handler) cancels
 *   another queued caller.
 * - **Registration released on the close path** (the round-10 retention test covers completed, limited and timed out).
 *
 * No sleeps, no wall clock; every wait is a latch or a join with a hard bound (a hang bound only); the storm's choices come from
 * `Random(seed)` and every message carries `seed=`.
 */
@OptIn(InternalCoroutinesApi::class)
class NativeExecutorA9BreakerR9Test {

    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()
    private val threads: MutableList<Thread> = CopyOnWriteArrayList()
    private val pools: MutableList<ExecutorService> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        threads.forEach { it.join(HANG_BOUND_MS) }
        pools.forEach { it.shutdownNow() }
    }

    private val factory: ThreadFactory = ThreadFactory { runnable: Runnable ->
        Thread(runnable, "breaker9-a9-native").apply {
            isDaemon = true
            threads += this
        }
    }

    private fun await(latch: CountDownLatch, what: String) {
        check(latch.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "hang bound: $what did not happen within ${HANG_BOUND_MS / 1000} s" }
    }

    /** A wait on the native thread that, like a real native call, does not end on the interrupt `close()` sends. */
    private fun awaitUninterruptibly(latch: CountDownLatch, what: String) {
        var interrupted = false
        try {
            while (true) {
                try {
                    await(latch, what)
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun <T> awaitDeferred(deferred: CompletableDeferred<T>, what: String): T = runBlocking {
        withTimeoutOrNull(HANG_BOUND_MS.milliseconds) { deferred.await() } ?: fail("hang bound: $what")
    }

    /** A caller's dispatcher on a thread of its own that is also the coroutine `Delay`; [onTimeout] is what arming a limit does. */
    private inner class TimerDispatcher(name: String, private val onTimeout: (Runnable) -> DisposableHandle) : CoroutineDispatcher(), Delay {
        private val pool: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }.also { pools += it }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pool.execute(block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            throw UnsupportedOperationException("delay() is not used by NativeExecutor.call")
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle = onTimeout(block)
    }

    /** A busy native thread: the call that keeps it busy runs until [end]. */
    private inner class Holder(executor: NativeExecutor) {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val answered: CompletableDeferred<NativeOutcome<Int>> = CompletableDeferred()

        init {
            CoroutineScope(Job() + Dispatchers.Default).launch {
                answered.complete(
                    executor.call(null) {
                        started.countDown()
                        awaitUninterruptibly(release, "the end of the busy call")
                        1
                    },
                )
            }
            await(started, "the start of the busy call")
        }

        fun end() = release.countDown()
    }

    // ---- W5: the limit passes between the offer and the watch on the limit's scope ----

    @Test
    fun `a limit that passes while the call is still queued never lets the call start after it, even before the limit watch is registered`() {
        val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
        val holder = Holder(executor)
        val nextBlock = CountDownLatch(1)
        val victimRuns = AtomicInteger()
        val victimRelease = CountDownLatch(1)
        val sentinelQueued = CountDownLatch(1)
        val runsAtExpiry = AtomicReference<Int?>(null)
        val sentinel: CompletableDeferred<NativeOutcome<Int>> = CompletableDeferred()
        // The sentinel's limit never fires; arming it tells the test that the sentinel has reached its limit (queued behind the victim).
        val sentinelDispatcher = TimerDispatcher("breaker9-a9-sentinel") { _: Runnable ->
            sentinelQueued.countDown()
            DisposableHandle { }
        }
        val victimDispatcher = TimerDispatcher("breaker9-a9-victim") { timeout: Runnable ->
            // kotlinx arms the victim's limit here: after the offer, before the block that registers the watch. The limit passes NOW,
            // as a timer thread would make it pass; the victim has not started (the native thread is busy with the holder).
            timeout.run()
            runsAtExpiry.set(victimRuns.get())
            // Let the native thread become free while this caller is still between the expiry and its watch.
            CoroutineScope(Job() + sentinelDispatcher).launch {
                sentinel.complete(
                    executor.call(30.seconds) {
                        nextBlock.countDown()
                        2
                    },
                )
            }
            await(sentinelQueued, "the sentinel's arrival at its limit")
            holder.end()
            await(nextBlock, "the native thread's next block (the victim's or the sentinel's)")
            DisposableHandle { }
        }
        val left: CompletableDeferred<Any?> = CompletableDeferred()
        CoroutineScope(Job() + victimDispatcher).launch {
            try {
                left.complete(
                    executor.call(NativeExecutor.NON_INTERACTIVE_LIMIT) {
                        victimRuns.incrementAndGet()
                        nextBlock.countDown()
                        await(victimRelease, "the release of the victim's block")
                        3
                    },
                )
            } catch (escaped: Throwable) {
                left.complete(escaped)
            }
        }
        val outcome: Any?
        try {
            outcome = awaitDeferred(left, "the victim never left call()")
        } finally {
            victimRelease.countDown()
            holder.end()
        }
        assertEquals(0, runsAtExpiry.get(), "control: the limit passed while the victim's call had not started")
        assertEquals(1, assertIs<NativeOutcome.Completed<Int>>(awaitDeferred(holder.answered, "the holder's answer")).value, "control: the holder answers")
        assertEquals(2, assertIs<NativeOutcome.Completed<Int>>(awaitDeferred(sentinel, "the sentinel's answer")).value, "control: the sentinel answers")
        assertEquals(
            0,
            victimRuns.get(),
            "KDoc: 'a call that has not started when its limit passes ... is withdrawn from the queue and never runs, so a timed-out " +
                "write is never applied later'. The limit passed while this call was still queued; the native thread then started it in " +
                "the window between the offer and the registration of the watch on the limit's scope (round-10 window W5), and the " +
                "caller was answered $outcome while its call ran",
        )
        assertEquals(NativeOutcome.TimedOut, outcome, "the victim's caller must be told TimedOut")
        assertEquals(0, executor.pendingCalls, "nothing may be left behind")
    }

    // ---- the handler on any thread: a seeded storm of cancellations, timers, close and the end of the busy call ----

    /**
     * A caller dispatcher whose dispatches are parked while [held] (released to a shared pool) and which, as the coroutine `Delay`,
     * records the limit's timer instead of arming it: the storm fires it from a thread of its choice.
     */
    private class StormGate(private val pool: ExecutorService) : CoroutineDispatcher(), Delay {
        private var held: Boolean = true
        private val parked: ArrayDeque<Runnable> = ArrayDeque()
        val timers: MutableList<Runnable> = CopyOnWriteArrayList()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (held) {
                    parked.addLast(block)
                    return
                }
            }
            pool.execute(block)
        }

        /** Runs what is parked on the calling thread (the start of a call, which runs until the call waits). */
        fun runParkedHere() {
            while (true) {
                val block: Runnable = synchronized(this) { parked.removeFirstOrNull() } ?: return
                block.run()
            }
        }

        fun parkedCount(): Int = synchronized(this) { parked.size }

        fun release() {
            val blocks: List<Runnable> = synchronized(this) {
                held = false
                parked.toList().also { parked.clear() }
            }
            blocks.forEach(pool::execute)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            throw UnsupportedOperationException("delay() is not used by NativeExecutor.call")
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            timers += block
            return DisposableHandle { }
        }
    }

    private class StormCaller(val index: Int, val limited: Boolean, val cancel: Boolean, val fire: Boolean) {
        val gateRef: AtomicReference<StormGate> = AtomicReference()
        val job: CompletableJob = Job()
        val runs: AtomicInteger = AtomicInteger()
        val discards: AtomicInteger = AtomicInteger()
        val answered: CompletableDeferred<Result<NativeOutcome<Int>>> = CompletableDeferred()
        val value: Int get() = 100 + index
        val victim: Boolean get() = cancel || fire
    }

    @Test
    fun `a seeded storm of cancellations and limits fired from many threads with close and the end of the busy call never runs a withdrawn call and loses nothing`() {
        val random = Random(SEED)
        val pool: ExecutorService = Executors.newFixedThreadPool(4) { runnable: Runnable ->
            Thread(runnable, "breaker9-a9-gates").apply { isDaemon = true }
        }.also { pools += it }
        var victimsWithdrawnAtOnce = 0
        var survivorsRun = 0
        repeat(ROUNDS) { round: Int ->
            val where = "seed=$SEED round=$round"
            val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
            val holder = Holder(executor)
            val concurrentEnd: Boolean = random.nextBoolean()
            val closeInRound: Boolean = random.nextInt(4) == 0
            val callers: List<StormCaller> = List(1 + random.nextInt(MAX_CALLERS)) { index: Int ->
                val limited: Boolean = random.nextBoolean()
                val choice: Int = random.nextInt(4) // 0 survivor, 1 cancel, 2 fire (limited only), 3 both
                StormCaller(index, limited, cancel = choice == 1 || choice == 3, fire = limited && (choice == 2 || choice == 3))
            }
            for (caller: StormCaller in callers) {
                val gate = StormGate(pool)
                caller.gateRef.set(gate)
                val limit: Duration? = if (caller.limited) NativeExecutor.NON_INTERACTIVE_LIMIT else null
                val body: suspend () -> NativeOutcome<Int> = {
                    executor.call(limit, { _: Int -> caller.discards.incrementAndGet() }) {
                        caller.runs.incrementAndGet()
                        caller.value
                    }
                }
                body.startCoroutine(Continuation(gate + caller.job) { result: Result<NativeOutcome<Int>> -> caller.answered.complete(result) })
                gate.runParkedHere()
                assertEquals(if (caller.limited) 1 else 0, gate.timers.size, "$where: control: the limit of caller ${caller.index} is armed")
            }
            assertEquals(1 + callers.size, executor.pendingCalls, "$where: control: every call is queued behind the busy one")

            // The actions, spread over threads in a seeded order; each runs in the thread that cancels, as a timer thread would.
            val actions: MutableList<Pair<String, () -> Unit>> = mutableListOf()
            for (caller: StormCaller in callers) {
                if (caller.cancel) actions += "cancel ${caller.index}" to { caller.job.cancel() }
                if (caller.fire) actions += "fire ${caller.index}" to { caller.gateRef.get().timers.single().run() }
            }
            if (closeInRound) actions += "close" to { executor.close() }
            if (concurrentEnd) actions += "end of the busy call" to { holder.end() }
            actions.shuffle(random)
            val threadCount: Int = 1 + random.nextInt(MAX_FIRING_THREADS)
            val perThread: List<MutableList<Pair<String, () -> Unit>>> = List(threadCount) { mutableListOf() }
            actions.forEach { action: Pair<String, () -> Unit> -> perThread[random.nextInt(threadCount)] += action }
            val go = CountDownLatch(1)
            val failures: MutableList<Throwable> = CopyOnWriteArrayList()
            val firing: List<Thread> = perThread.mapIndexed { number: Int, list: List<Pair<String, () -> Unit>> ->
                Thread({
                    try {
                        await(go, "the start signal")
                        list.forEach { (_: String, act: () -> Unit) -> act() }
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }, "breaker9-a9-firing-$number").apply {
                    isDaemon = true
                    start()
                }
            }
            go.countDown()
            firing.forEach { thread: Thread ->
                thread.join(HANG_BOUND_MS)
                check(!thread.isAlive) { "$where: hang bound: a cancelling thread is stuck in ${thread.name}: ${thread.stackTrace.joinToString(" <- ")}" }
            }
            assertEquals(emptyList(), failures, "$where: a cancellation, a timer or close threw in the cancelling thread")

            if (!concurrentEnd) {
                // Every cancellation and timer has returned and no resumption has run (the gates are held): the queue must hold
                // exactly the busy call and the calls of the callers still waiting, because the withdrawal happens in the cancelling
                // thread, at that instant.
                val expected: Int = if (closeInRound) 1 else 1 + callers.count { !it.victim }
                assertEquals(expected, executor.pendingCalls, "$where: a call whose caller was cancelled or whose limit passed is still queued (actions ${actions.map { it.first }})")
                victimsWithdrawnAtOnce += callers.count { it.victim }
                holder.end()
            }
            assertEquals(1, assertIs<NativeOutcome.Completed<Int>>(awaitDeferred(holder.answered, "$where: the busy call's answer")).value)
            if (!executor.isClosed) {
                // One thread, FIFO: once this answers, the native thread has passed every call queued before it.
                val flush: NativeOutcome<Int> = runBlocking(Dispatchers.Default) { executor.call(null) { 0 } }
                assertIs<NativeOutcome.Completed<Int>>(flush, "$where: control: the executor still serves calls")
            }
            callers.forEach { it.gateRef.get().release() }

            for (caller: StormCaller in callers) {
                val who = "$where caller=${caller.index} limited=${caller.limited} cancel=${caller.cancel} fire=${caller.fire} " +
                    "closed=$closeInRound concurrentEnd=$concurrentEnd actions=${actions.map { it.first }}"
                val answer: Result<NativeOutcome<Int>> = awaitDeferred(caller.answered, "$who: the caller never left call()")
                val outcome: NativeOutcome<Int>? = answer.getOrNull()
                val completed: Boolean = outcome is NativeOutcome.Completed<Int>
                assertTrue(caller.runs.get() <= 1, "$who: the call ran ${caller.runs.get()} times")
                if (completed) assertEquals(caller.value, (outcome as NativeOutcome.Completed<Int>).value, "$who: another value")
                // A value is never dropped: a call that ran reaches its caller as Completed or discard, once, never both.
                if (caller.runs.get() == 1) {
                    assertEquals(if (completed) 0 else 1, caller.discards.get(), "$who: the value of a call that ran (answer $answer)")
                } else {
                    assertEquals(0, caller.discards.get(), "$who: a call that never ran has no value to discard")
                    assertTrue(!completed, "$who: a caller answered Completed for a call that never ran")
                }
                if (caller.victim) {
                    assertTrue(!completed || concurrentEnd, "$who: a caller that was cancelled or timed out was answered Completed")
                    if (!concurrentEnd) {
                        assertEquals(0, caller.runs.get(), "$who: KDoc 'withdrawn ... and never runs': the call of a caller that left while it was queued ran")
                    }
                    val allowed: Boolean = answer.exceptionOrNull() is CancellationException || outcome == NativeOutcome.TimedOut ||
                        completed || (closeInRound && outcome is NativeOutcome.Threw)
                    assertTrue(allowed, "$who: answer $answer")
                    if (caller.cancel && !caller.fire && !concurrentEnd) {
                        assertIs<CancellationException>(answer.exceptionOrNull(), "$who: a cancelled caller gets its CancellationException: $answer")
                    }
                    if (caller.fire && !caller.cancel && !concurrentEnd && !closeInRound) {
                        assertEquals(NativeOutcome.TimedOut, outcome, "$who: a caller whose limit passed while queued is told TimedOut")
                    }
                } else if (!closeInRound) {
                    assertEquals(1, caller.runs.get(), "$who: a caller that stayed has its call run exactly once")
                    assertTrue(completed, "$who: answer $answer")
                    survivorsRun++
                } else {
                    assertTrue(
                        (completed && caller.runs.get() == 1) || (outcome is NativeOutcome.Threw && caller.runs.get() == 0),
                        "$who: after close a waiting caller is refused (Threw) or its call, taken before close, answers: $answer",
                    )
                }
                assertEquals(0, caller.gateRef.get().parkedCount(), "$who: a resumption is still parked")
            }
            executor.close()
            assertEquals(0, executor.pendingCalls, "$where: something was left in the queue")
        }
        assertTrue(victimsWithdrawnAtOnce > 0 && survivorsRun > 0, "control: the storm met both kinds (seed=$SEED): $victimsWithdrawnAtOnce, $survivorsRun")
    }

    // ---- the handler on the native thread itself ----

    @Test
    fun `a native block and a discard handler that cancel other queued callers run the handler on the native thread without a deadlock`() {
        val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
        val holder = Holder(executor)
        val gatePool: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, "breaker9-a9-reentrant").apply { isDaemon = true }
        }.also { pools += it }
        val runs = AtomicInteger()
        val first = StormCaller(0, limited = false, cancel = true, fire = false)
        val second = StormCaller(1, limited = true, cancel = true, fire = false)
        val canceller = StormCaller(2, limited = false, cancel = false, fire = false)
        val late = StormCaller(3, limited = false, cancel = false, fire = false)
        fun start(caller: StormCaller, limit: Duration?, discard: (Int) -> Unit, block: () -> Int) {
            val gate = StormGate(gatePool)
            caller.gateRef.set(gate)
            val body: suspend () -> NativeOutcome<Int> = { executor.call(limit, discard, block) }
            body.startCoroutine(Continuation(gate + caller.job) { result: Result<NativeOutcome<Int>> -> caller.answered.complete(result) })
            gate.runParkedHere()
        }
        // Queue: holder (running), canceller, first, late, second. The canceller's block cancels `first` from the native thread; the
        // late call is abandoned by its caller while it runs, and its discard handler (on the native thread) cancels `second`.
        val lateEntered = CountDownLatch(1)
        val lateRelease = CountDownLatch(1)
        start(canceller, null, { _: Int -> }) {
            first.job.cancel()
            10
        }
        start(first, null, { _: Int -> }) {
            runs.incrementAndGet()
            11
        }
        start(late, null, { _: Int -> second.job.cancel() }) {
            lateEntered.countDown()
            await(lateRelease, "the release of the late call")
            12
        }
        start(second, NativeExecutor.NON_INTERACTIVE_LIMIT, { _: Int -> }) {
            runs.incrementAndGet()
            13
        }
        assertEquals(5, executor.pendingCalls, "control: four calls queued behind the busy one")
        holder.end()
        await(lateEntered, "the late call's start")
        assertEquals(2, executor.pendingCalls, "the call whose caller the native thread cancelled must have left the queue at once (late running, second queued)")
        // The late caller leaves while its call runs (its abandon happens on its own thread), so the late value goes to discard on
        // the native thread, and that discard handler cancels `second`, which is still queued.
        late.job.cancel()
        late.gateRef.get().release()
        assertIs<CancellationException>(awaitDeferred(late.answered, "late").exceptionOrNull(), "the late caller was cancelled")
        lateRelease.countDown()
        val flush: NativeOutcome<Int> = runBlocking(Dispatchers.Default) { executor.call(null) { 0 } }
        assertIs<NativeOutcome.Completed<Int>>(flush, "control: the native thread survived")
        listOf(canceller, first, second).forEach { it.gateRef.get().release() }
        assertEquals(10, assertIs<NativeOutcome.Completed<Int>>(awaitDeferred(canceller.answered, "canceller").getOrThrow()).value)
        assertIs<CancellationException>(awaitDeferred(first.answered, "first").exceptionOrNull(), "the first caller was cancelled")
        assertIs<CancellationException>(awaitDeferred(second.answered, "second").exceptionOrNull(), "the second caller was cancelled")
        assertEquals(0, runs.get(), "a call whose caller was cancelled from the native thread while it was queued ran")
        assertEquals(0, executor.pendingCalls)
    }

    // ---- the caller-job registration on the close path ----

    private class Payload

    @Test
    fun `the watch on a long-lived caller job is released when close refuses the queued call`() {
        val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
        val holder = Holder(executor)
        val longLived: CompletableJob = Job()
        val (reference: WeakReference<Payload>, answered: CompletableDeferred<Result<NativeOutcome<Int>>>) = refusedCall(executor, longLived)
        assertEquals(2, executor.pendingCalls, "control: the call is queued behind the busy one")
        executor.close()
        val outcome: NativeOutcome<Int> = awaitDeferred(answered, "the refused caller's answer").getOrThrow()
        assertIs<NativeOutcome.Threw>(outcome, "control: close refuses the queued call")
        assertTrue(longLived.isActive, "control: the long-lived job is still active")
        var attempts = 0
        while (reference.get() != null && attempts < GC_ATTEMPTS) {
            System.gc()
            attempts++
        }
        assertEquals(null, reference.get(), "the long-lived caller job still holds the refused call (its watch was not released)")
        holder.end()
        awaitDeferred(holder.answered, "the holder's answer")
        longLived.cancel()
    }

    private fun refusedCall(executor: NativeExecutor, job: Job): Pair<WeakReference<Payload>, CompletableDeferred<Result<NativeOutcome<Int>>>> {
        val payload = Payload()
        val answered: CompletableDeferred<Result<NativeOutcome<Int>>> = CompletableDeferred()
        val gatePool: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, "breaker9-a9-retention").apply { isDaemon = true }
        }.also { pools += it }
        val gate = StormGate(gatePool)
        val body: suspend () -> NativeOutcome<Int> = { executor.call(null) { payload.hashCode() } }
        body.startCoroutine(Continuation(gate + job) { result: Result<NativeOutcome<Int>> -> answered.complete(result) })
        // The start runs here until the call waits (queued behind the busy call); later resumptions go to the pool.
        gate.runParkedHere()
        gate.release()
        return WeakReference(payload) to answered
    }

    private companion object {
        const val HANG_BOUND_MS: Long = 60_000
        const val SEED: Long = 20261533L
        const val ROUNDS: Int = 500
        const val MAX_CALLERS: Int = 10
        const val MAX_FIRING_THREADS: Int = 4
        const val GC_ATTEMPTS: Int = 100
    }
}

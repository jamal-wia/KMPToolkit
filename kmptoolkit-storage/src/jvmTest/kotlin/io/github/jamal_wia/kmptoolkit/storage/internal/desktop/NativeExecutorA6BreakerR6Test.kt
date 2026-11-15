package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentHashMap
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Group sto-desktop, phase A breaker round 6 (decisions.md D-23), part A6: the round-7 fix of [NativeExecutor] (10e06bc7:
 * "a value a call returned that does not reach its caller as Completed reaches discard exactly once"; the catch in `call`
 * widened from `CancellationException` to `Throwable`) attacked where the round-7 audit says "by reading only":
 *
 * - **Audit row 7, made injectable.** A caller that leaves `call` by a Throwable that is not a cancellation. The seam is on the
 *   caller's side, not in production code: the caller runs on a dispatcher that is also the coroutine `Delay`, and its
 *   `invokeOnTimeout` (which `withTimeoutOrNull` calls on the caller's thread) throws an `Error`. Since round 11 (A9-m1: a limited
 *   call is queued inside its limit's scope, after the watch on that scope) the timer is armed BEFORE the call is queued, so the
 *   Error leaves `call` before anything is queued. Test 1 keeps the task-queued shape (an executor whose native thread is busy);
 *   tests 2 and 3 were written for the old order (the Error thrown after the call started, after it finished) and were rewritten
 *   in round 11, as the lead ruled and an Opus advisor confirmed, to the same attack in the new order: on an IDLE executor (a task
 *   offered now would run at once) and after an earlier call of the same caller finished: the call must never be queued, run or
 *   leak, nothing is discarded that was never produced, an earlier delivered value stays intact, the executor serves the next call,
 *   and the Error reaches the caller as the very same instance (neither swallowed nor wrapped nor turned into an outcome).
 * - **Per-value accounting under failing deliveries.** A seeded storm: callers with no limit, a 1 ms limit or a cancellation race
 *   delivery failures at random visits of the `beforeDelivery` seam (one, or two in a row, which kills the loop) and `close()`.
 *   Every value a block produced must end in exactly one place: handed to its caller as `Completed` (and still intact there),
 *   or handed to `discard` once (and wiped). Never both, never neither, never twice. (The round-5 storm counted deaths and
 *   answers, the round-2 storm counted values without delivery failures; this one counts values with them.)
 *
 * No sleeps; every wait is a latch or join with a hard bound (hang bounds only); the storm's choices come from `Random(seed)`
 * and every message carries `seed=`. The caller-side Error leaves the caller's `withTimeoutOrNull` child coroutine unfinished
 * (kotlinx never started its body), so those callers run in a scope of their own that the test never joins.
 */
@OptIn(InternalCoroutinesApi::class)
class NativeExecutorA6BreakerR6Test {

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
        Thread(runnable, "breaker6-a6-native").apply {
            isDaemon = true
            // An injected Error ends a native thread by design in the storm: do not let the JVM print it.
            setUncaughtExceptionHandler { _: Thread, _: Throwable -> }
            threads += this
        }
    }

    private class InjectedTimerError : Error("the caller's timer could not be armed (breaker A6 caller-side seam)")

    /**
     * The caller's dispatcher: dispatches to a thread of its own, and as the coroutine `Delay` of that context it is what
     * `withTimeoutOrNull` asks to arm the timer. [beforeThrow] runs on the caller's thread before the Error is thrown.
     */
    private inner class TimerFailingDispatcher(private val beforeThrow: () -> Unit) : CoroutineDispatcher(), Delay {
        private val pool: ExecutorService = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, "breaker6-a6-caller").apply { isDaemon = true }
        }.also { pools += it }
        val armed: AtomicInteger = AtomicInteger()

        /** The Error this dispatcher threw: what the caller must be handed back, the very same instance. */
        val thrown: AtomicReference<InjectedTimerError?> = AtomicReference(null)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pool.execute(block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            throw UnsupportedOperationException("delay() is not used by NativeExecutor.call")
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            armed.incrementAndGet()
            beforeThrow()
            throw InjectedTimerError().also { thrown.set(it) }
        }
    }

    /**
     * One native thread, FIFO: when this answers, every call queued before it has run. It runs on a loop of its own, never on the
     * failing `Delay`, so it can be used from inside the seam.
     */
    private fun fifoProbe(executor: NativeExecutor): NativeOutcome<Int> = runBlocking {
        withTimeoutOrNull(HANG_BOUND_MS.milliseconds) { executor.call(null) { 0 } } ?: fail("hang bound: the FIFO probe never answered")
    }

    private fun await(latch: CountDownLatch, what: String) {
        check(latch.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "hang bound: $what did not happen within ${HANG_BOUND_MS / 1000} s" }
    }

    private class Secret {
        val bytes: ByteArray = ByteArray(32) { 0x5A }
        val discarded: MutableList<ByteArray> = CopyOnWriteArrayList()
        val discard: (ByteArray) -> Unit = { value: ByteArray ->
            discarded += value
            value.fill(0)
        }
    }

    /** Starts one limited call on [dispatcher] in a scope the test never joins; answers what left `call` (or its outcome). */
    private fun callFromFailingTimer(
        executor: NativeExecutor,
        dispatcher: TimerFailingDispatcher,
        discard: (ByteArray) -> Unit,
        block: () -> ByteArray,
    ): CompletableDeferred<Any?> {
        val left = CompletableDeferred<Any?>()
        CoroutineScope(Job() + dispatcher).launch {
            try {
                left.complete(executor.call(30.seconds, discard, block))
            } catch (escaped: Throwable) {
                left.complete(escaped)
            }
        }
        return left
    }

    private fun leftWith(left: CompletableDeferred<Any?>): Any? = runBlocking {
        withTimeoutOrNull(HANG_BOUND_MS.milliseconds) { left.await() } ?: fail("hang bound: the caller never left call()")
    }

    @Test
    fun `a caller that leaves by an Error while its call is still queued withdraws it and the call never runs`() {
        val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        // Control: the native thread is busy, so the next call can only be queued when its caller leaves.
        val blocker: CompletableDeferred<Any?> = CompletableDeferred()
        CoroutineScope(Job() + Dispatchers.Default).launch {
            blocker.complete(executor.call<Int>(null) {
                blockerStarted.countDown()
                await(releaseBlocker, "the release of the blocker")
                1
            })
        }
        await(blockerStarted, "the blocker's start")

        val runs = AtomicInteger()
        val secret = Secret()
        val dispatcher = TimerFailingDispatcher(beforeThrow = {})
        val left: CompletableDeferred<Any?> = callFromFailingTimer(executor, dispatcher, secret.discard) {
            runs.incrementAndGet()
            secret.bytes
        }
        val escaped: Any? = leftWith(left)
        assertEquals(1, dispatcher.armed.get(), "control: the caller reached withTimeoutOrNull with its task queued")
        assertIs<InjectedTimerError>(escaped, "the Error must leave call() unchanged (neither swallowed nor turned into an outcome): $escaped")

        releaseBlocker.countDown()
        val blockerOutcome: Any? = leftWith(blocker)
        assertEquals(1, assertIs<NativeOutcome.Completed<*>>(blockerOutcome, "control: the blocker answers").value)
        // FIFO single thread: a call made after the blocker returned runs after anything still queued behind it.
        val after: NativeOutcome<Int> = runBlocking { executor.call(null) { 2 } }
        assertEquals(2, assertIs<NativeOutcome.Completed<Int>>(after, "control: the executor still serves calls").value)

        assertEquals(
            0,
            runs.get(),
            "KDoc: a call whose caller left before it started 'is withdrawn from the queue and never runs'; this one ran after " +
                "its caller had left by an Error (the catch in call() did not withdraw it)",
        )
        assertTrue(secret.discarded.isEmpty(), "a call that never ran has no value to discard: ${secret.discarded.size}")
        assertEquals(0, executor.pendingCalls, "nothing may be left queued")
    }

    @Test
    fun `a caller whose limit cannot be armed on an idle executor gets the Error unchanged and its call is never queued or run`() {
        val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
        // Control: the native thread exists and waits for work, so a task offered now would run at once.
        assertEquals(1, assertIs<NativeOutcome.Completed<Int>>(runBlocking { executor.call(null) { 1 } }, "control: the native thread is up").value)
        val nativeThread: Thread = threads.single()
        val runs = AtomicInteger()
        val secret = Secret()
        val seen = ArmingSeen(executor, runs)
        val dispatcher = TimerFailingDispatcher(beforeThrow = seen::record)

        val left: CompletableDeferred<Any?> = callFromFailingTimer(executor, dispatcher, secret.discard) {
            runs.incrementAndGet()
            secret.bytes
        }
        val escaped: Any? = leftWith(left)
        assertEquals(1, dispatcher.armed.get(), "control: the caller reached the arming of its limit")
        assertSame(
            dispatcher.thrown.get(),
            escaped,
            "the Error must leave call() as the same instance (neither swallowed, wrapped nor turned into an outcome): $escaped",
        )
        assertEquals(0, assertIs<NativeOutcome.Completed<Int>>(seen.probe.get(), "control: the FIFO probe answered").value)
        assertEquals(0, seen.pendingAtArming.get(), "nothing may be queued or running when the limit is armed")
        assertEquals(
            0,
            seen.runsAfterProbe.get(),
            "KDoc: a limit that cannot be armed leaves call() before anything is queued; the idle native thread ran the call " +
                "(a task offered before its limit was armed) before the caller had a limit",
        )

        val after: NativeOutcome<Int> = runBlocking { executor.call(null) { 3 } }
        assertEquals(3, assertIs<NativeOutcome.Completed<Int>>(after, "control: the executor still serves calls").value)
        assertEquals(0, runs.get(), "the call of a caller that left by an Error before its limit was armed must never run")
        assertTrue(secret.discarded.isEmpty(), "a call that never ran has no value to discard: ${secret.discarded.size}")
        assertTrue(secret.bytes.all { it == 0x5A.toByte() }, "the key was never produced by a call, so nothing may have wiped it")
        assertEquals(0, executor.pendingCalls, "nothing may be left queued or running")
        assertSame(nativeThread, threads.single(), "no replacement native thread may have been made")
        assertTrue(nativeThread.isAlive, "the native thread must outlive a caller's failed arming")
        executor.close()
        nativeThread.join(HANG_BOUND_MS)
        assertFalse(nativeThread.isAlive, "the native thread outlived close")
    }

    @Test
    fun `a caller that finished a call and then cannot arm its next limit keeps the finished value and has nothing discarded`() {
        val executor: NativeExecutor = NativeExecutor(factory).also { executors += it }
        val first = Secret()
        val secondRuns = AtomicInteger()
        val seen = ArmingSeen(executor, secondRuns)
        val dispatcher = TimerFailingDispatcher(beforeThrow = seen::record)
        val firstOutcome: CompletableDeferred<NativeOutcome<ByteArray>> = CompletableDeferred()
        val armedAfterFirst = AtomicInteger(-1)
        val left: CompletableDeferred<Any?> = CompletableDeferred()
        // One caller, one shared discard handler: a value misrouted by either call would show up in it.
        CoroutineScope(Job() + dispatcher).launch {
            try {
                firstOutcome.complete(executor.call(null, first.discard) { first.bytes })
                armedAfterFirst.set(dispatcher.armed.get())
                left.complete(executor.call(30.seconds, first.discard) { secondRuns.incrementAndGet(); ByteArray(32) { 0x33 } })
            } catch (escaped: Throwable) {
                left.complete(escaped)
            }
        }
        val escaped: Any? = leftWith(left)
        val firstValue: NativeOutcome<ByteArray> = runBlocking {
            withTimeoutOrNull(HANG_BOUND_MS.milliseconds) { firstOutcome.await() } ?: fail("hang bound: the first call never answered")
        }
        assertEquals(0, armedAfterFirst.get(), "control: the first call took the unlimited path and armed no timer")
        assertEquals(1, dispatcher.armed.get(), "control: the second call reached the arming of its limit")
        assertSame(dispatcher.thrown.get(), escaped, "the Error must leave call() as the same instance: $escaped")
        assertSame(first.bytes, assertIs<NativeOutcome.Completed<ByteArray>>(firstValue, "control: the first call answered").value)
        assertTrue(first.bytes.all { it == 0x5A.toByte() }, "the first call's delivered value must stay intact after the second call failed")
        assertEquals(0, assertIs<NativeOutcome.Completed<Int>>(seen.probe.get(), "control: the FIFO probe answered").value)
        assertEquals(0, seen.pendingAtArming.get(), "nothing may be queued or running when the second limit is armed")
        assertEquals(0, seen.runsAfterProbe.get(), "the second call ran although its limit could not be armed")

        val after: NativeOutcome<Int> = runBlocking { executor.call(null) { 4 } }
        assertEquals(4, assertIs<NativeOutcome.Completed<Int>>(after, "control: the executor still serves calls").value)
        assertEquals(0, secondRuns.get(), "the second call must never run")
        assertTrue(first.discarded.isEmpty(), "no value was left undelivered, so nothing may reach discard: ${first.discarded.size}")
        assertEquals(0, executor.pendingCalls, "nothing may be left queued or running")
        assertEquals(1, threads.size, "no replacement native thread may have been made")
        val nativeThread: Thread = threads.single()
        executor.close()
        nativeThread.join(HANG_BOUND_MS)
        assertFalse(nativeThread.isAlive, "the native thread outlived close")
    }

    /** What the caller's thread saw at the moment its limit was armed (recorded in the seam, asserted by the test, never inside it). */
    private inner class ArmingSeen(private val executor: NativeExecutor, private val runs: AtomicInteger) {
        val pendingAtArming: AtomicReference<Int?> = AtomicReference(null)
        val probe: AtomicReference<NativeOutcome<Int>?> = AtomicReference(null)
        val runsAfterProbe: AtomicReference<Int?> = AtomicReference(null)

        fun record() {
            pendingAtArming.set(executor.pendingCalls)
            // FIFO and one native thread: if the failing call had been offered already, it has run when the probe answers.
            probe.set(fifoProbe(executor))
            runsAfterProbe.set(runs.get())
        }
    }

    @Test
    fun `a seeded storm of failing deliveries, timeouts, cancellations and close hands every produced value to exactly one place`() {
        var totalProduced = 0
        var totalDelivered = 0
        var totalDiscarded = 0
        var totalDeliveryFailures = 0
        for (round in 0 until ROUNDS) {
            val random = Random(SEED * 1_000 + round)
            // Failures on the delivery seam: single visits (the loop survives) or two in a row (the loop dies), never three in a
            // row (a third failed settle leaves an unlimited caller to its own cancellation: documented, row 4 of the audit).
            val failOn: MutableSet<Int> = mutableSetOf()
            var visit = 1 + random.nextInt(2)
            while (visit < CALLERS * 2) {
                failOn += visit
                if (random.nextInt(3) == 0) failOn += visit + 1
                visit += 3 + random.nextInt(3)
            }
            val closeAt: Int = if (random.nextInt(100) < 35) random.nextInt(CALLERS) else Int.MAX_VALUE
            val kinds: List<Int> = List(CALLERS) { random.nextInt(4) }
            val context = "seed=$SEED round=$round closeAt=$closeAt failOn=$failOn kinds=$kinds"

            val seamVisits = AtomicInteger()
            val seamThrown = AtomicInteger()
            val executor: NativeExecutor = NativeExecutor(factory, beforeDelivery = {
                if (seamVisits.incrementAndGet() in failOn) {
                    seamThrown.incrementAndGet()
                    throw OutOfMemoryError("allocation failed while delivering (breaker A6 storm, $context)")
                }
            }).also { executors += it }
            // Every value a block made, by id; where it ended up, counted.
            val produced: MutableMap<Int, ByteArray> = ConcurrentHashMap()
            val delivered: MutableMap<Int, AtomicInteger> = ConcurrentHashMap()
            val discarded: MutableMap<Int, AtomicInteger> = ConcurrentHashMap()
            val intactWhenDelivered: MutableMap<Int, Boolean> = ConcurrentHashMap()
            val arrived = AtomicInteger()
            val problem = AtomicReference<String?>(null)
            val discard: (ByteArray) -> Unit = { value: ByteArray ->
                discarded.computeIfAbsent(value[0].toInt()) { AtomicInteger() }.incrementAndGet()
                value.fill(0)
            }

            runBlocking {
                val answered: Boolean = withTimeoutOrNull(HANG_BOUND_MS.milliseconds) {
                    val callers: List<Deferred<Unit>> = List(CALLERS) { id: Int ->
                        async(Dispatchers.Default) {
                            if (arrived.getAndIncrement() == closeAt) executor.close()
                            // The value is recorded in the same coroutine, with no suspension after call() returned: a
                            // cancellation that lands later cannot lose a value the executor already handed over.
                            val call: suspend () -> Unit = {
                                val outcome: NativeOutcome<ByteArray> =
                                    executor.call(if (kinds[id] == 1) 1.milliseconds else null, discard) {
                                        // Byte 0 names the call; the rest is a key that must never be seen wiped by its holder.
                                        val value = ByteArray(16) { index: Int -> if (index == 0) id.toByte() else KEY_BYTE }
                                        if (produced.putIfAbsent(id, value) != null) problem.compareAndSet(null, "$context: call $id ran twice")
                                        value
                                    }
                                if (outcome is NativeOutcome.Completed<ByteArray>) {
                                    val value: ByteArray = outcome.value
                                    val owner: Int = value[0].toInt()
                                    if (owner != id) problem.compareAndSet(null, "$context: call $id got call $owner's value")
                                    intactWhenDelivered[owner] = (1 until value.size).all { value[it] == KEY_BYTE }
                                    delivered.computeIfAbsent(owner) { AtomicInteger() }.incrementAndGet()
                                }
                            }
                            if (kinds[id] >= 2) {
                                // Cancelled at a random moment (kind 3 right away, kind 2 after a yield).
                                val inner: Deferred<Unit> = async { call() }
                                if (kinds[id] == 2) kotlinx.coroutines.yield()
                                inner.cancelAndJoin()
                            } else {
                                call()
                            }
                        }
                    }
                    callers.forEach { it.await() }
                    true
                } ?: false
                if (!answered) problem.compareAndSet(null, "$context: a caller never answered within ${HANG_BOUND_MS / 1000} s")
            }
            problem.get()?.let { fail(it) }
            // Quiescence: close, and every native thread of this round has ended (a running block has delivered or discarded).
            executor.close()
            for (thread: Thread in threads) {
                thread.join(HANG_BOUND_MS)
                assertFalse(thread.isAlive, "$context: ${thread.name} outlived close")
            }
            threads.clear()

            for ((id: Int, value: ByteArray) in produced) {
                val handedToCaller: Int = delivered[id]?.get() ?: 0
                val handedToDiscard: Int = discarded[id]?.get() ?: 0
                assertEquals(
                    1,
                    handedToCaller + handedToDiscard,
                    "$context: the value of call $id reached its caller $handedToCaller times and discard $handedToDiscard times; " +
                        "KDoc: a value that does not reach its caller as Completed reaches discard exactly once",
                )
                if (handedToCaller == 1) {
                    assertEquals(true, intactWhenDelivered[id], "$context: call $id was handed a value that was already wiped")
                    assertTrue((1 until value.size).all { value[it] == KEY_BYTE }, "$context: call $id's delivered value was wiped afterwards")
                } else {
                    assertTrue(value.all { it == 0.toByte() }, "$context: the discarded value of call $id was not wiped")
                }
            }
            for (id: Int in delivered.keys + discarded.keys) {
                assertTrue(id in produced, "$context: a value of call $id was handed out but never produced")
            }
            assertEquals(0, executor.pendingCalls, "$context: calls were left queued or running after close")
            totalProduced += produced.size
            totalDelivered += delivered.size
            totalDiscarded += discarded.size
            totalDeliveryFailures += seamThrown.get()
        }
        val totals = "produced=$totalProduced delivered=$totalDelivered discarded=$totalDiscarded deliveryFailures=$totalDeliveryFailures"
        println("seed=$SEED A6 storm totals: $totals")
        assertTrue(
            totalDelivered > 0 && totalDiscarded > 0 && totalDeliveryFailures > 0,
            "seed=$SEED control: the storm delivered nothing, discarded nothing or met no delivery failure: $totals",
        )
    }

    private companion object {
        const val SEED: Int = 20_261_130
        const val ROUNDS: Int = 120
        const val CALLERS: Int = 16
        const val KEY_BYTE: Byte = 0x4B

        /** Hang bounds only, never timing assertions. */
        const val HANG_BOUND_MS: Long = 30_000
    }
}

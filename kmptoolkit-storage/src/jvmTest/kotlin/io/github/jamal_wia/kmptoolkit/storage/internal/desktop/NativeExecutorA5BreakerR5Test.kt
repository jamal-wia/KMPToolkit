package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Group sto-desktop, phase A breaker round 5 (decisions.md D-23), part A5: the round-6 redesign of thread death in
 * [NativeExecutor] (a dying thread settles its call once more, forgets itself and hands the queue to a replacement; `call()`
 * re-checks the thread after its offer) attacked through the `beforeDelivery` seam, which the executor itself names as the
 * place an `OutOfMemoryError` comes from after a call returned:
 *
 * - the KDoc of `call`: "[discard] receives a result nobody will read", and 05: a late result "is discarded and the key bytes
 *   in it are wiped". A value whose delivery fails is read by nobody, whether its caller already left (abandoned while running)
 *   or is told `Threw`;
 * - a seeded storm of callers (unlimited, limited, cancelled) racing injected thread deaths, failed thread starts and `close()`:
 *   every call answers once (no wait forever without a limit), runs at most once, a call is refused as "closed" only when the
 *   executor was closed, no replacement storm (threads started stay within deaths + 2), and every thread ends after close.
 *
 * No sleeps; every wait has a hard bound; the storm's choices come from `Random(seed)` and every message carries `seed=`.
 */
class NativeExecutorA5BreakerR5Test {

    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()
    private val threads: MutableList<Thread> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        threads.forEach { it.join(HANG_BOUND_MS) }
    }

    /** Makes daemon threads, records how they died, and can make `start()` fail for chosen asks (a thread limit). */
    private inner class DeathFactory(private val startFailsOn: (Int) -> Boolean = { false }) : ThreadFactory {
        val asked: AtomicInteger = AtomicInteger()
        val started: AtomicInteger = AtomicInteger()
        val startFailures: AtomicInteger = AtomicInteger()
        val deaths: MutableList<Throwable> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread {
            val index: Int = asked.incrementAndGet()
            val failStart: Boolean = startFailsOn(index)
            val thread: Thread = object : Thread(runnable, "breaker5-a5-native-$index") {
                override fun start() {
                    if (failStart) {
                        startFailures.incrementAndGet()
                        throw OutOfMemoryError("unable to create native thread (breaker A5)")
                    }
                    super.start()
                    started.incrementAndGet()
                }
            }
            thread.isDaemon = true
            // The injected Error ends a thread by design: record it instead of letting the JVM print it.
            thread.setUncaughtExceptionHandler { _: Thread, error: Throwable -> deaths += error }
            threads += thread
            return thread
        }
    }

    /** Throws an [OutOfMemoryError] on the chosen visits of the executor's `beforeDelivery` seam. */
    private class DeliverySeam(private val failOn: Set<Int>) {
        val visits: AtomicInteger = AtomicInteger()
        val thrown: AtomicInteger = AtomicInteger()

        fun visit() {
            if (visits.incrementAndGet() in failOn) {
                thrown.incrementAndGet()
                throw InjectedDeliveryFailure()
            }
        }
    }

    private class InjectedDeliveryFailure : OutOfMemoryError("allocation failed while delivering (breaker A5 seam)")

    private fun executorOf(factory: ThreadFactory, seam: DeliverySeam): NativeExecutor =
        NativeExecutor(factory, beforeDelivery = seam::visit).also { executors += it }

    @Test
    fun `a late result whose delivery fails after its caller left is still handed to discard and wiped`() = runBlocking {
        val seam = DeliverySeam(failOn = setOf(1))
        val factory = DeathFactory()
        val executor: NativeExecutor = executorOf(factory, seam)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secret = ByteArray(32) { 0x5A }
        val discarded: MutableList<ByteArray> = CopyOnWriteArrayList()

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val caller: Deferred<NativeOutcome<ByteArray>> = async(start = CoroutineStart.UNDISPATCHED) {
                executor.call(null, discard = { value: ByteArray -> discarded += value; value.fill(0) }) {
                    started.countDown()
                    check(release.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the call" }
                    secret
                }
            }
            check(started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the call never started" }
            // The caller leaves while its call runs: its result now belongs to nobody but `discard`.
            caller.cancelAndJoin()
            release.countDown()
            // One thread, first in first out: when this call answers, the first call's delivery has been handled.
            assertIs<NativeOutcome.Completed<Int>>(executor.call<Int>(null) { 1 }, "control: the executor still serves calls")
        }

        assertEquals(1, seam.thrown.get(), "control: the delivery of the abandoned result failed once (the seam threw)")
        assertEquals(0, factory.deaths.size, "control: one failed delivery does not kill the thread")
        assertEquals(
            1,
            discarded.size,
            "KDoc of call: 'discard receives a result nobody will read'; 05: a late result 'is discarded and the key bytes in it are " +
                "wiped'. The caller had left and the delivery failed, so the 32-byte result was dropped without reaching discard",
        )
        assertTrue(secret.all { it == 0.toByte() }, "the abandoned key bytes were never wiped")
    }

    @Test
    fun `a result whose delivery fails while its caller waits is handed to discard when the caller is told Threw`() = runBlocking {
        val seam = DeliverySeam(failOn = setOf(1))
        val executor: NativeExecutor = executorOf(DeathFactory(), seam)
        val secret = ByteArray(32) { 0x3C }
        val discarded: MutableList<ByteArray> = CopyOnWriteArrayList()

        val outcome: NativeOutcome<ByteArray> = withTimeout(HANG_BOUND_SECONDS.seconds) {
            executor.call(null, discard = { value: ByteArray -> discarded += value; value.fill(0) }) { secret }
        }

        val threw: NativeOutcome.Threw = assertIs<NativeOutcome.Threw>(outcome, "control: the caller is told the delivery failed")
        assertIs<InjectedDeliveryFailure>(threw.cause, "control: the failure is the injected one")
        assertEquals(
            1,
            discarded.size,
            "the call produced a key but its caller got Threw: nobody will ever read that value, so it must reach discard " +
                "('discard receives a result nobody will read'); it was dropped unwiped",
        )
        assertTrue(secret.all { it == 0.toByte() }, "the undelivered key bytes were never wiped")
    }

    @Test
    fun `a seeded storm of callers racing thread deaths, failed thread starts and close answers every call once with no stray threads`() {
        var totalDeaths = 0
        var totalStartFailures = 0
        var totalRefusedAsClosed = 0
        var totalCompleted = 0
        var totalDeliveryFailures = 0
        for (round in 0 until ROUNDS) {
            val random = Random(SEED * 1_000 + round)
            // Failure groups on the delivery seam: one visit (the thread survives) or two in a row (the loop dies), always at least
            // four visits apart, so the death handler's own settle never fails (three failures in a row are a different question).
            val failOn: MutableSet<Int> = mutableSetOf()
            var visit = 1 + random.nextInt(3)
            while (visit < CALLERS * 2) {
                failOn += visit
                if (random.nextBoolean()) failOn += visit + 1
                visit += 4 + random.nextInt(4)
            }
            val startFails: Set<Int> = (2..CALLERS).filter { random.nextInt(100) < 15 }.toSet()
            val closeAt: Int = if (random.nextInt(100) < 30) Int.MAX_VALUE else random.nextInt(CALLERS)
            val kinds: List<Int> = List(CALLERS) { random.nextInt(3) }
            val cancels: List<Boolean> = List(CALLERS) { random.nextBoolean() }
            val context = "seed=$SEED round=$round closeAt=$closeAt failOn=$failOn startFails=$startFails"

            val seam = DeliverySeam(failOn)
            val factory = DeathFactory(startFailsOn = { index: Int -> index in startFails })
            val executor: NativeExecutor = executorOf(factory, seam)
            val runs: Array<AtomicInteger> = Array(CALLERS) { AtomicInteger() }
            val outcomes: MutableMap<Int, NativeOutcome<Int>> = ConcurrentHashMap()
            val closedBefore: MutableMap<Int, Boolean> = ConcurrentHashMap()
            val arrived = AtomicInteger()
            val failure = AtomicReference<String?>(null)

            runBlocking {
                val answered: Boolean = withTimeoutOrNull(HANG_BOUND_SECONDS.seconds) {
                    val callers: List<Deferred<Unit>> = List(CALLERS) { id: Int ->
                        async(Dispatchers.Default) {
                            if (arrived.getAndIncrement() == closeAt) executor.close()
                            closedBefore[id] = executor.isClosed
                            val call: suspend () -> NativeOutcome<Int> = {
                                executor.call(if (kinds[id] == 1) 1.milliseconds else null) {
                                    runs[id].incrementAndGet()
                                    id
                                }
                            }
                            if (kinds[id] == 2) {
                                // Cancelled at a random moment: the result may be withdrawn, discarded or delivered.
                                val inner: Deferred<NativeOutcome<Int>> = async { call() }
                                if (cancels[id]) inner.cancelAndJoin() else outcomes[id] = inner.await()
                            } else {
                                outcomes[id] = call()
                            }
                        }
                    }
                    callers.forEach { it.await() }
                    true
                } ?: false
                if (!answered) {
                    val missing: List<Int> = (0 until CALLERS).filter { it !in outcomes && kinds[it] != 2 }
                    failure.set("$context: callers $missing never answered within ${HANG_BOUND_SECONDS}s (a call waits forever)")
                }
            }
            failure.get()?.let { fail(it) }

            for ((id: Int, outcome: NativeOutcome<Int>) in outcomes) {
                assertTrue(runs[id].get() <= 1, "$context: call $id ran ${runs[id].get()} times")
                when (outcome) {
                    is NativeOutcome.Completed<Int> -> {
                        assertEquals(id, outcome.value, "$context: call $id got another call's result")
                        assertEquals(1, runs[id].get(), "$context: call $id was answered Completed but its block ran ${runs[id].get()} times")
                    }
                    is NativeOutcome.Threw -> {
                        val cause: Throwable = outcome.cause
                        if (cause is IllegalStateException) {
                            assertTrue(
                                closeAt != Int.MAX_VALUE,
                                "$context: call $id was refused as '${cause.message}' by an executor that was never closed",
                            )
                        } else {
                            assertIs<OutOfMemoryError>(cause, "$context: call $id threw something nothing injected: $cause")
                        }
                    }
                    NativeOutcome.TimedOut -> Unit
                    is NativeOutcome.NativeUnavailable -> fail("$context: call $id answered NativeUnavailable: $outcome")
                }
                if (closedBefore[id] == true) {
                    assertIs<NativeOutcome.Threw>(outcome, "$context: call $id was made on a closed executor and answered $outcome")
                    assertEquals(0, runs[id].get(), "$context: call $id was made on a closed executor and ran")
                }
            }
            totalCompleted += outcomes.values.count { it is NativeOutcome.Completed<*> }
            totalRefusedAsClosed += outcomes.values.count { (it as? NativeOutcome.Threw)?.cause is IllegalStateException }
            totalStartFailures += factory.startFailures.get()
            totalDeliveryFailures += seam.thrown.get()
            val deaths: Int = factory.deaths.count { it is InjectedDeliveryFailure }
            totalDeaths += deaths
            assertEquals(factory.deaths.size, deaths, "$context: a native thread died of something nothing injected: ${factory.deaths}")
            assertTrue(
                factory.started.get() <= deaths + 2,
                "$context: ${factory.started.get()} threads were started for $deaths deaths: a replacement storm",
            )

            executor.close()
            for (thread: Thread in threads) {
                thread.join(HANG_BOUND_MS)
                assertFalse(thread.isAlive, "$context: ${thread.name} outlived close")
            }
            assertEquals(0, executor.pendingCalls, "$context: calls were left queued or running after close")
            threads.clear()
        }
        val totals = "deaths=$totalDeaths startFailures=$totalStartFailures deliveryFailures=$totalDeliveryFailures " +
            "refusedAsClosed=$totalRefusedAsClosed completed=$totalCompleted"
        println("seed=$SEED storm totals: $totals")
        assertTrue(totalDeaths > 0 && totalStartFailures > 0 && totalDeliveryFailures > totalDeaths, "seed=$SEED control: the storm met no death, start failure or surviving delivery failure: $totals")
        assertTrue(totalRefusedAsClosed > 0 && totalCompleted > 0, "seed=$SEED control: the storm closed no executor in flight or completed nothing: $totals")
    }

    private companion object {
        const val SEED: Int = 20_261_129
        const val ROUNDS: Int = 120
        const val CALLERS: Int = 16

        /** Hang bounds only, never timing assertions. */
        const val HANG_BOUND_MS: Long = 30_000
        const val HANG_BOUND_SECONDS: Int = 30
    }
}

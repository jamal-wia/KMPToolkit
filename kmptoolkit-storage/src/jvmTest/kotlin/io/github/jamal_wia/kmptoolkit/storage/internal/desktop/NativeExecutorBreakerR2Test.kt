package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Breaker round 2 of group sto-desktop (phase A): the native executor after its rewrite (plan 4.11: "native calls run on
 * one dedicated daemon thread per process; a hung OS call makes later calls time out rather than pile up threads"; the
 * class KDoc: a withdrawn call never runs, a late result goes to `discard`).
 *
 * - The one native thread must outlive whatever a call leaves behind, not only what it throws: a call that restores the
 *   interrupt status of its thread (the standard idiom after catching `InterruptedException`) must not end the loop, or
 *   every later secure call of the process times out or (interactive) waits forever, with `started` still set.
 * - A thread that cannot be created (`OutOfMemoryError: unable to create native thread`, a process thread limit) must not
 *   wedge the executor for good, and the call that could not start must never run later (its caller already got an
 *   answer).
 * - Exactly once: every value a call produced reaches its caller or `discard`, never both and never neither, under limits
 *   so short that the timeout races the completion and cancellations at random points.
 *
 * Real threads, latches and hang bounds; `runBlocking` where the native thread really runs (a virtual-time scheduler that
 * is idle would fire a limit while the thread works). No sleeps, no clock reads.
 */
class NativeExecutorBreakerR2Test {

    private class CountingFactory(private val failFirst: Boolean = false) : ThreadFactory {
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()
        private val failed = AtomicBoolean(false)

        override fun newThread(runnable: Runnable): Thread {
            if (failFirst && failed.compareAndSet(false, true)) {
                throw OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached")
            }
            return Thread(runnable, "breaker2-native-${created.size}").apply {
                isDaemon = true
                created += this
            }
        }
    }

    private val executors: MutableList<Pair<NativeExecutor, CountingFactory>> = CopyOnWriteArrayList()

    private fun newExecutor(failFirst: Boolean = false): Pair<NativeExecutor, CountingFactory> {
        val factory = CountingFactory(failFirst)
        return (NativeExecutor(factory) to factory).also { executors += it }
    }

    @AfterTest
    fun closeExecutors() {
        executors.forEach { (executor, factory) ->
            executor.close()
            factory.created.forEach { it.join(HANG_BOUND_MS) }
        }
    }

    @Test
    fun `a call that leaves its thread interrupted does not end the native thread`() = runBlocking {
        val (executor, factory) = newExecutor()

        val first: NativeOutcome<Int> = executor.call(null) {
            // What a binding does after catching an InterruptedException of its own: restore the status and return.
            Thread.currentThread().interrupt()
            1
        }
        assertEquals(1, (first as? NativeOutcome.Completed<Int>)?.value, "control: the call itself completed")

        val next: NativeOutcome<Int>? = withTimeoutOrNull(FOLLOW_UP_BOUND) { executor.call(null) { 2 } }

        assertEquals(
            2,
            (next as? NativeOutcome.Completed<Int>)?.value,
            "an interactive call after a call that restored the interrupt status never ran: the native loop ended " +
                "(thread alive=${factory.created.single().isAlive}, closed=${executor.isClosed}, pending=${executor.pendingCalls})",
        )
        assertEquals(1, factory.created.size, "still one native thread")
    }

    @Test
    fun `a native thread that could not be created is created by the next call, and the call that failed never runs later`() =
        runBlocking {
            val (executor, factory) = newExecutor(failFirst = true)
            val firstRan = AtomicBoolean(false)

            val first: Result<NativeOutcome<Int>> = runCatching { executor.call(null) { firstRan.set(true); 1 } }
            assertFalse(first.getOrNull() is NativeOutcome.Completed<*>, "control: the first call could not run (got $first)")

            val next: NativeOutcome<Int>? = withTimeoutOrNull(FOLLOW_UP_BOUND) { executor.call(null) { 2 } }

            assertEquals(
                2,
                (next as? NativeOutcome.Completed<Int>)?.value,
                "after one failed thread start no thread is ever created again (threads=${factory.created.size}, " +
                    "pending=${executor.pendingCalls}): every later secure call times out or waits forever",
            )
            assertFalse(firstRan.get(), "the call whose caller was answered with a failure ran later anyway")
        }

    @Test
    fun `every produced value reaches its caller or discard exactly once under racing limits and cancellations`() = runBlocking {
        val seed = 20_261_115L
        val (executor, factory) = newExecutor()
        val produced: MutableSet<Int> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        val returned: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val discarded: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val unexpected: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val timedOut = AtomicInteger()
        withTimeout(STORM_BOUND) {
            (0 until WORKERS).map { worker: Int ->
                launch(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(STEPS) { step: Int ->
                        val token: Int = worker * 100_000 + step
                        val limit: Duration? = when (random.nextInt(3)) {
                            0 -> null
                            1 -> random.nextInt(1, 50).microseconds
                            else -> 30.seconds
                        }
                        val cancelAfterYields: Int = if (random.nextInt(3) == 0) random.nextInt(4) else -1
                        // A short-limit call that keeps running until its caller has stopped waiting: forces the
                        // RUNNING-abandoned path (late result to discard) without any sleep. Never with limit null.
                        val outlivesCaller: CountDownLatch? =
                            if (limit != null && limit < 1.seconds && random.nextBoolean()) CountDownLatch(1) else null
                        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try {
                                val outcome: NativeOutcome<Int> = executor.call(limit, discard = { value: Int -> discarded += value }) {
                                    outlivesCaller?.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
                                    produced += token
                                    token
                                }
                                when (outcome) {
                                    is NativeOutcome.Completed -> returned += outcome.value
                                    NativeOutcome.TimedOut -> timedOut.incrementAndGet()
                                    else -> unexpected += "seed=$seed w=$worker s=$step outcome $outcome"
                                }
                            } finally {
                                outlivesCaller?.countDown()
                            }
                        }
                        if (cancelAfterYields >= 0) {
                            repeat(cancelAfterYields) { yield() }
                            job.cancel()
                        }
                        job.join()
                    }
                }
            }.forEach { it.join() }
            // FIFO: once this call ran, every earlier call ran or was withdrawn, and every late result was discarded.
            executor.call(null) { Unit }
        }

        assertEquals(emptyList(), unexpected.take(5), "seed=$seed")
        val returnedSet: Set<Int> = returned.toSet()
        val discardedSet: Set<Int> = discarded.toSet()
        assertEquals(returned.size, returnedSet.size, "seed=$seed: a value was returned twice")
        assertEquals(discarded.size, discardedSet.size, "seed=$seed: a value was discarded twice")
        assertEquals(emptySet(), returnedSet intersect discardedSet, "seed=$seed: a value was both returned and discarded")
        assertEquals(emptySet(), produced - returnedSet - discardedSet, "seed=$seed: a produced value reached nobody (not wiped)")
        assertEquals(emptySet(), (returnedSet + discardedSet) - produced, "seed=$seed: a value nobody produced was delivered")
        assertTrue(returned.isNotEmpty() && discarded.isNotEmpty() && timedOut.get() > 0, "seed=$seed control: every path was taken " +
            "(returned=${returned.size}, discarded=${discarded.size}, timedOut=${timedOut.get()})")
        assertEquals(0, executor.pendingCalls, "seed=$seed: calls left behind")
        assertEquals(1, factory.created.size, "seed=$seed: more than one native thread")
    }

    @Test
    fun `the shared executor's thread is a daemon, so it never keeps the process alive`() = runBlocking {
        val executor = NativeExecutor()
        try {
            val daemon: NativeOutcome<Boolean> = executor.call(null) { Thread.currentThread().isDaemon }
            assertEquals(true, (daemon as? NativeOutcome.Completed<Boolean>)?.value)
        } finally {
            executor.close()
        }
    }

    private companion object {
        /** Hang bounds, never timing assertions. */
        const val HANG_BOUND_MS: Long = 60_000
        val FOLLOW_UP_BOUND: Duration = 30.seconds
        val STORM_BOUND: Duration = 120.seconds
        const val WORKERS: Int = 8
        const val STEPS: Int = 300
    }
}

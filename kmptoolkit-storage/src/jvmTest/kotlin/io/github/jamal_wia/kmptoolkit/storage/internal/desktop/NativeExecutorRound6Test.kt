package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 6 (phase A breaker round 4: m1 threads made after close, m3 thread death without close).
 * The breaker class `NativeExecutorBreakerR4Test` proves that a closed executor makes no thread; these tests pin the design
 * for a native thread that DIES while the executor is open (only an `Error` such as an `OutOfMemoryError` ends the loop,
 * so the `beforeDelivery` seam injects one where the executor allocates after a call returned):
 *
 * - the call the thread was running answers a typed outcome instead of waiting forever,
 * - calls queued behind it, and a call that arrives while the thread is dying, run on the replacement (they are not refused
 *   as "closed", the executor is open),
 * - a call that is offered after the dead thread's last look at the queue starts a thread itself,
 * - when no replacement can be made the queued calls answer [NativeOutcome.Threw] with that cause.
 *
 * Calls are queued deterministically: `async(start = UNDISPATCHED)` runs the call up to its first suspension on the test
 * thread, which is after its offer. No sleeps; every wait has a hard bound.
 */
class NativeExecutorRound6Test {

    /** Counts what was asked for and records how each native thread ended. */
    private class RecordingFactory(
        private val failFrom: Int = Int.MAX_VALUE,
        private val makeThread: (Runnable, Int) -> Thread = { runnable: Runnable, index: Int ->
            Thread(runnable, "round6-native-$index").apply { isDaemon = true }
        },
    ) : ThreadFactory {
        val asked: AtomicInteger = AtomicInteger()
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()
        val deaths: CopyOnWriteArrayList<Throwable> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread {
            val index: Int = asked.incrementAndGet()
            if (index >= failFrom) throw OutOfMemoryError("unable to create native thread (round 6)")
            return makeThread(runnable, index).apply {
                // The injected Error ends the thread by design: record it instead of letting the JVM print it.
                setUncaughtExceptionHandler { _: Thread, error: Throwable -> deaths += error }
                created += this
            }
        }
    }

    /** Throws an OutOfMemoryError from the first [failures] visits, and can park the thread on [hold] on a given visit. */
    private class DeathSeam(private val failures: Int, private val holdOnVisit: Int = 0) {
        val visits: AtomicInteger = AtomicInteger()
        val holding: CountDownLatch = CountDownLatch(1)
        val hold: CountDownLatch = CountDownLatch(1)

        fun visit() {
            val number: Int = visits.incrementAndGet()
            if (number == holdOnVisit) {
                holding.countDown()
                check(hold.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the dying thread" }
            }
            if (number <= failures) throw OutOfMemoryError("allocation failed while delivering (round 6 seam)")
        }
    }

    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()
    private val factories: MutableList<RecordingFactory> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        factories.forEach { factory: RecordingFactory -> factory.created.forEach { it.join(HANG_BOUND_MS) } }
    }

    private fun executorOf(factory: RecordingFactory, seam: DeathSeam? = null): NativeExecutor {
        factories += factory
        return NativeExecutor(factory, beforeDelivery = { seam?.visit() }).also { executors += it }
    }

    @Test
    fun `a thread that dies running a call answers that call, and the call queued behind it runs on a replacement`() = runBlocking {
        val factory = RecordingFactory()
        // Visits 1 and 2 fail: the delivery of the call fails, then so does the handling of that failure, so the loop dies.
        val executor: NativeExecutor = executorOf(factory, DeathSeam(failures = 2))
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                executor.call<Int>(null) {
                    started.countDown()
                    check(release.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the call" }
                    1
                }
            }
            check(started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the first call never started" }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { executor.call<Int>(null) { 2 } }
            assertEquals(2, executor.pendingCalls, "control: one call runs and one is queued behind it")

            release.countDown()

            assertIs<NativeOutcome.Threw>(running.await(), "the call the dying thread was running must answer, not wait forever")
            assertEquals(
                2,
                assertIs<NativeOutcome.Completed<Int>>(
                    queued.await(),
                    "the executor is open: a call queued behind the dead thread was refused instead of run by its replacement",
                ).value,
            )
        }
        assertFalse(executor.isClosed, "control: the executor was never closed")
        assertEquals(2, factory.created.size, "the dead thread was replaced by exactly one thread")
        factory.created.first().join(HANG_BOUND_MS)
        assertFalse(factory.created.first().isAlive, "control: the first thread is gone")
        assertEquals(1, factory.deaths.size, "control: the first thread died of the injected error: ${factory.deaths}")
        assertEquals(0, executor.pendingCalls)
    }

    @Test
    fun `a call that arrives while the thread is dying runs on the replacement`() = runBlocking {
        val factory = RecordingFactory()
        // Visit 3 is the second try to deliver the first call's failure, on the dying thread, which holds there: the thread is
        // alive, past its loop, and has not yet looked at the queue.
        val seam = DeathSeam(failures = 2, holdOnVisit = 3)
        val executor: NativeExecutor = executorOf(factory, seam)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                executor.call<Int>(null) {
                    started.countDown()
                    check(release.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the call" }
                    1
                }
            }
            check(started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the first call never started" }
            release.countDown()
            check(seam.holding.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the thread never reached its death" }
            assertTrue(factory.created.single().isAlive, "control: the dying thread is still alive when the second call arrives")

            val arriving = async(start = CoroutineStart.UNDISPATCHED) { executor.call<Int>(null) { 3 } }
            assertEquals(1, factory.asked.get(), "control: the arriving call saw a live thread and made no thread")
            seam.hold.countDown()

            assertIs<NativeOutcome.Threw>(first.await())
            assertEquals(3, assertIs<NativeOutcome.Completed<Int>>(arriving.await(), "a call that arrived while the thread died was stranded or refused").value)
        }
        assertEquals(2, factory.created.size)
        assertEquals(0, executor.pendingCalls)
    }

    @Test
    fun `calls queued behind a thread that dies answer Threw with the cause when no replacement can be made`() = runBlocking {
        val factory = RecordingFactory(failFrom = 2)
        val executor: NativeExecutor = executorOf(factory, DeathSeam(failures = 2))
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                executor.call<Int>(null) {
                    started.countDown()
                    check(release.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the call" }
                    1
                }
            }
            check(started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the first call never started" }
            val queued = List(3) { async(start = CoroutineStart.UNDISPATCHED) { executor.call<Int>(null) { it } } }

            release.countDown()

            assertIs<NativeOutcome.Threw>(running.await())
            queued.forEach { call ->
                val outcome: NativeOutcome<Int> = call.await()
                val threw: NativeOutcome.Threw = assertIs<NativeOutcome.Threw>(outcome, "a queued call must answer, not wait for a thread nobody makes")
                assertIs<OutOfMemoryError>(threw.cause, "the cause is the failed thread start, not a claim that the executor is closed")
            }
        }
        assertEquals(0, executor.pendingCalls)
        assertFalse(executor.isClosed)
    }

    @Test
    fun `a call offered after its thread already ended starts a thread itself instead of waiting for the next call`() = runBlocking {
        // The first thread ends at once, and its start() does not return before it is gone: when ensureStarted returns, the thread it
        // just made is already dead, as if the thread that call saw alive had ended before the offer.
        val factory = RecordingFactory(
            makeThread = { runnable: Runnable, index: Int ->
                if (index == 1) {
                    object : Thread({ }, "round6-dying") {
                        override fun start() {
                            super.start()
                            join(HANG_BOUND_MS)
                        }
                    }.apply { isDaemon = true }
                } else {
                    Thread(runnable, "round6-native-$index").apply { isDaemon = true }
                }
            },
        )
        val executor: NativeExecutor = executorOf(factory)

        val outcome: NativeOutcome<Int> = withTimeout(HANG_BOUND_SECONDS.seconds) { executor.call(null) { 5 } }

        assertEquals(5, assertIs<NativeOutcome.Completed<Int>>(outcome, "the call was queued with no thread and waited for some later call").value)
        assertEquals(2, factory.created.size, "the dead thread was replaced by exactly one thread")
    }

    @Test
    fun `a replacement that cannot be made for a call offered after its thread ended answers that call and leaves nothing queued`() = runBlocking {
        val factory = RecordingFactory(
            failFrom = 2,
            makeThread = { _: Runnable, _: Int ->
                object : Thread({ }, "round6-dying") {
                    override fun start() {
                        super.start()
                        join(HANG_BOUND_MS)
                    }
                }.apply { isDaemon = true }
            },
        )
        val executor: NativeExecutor = executorOf(factory)

        val outcome: NativeOutcome<Int> = withTimeout(HANG_BOUND_SECONDS.seconds) { executor.call(null) { 5 } }

        val threw: NativeOutcome.Threw = assertIs<NativeOutcome.Threw>(outcome)
        assertIs<OutOfMemoryError>(threw.cause)
        assertEquals(0, executor.pendingCalls, "a call that was told it failed stayed queued where a later thread could run it")
    }

    private companion object {
        /** Hang bounds only, never timing assertions. */
        const val HANG_BOUND_MS: Long = 30_000
        const val HANG_BOUND_SECONDS: Int = 30
    }
}

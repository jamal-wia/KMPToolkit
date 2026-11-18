package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Breaker round 1 of group sto-desktop (phase A): attacks on the native executor of plan 4.11 ("one dedicated daemon
 * thread per process; a hung OS call makes later calls time out rather than pile up threads").
 *
 * - A binding that hangs for good is the state the plan expects to last until the process ends; every later call then
 *   times out. Each withdrawn call must leave nothing behind (its closure carries the key bytes of the call), or the
 *   hung state grows memory without bound.
 * - Whatever a discard (wipe) handler throws must not end the one native thread: with the thread gone and `started`
 *   set, no new thread is made and an interactive call (no limit) waits forever.
 *
 * Virtual time for limits, latches for the native thread, hang bounds only.
 */
class NativeExecutorBreakerR1Test {

    private class CountingFactory : ThreadFactory {
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "breaker-native-${created.size}").apply {
                isDaemon = true
                // The thread dying of an uncaught error is the defect under test; keep the test log quiet about it.
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                created += this
            }
    }

    private val factory = CountingFactory()
    private val executor = NativeExecutor(factory)

    @AfterTest
    fun closeExecutor() {
        executor.close()
        factory.created.forEach { it.join(HANG_BOUND_MS) }
    }

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue(latch.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS), "timed out waiting for $what")
    }

    @Test
    fun `calls withdrawn behind a hung call leave the queue, so a binding hung for good cannot grow it without bound`() = runTest {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val hung = async(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Unit>(null) {
                started.countDown()
                await(release, "the test to release the hung call")
            }
        }
        await(started, "the hung call to start")
        assertEquals(1, executor.pendingCalls, "control: the hung call is running")

        repeat(WITHDRAWN_CALLS) { index: Int ->
            // What a real call captures: the key bytes it would protect or store.
            val secret = ByteArray(SECRET_BYTES) { index.toByte() }
            val outcome: NativeOutcome<Int> = executor.call(NativeExecutor.NON_INTERACTIVE_LIMIT) { secret.size }
            assertEquals(NativeOutcome.TimedOut, outcome, "call $index must time out behind the hung one")
        }

        assertEquals(
            1,
            executor.pendingCalls,
            "$WITHDRAWN_CALLS withdrawn calls (each holding its closure and key bytes) are still queued behind the hung call",
        )
        release.countDown()
        hung.await()
    }

    @Test
    fun `a discard handler that throws an Error does not end the native thread, so later calls still run`() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val discardRan = CountDownLatch(1)
        val abandoned = async(Dispatchers.Default) {
            executor.call<Int>(
                null,
                discard = { _: Int ->
                    discardRan.countDown()
                    throw AssertionError("a wipe handler failed (breaker test)")
                },
            ) {
                started.countDown()
                await(release, "the test to release the running call")
                1
            }
        }
        await(started, "the call to start")
        abandoned.cancelAndJoin() // the caller leaves; the running call's late result goes to discard
        release.countDown()
        await(discardRan, "the late result to reach discard")

        val next: NativeOutcome<Int>? = withTimeoutOrNull(FOLLOW_UP_BOUND) { executor.call(null) { 2 } }

        assertEquals(
            2,
            (next as? NativeOutcome.Completed<Int>)?.value,
            "an interactive call after a throwing discard never ran: the native thread is dead (alive=${factory.created.single().isAlive})",
        )
        assertEquals(1, factory.created.size, "still one native thread")
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_MS: Long = 60_000
        val FOLLOW_UP_BOUND = 30.seconds
        const val WITHDRAWN_CALLS: Int = 200
        const val SECRET_BYTES: Int = 4096
    }
}

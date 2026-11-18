package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 7 (breaker findings A5-m1 and B4-m1): a value that a native call returned and that is not handed
 * to its caller as `Completed` always reaches `discard`, so a key is never left unwiped on the heap.
 *
 * The audit of every path on which a `Completed` value can be dropped (the table is in the round-7 report) is pinned here for
 * the paths the breaker classes `NativeExecutorA5BreakerR5Test` (delivery fails while the caller waits or after it left) and
 * the older classes (limit passed, caller cancelled while the call runs, storm) do not name:
 *
 * - the delivery AND its retries fail (the loop dies; the death handler's own settle fails too): discarded once, never twice;
 * - the caller is cancelled in the instant between the delivery and the caller's wake-up: discarded by the caller's own path;
 * - `close()` while a call runs: the running call still answers its real result (nothing is dropped); a call that was only
 *   queued never runs, so there is no value to wipe;
 * - every case first proves that the secret existed (a control), then that it was wiped.
 *
 * No sleeps; every wait is a latch with a hard bound.
 */
class NativeExecutorRound7Test {

    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()
    private val threads: MutableList<Thread> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        threads.forEach { it.join(HANG_BOUND_MS) }
    }

    private val factory: ThreadFactory = ThreadFactory { runnable: Runnable ->
        Thread(runnable, "round7-native").apply {
            isDaemon = true
            // The injected Error ends a thread by design: do not let the JVM print it.
            setUncaughtExceptionHandler { _: Thread, _: Throwable -> }
            threads += this
        }
    }

    /** Throws an OutOfMemoryError from the first [failures] visits of the delivery seam; [thrown] counts down for each. */
    private class FailingSeam(private val failures: Int) {
        val visits: AtomicInteger = AtomicInteger()
        val thrown: CountDownLatch = CountDownLatch(failures)

        fun visit() {
            if (visits.incrementAndGet() <= failures) {
                thrown.countDown()
                throw OutOfMemoryError("allocation failed while delivering (round 7 seam)")
            }
        }
    }

    private fun executorOf(seam: FailingSeam? = null): NativeExecutor =
        NativeExecutor(factory, beforeDelivery = { seam?.visit() }).also { executors += it }

    private class Secret {
        val bytes: ByteArray = ByteArray(32) { 0x6B }
        val discarded: MutableList<ByteArray> = CopyOnWriteArrayList()
        val discard: (ByteArray) -> Unit = { value: ByteArray ->
            discarded += value
            value.fill(0)
        }

        fun assertExisted(message: String) = assertTrue(bytes.size == 32, "control: $message")

        fun assertWipedOnce(message: String) {
            assertEquals(1, discarded.size, "$message: the value must reach discard exactly once")
            assertTrue(discarded.single() === bytes, "$message: discard received another array than the one the call returned")
            assertTrue(bytes.all { it == 0.toByte() }, "$message: the key bytes were not wiped")
        }
    }

    @Test
    fun `a value whose delivery and both retries fail reaches discard once and the replacement serves the next call`() = runBlocking {
        val seam = FailingSeam(failures = 3)
        val executor: NativeExecutor = executorOf(seam)
        val secret = Secret()
        secret.assertExisted("the call returns a 32-byte key")

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val caller: Deferred<NativeOutcome<ByteArray>> =
                async(start = CoroutineStart.UNDISPATCHED) { executor.call(null, secret.discard) { secret.bytes } }
            check(seam.thrown.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the seam never failed three times" }
            threads.first().join(HANG_BOUND_MS)

            assertFalse(threads.first().isAlive, "control: the native thread died of the injected errors")
            secret.assertWipedOnce("three failed deliveries in a row (delivery, the loop's settle, the death handler's settle)")
            assertFalse(caller.isCompleted, "control: even a third failed settle leaves the caller to its own limit or cancellation")

            caller.cancelAndJoin()
            secret.assertWipedOnce("the caller's cancellation after the value was already discarded")
            assertEquals(
                7,
                assertIs<NativeOutcome.Completed<Int>>(executor.call<Int>(null) { 7 }, "the executor makes a replacement thread").value,
            )
        }
        assertEquals(0, executor.pendingCalls)
    }

    @Test
    fun `a value whose delivery and the loop's own settle fail is discarded once and its caller is told Threw by the death handler`() =
        runBlocking {
            val seam = FailingSeam(failures = 2)
            val executor: NativeExecutor = executorOf(seam)
            val secret = Secret()
            secret.assertExisted("the call returns a 32-byte key")

            val outcome: NativeOutcome<ByteArray> =
                withTimeout(HANG_BOUND_SECONDS.seconds) { executor.call(null, secret.discard) { secret.bytes } }

            assertIs<NativeOutcome.Threw>(outcome, "the death handler answers the call the thread was running")
            assertEquals(0L, seam.thrown.count, "control: both injected failures happened")
            secret.assertWipedOnce("two failed deliveries (the loop dies)")
        }

    @Test
    fun `a caller cancelled after the value was delivered but before it woke up discards the value itself`() = runBlocking {
        val executor: NativeExecutor = executorOf()
        val secret = Secret()
        secret.assertExisted("the call returns a 32-byte key")
        // The caller runs on its own single thread, which the test keeps busy while the call finishes: the delivery wakes the
        // caller, but its continuation cannot run until the test lets it, so the cancellation arrives in between.
        val callerThread = Executors.newSingleThreadExecutor { runnable: Runnable ->
            Thread(runnable, "round7-caller").apply { isDaemon = true }
        }
        val occupied = CountDownLatch(1)
        val free = CountDownLatch(1)
        val go = CountDownLatch(1)
        try {
            withTimeout(HANG_BOUND_SECONDS.seconds) {
                val caller: Deferred<NativeOutcome<ByteArray>> = async(callerThread.asCoroutineDispatcher(), CoroutineStart.UNDISPATCHED) {
                    executor.call(null, secret.discard) {
                        check(go.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the call" }
                        secret.bytes
                    }
                }
                callerThread.execute {
                    occupied.countDown()
                    check(free.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never freed the caller's thread" }
                }
                check(occupied.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the caller's thread was never occupied" }
                go.countDown()
                // FIFO on the single native thread: once this call answers, the first call's delivery is finished.
                withContext(Dispatchers.Default) {
                    assertIs<NativeOutcome.Completed<Int>>(executor.call<Int>(null) { 1 }, "control: the executor serves calls")
                }
                assertEquals(emptyList(), secret.discarded, "control: the value sits delivered, nobody discarded it yet")
                caller.cancel()
                free.countDown()
                try {
                    caller.await()
                    throw AssertionError("the cancelled caller returned a value instead of being cancelled")
                } catch (_: CancellationException) {
                    // Expected.
                }
            }
            secret.assertWipedOnce("a value delivered to a caller that was cancelled before it woke up")
        } finally {
            go.countDown()
            free.countDown()
            callerThread.shutdownNow()
        }
    }

    @Test
    fun `close while a call runs still delivers its real result and a call that never started has no value to wipe`() = runBlocking {
        val executor: NativeExecutor = executorOf()
        val secret = Secret()
        secret.assertExisted("the running call returns a 32-byte key")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRan = AtomicInteger()
        val queuedDiscards = AtomicInteger()

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val running: Deferred<NativeOutcome<ByteArray>> = async(start = CoroutineStart.UNDISPATCHED) {
                executor.call(null, secret.discard) {
                    started.countDown()
                    // close() interrupts the running thread; a native call that ignores the interrupt still returns its value.
                    awaitIgnoringInterrupts(release)
                    secret.bytes
                }
            }
            check(started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the call never started" }
            val queued: Deferred<NativeOutcome<ByteArray>> = async(start = CoroutineStart.UNDISPATCHED) {
                executor.call(null, { _: ByteArray -> queuedDiscards.incrementAndGet() }) {
                    queuedRan.incrementAndGet()
                    ByteArray(32)
                }
            }
            assertEquals(2, executor.pendingCalls, "control: one call runs and one is queued behind it")

            executor.close()
            release.countDown()

            assertIs<NativeOutcome.Threw>(queued.await(), "the queued call is refused by close")
            assertTrue(running.await() is NativeOutcome.Completed<*>, "the call that was running answers its real result")
            assertTrue((running.await() as NativeOutcome.Completed<ByteArray>).value === secret.bytes, "the real value is delivered")
        }
        assertEquals(0, queuedRan.get(), "a refused call never runs")
        assertEquals(0, queuedDiscards.get(), "a refused call produced no value to discard")
        assertEquals(emptyList(), secret.discarded, "a delivered value is the caller's, not discard's")
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                check(latch.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the test never released the call" }
                return
            } catch (_: InterruptedException) {
                // close() interrupted the thread: keep waiting, as a native call that does not react to interrupts would.
            }
        }
    }

    private companion object {
        const val HANG_BOUND_SECONDS: Int = 60
        const val HANG_BOUND_MS: Long = HANG_BOUND_SECONDS * 1_000L
    }
}

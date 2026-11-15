package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Breaker round 3 of group sto-desktop (phase A): the native executor after the round 4 fixes (interrupt status cleared
 * after every call, a failed thread start answered per call).
 *
 * - [NativeExecutor.call] answers every way a call can end as a [NativeOutcome] (its KDoc and the class KDoc: closed,
 *   timed out, unavailable, threw); only cancellation leaves it as an exception. A caller whose thread carries an interrupt
 *   status (a pooled worker that a previous task left interrupted, the standard "restore the status" idiom, or a caller
 *   that was interrupted) must still get an outcome, and its interrupt status is the caller's, not the executor's to
 *   consume. `LinkedBlockingQueue.put` takes its lock with `lockInterruptibly`, so it throws `InterruptedException` (and
 *   clears the status) before the call is even queued; the public path that reaches it without any file I/O first is
 *   `probeDesktopKeyStore()` (the probe's 1-byte round trip on the store dispatcher).
 * - A call that interrupts its own thread and then throws must leave the next call an uninterrupted thread.
 * - Repeated thread-creation failures make exactly one attempt per call: no retry loop, no hot loop creating threads.
 */
class NativeExecutorBreakerR3Test {

    private class CountingFactory(private val failures: Int) : ThreadFactory {
        val attempts: AtomicInteger = AtomicInteger()
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread {
            if (attempts.incrementAndGet() <= failures) throw OutOfMemoryError("unable to create native thread (breaker)")
            return Thread(runnable, "breaker-r3-native").apply {
                isDaemon = true
                created += this
            }
        }
    }

    private val executors: MutableList<Pair<NativeExecutor, CountingFactory>> = CopyOnWriteArrayList()

    private fun newExecutor(failures: Int = 0): Pair<NativeExecutor, CountingFactory> {
        val factory = CountingFactory(failures)
        return (NativeExecutor(factory) to factory).also { executors += it }
    }

    @AfterTest
    fun closeAll() {
        executors.forEach { (executor: NativeExecutor, factory: CountingFactory) ->
            executor.close()
            factory.created.forEach { it.join(HANG_BOUND_MS) }
        }
    }

    @Test
    fun `a call made on a thread whose interrupt status is set answers an outcome and leaves that status to the caller`() {
        val (executor: NativeExecutor, _) = newExecutor()
        val result: CompletableFuture<Result<NativeOutcome<Int>>> = CompletableFuture()
        val statusAfterCall: CompletableFuture<Boolean> = CompletableFuture()
        // A plain thread, no runBlocking (which would react to the status itself): the coroutine body runs here up to its
        // first suspension, exactly as on a pooled dispatcher worker that a previous task left interrupted.
        val caller = Thread {
            Thread.currentThread().interrupt()
            val body: suspend () -> NativeOutcome<Int> = { executor.call(null) { 42 } }
            body.startCoroutine(Continuation(EmptyCoroutineContext) { outcome: Result<NativeOutcome<Int>> -> result.complete(outcome) })
            statusAfterCall.complete(Thread.interrupted())
        }
        caller.start()
        caller.join(HANG_BOUND_MS)

        val outcome: Result<NativeOutcome<Int>> = result.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
        assertTrue(
            outcome.isSuccess,
            "call() threw ${outcome.exceptionOrNull()} for a caller whose thread was interrupted: a NativeOutcome API (and " +
                "probeDesktopKeyStore above it) must never throw anything but cancellation (LinkedBlockingQueue.put is interruptible)",
        )
        assertEquals(42, assertIs<NativeOutcome.Completed<Int>>(outcome.getOrThrow()).value, "control: the call ran")
        assertEquals(
            true,
            statusAfterCall.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS),
            "the caller's interrupt status was consumed by the executor (it belongs to the caller)",
        )
        assertEquals(0, executor.pendingCalls)
    }

    @Test
    fun `a call that interrupts its own thread and then throws leaves the next call an uninterrupted thread`() = runBlocking {
        val (executor: NativeExecutor, factory: CountingFactory) = newExecutor()

        val failed: NativeOutcome<Unit> = executor.call(null) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("breaker: interrupted itself, then failed")
        }
        val next: NativeOutcome<Boolean> = executor.call(null) { Thread.currentThread().isInterrupted }

        assertIs<NativeOutcome.Threw>(failed, "control: the failure is typed")
        assertEquals(false, assertIs<NativeOutcome.Completed<Boolean>>(next).value, "the next call inherited the interrupt status")
        assertEquals(1, factory.created.size, "the native thread survived and was not replaced")
    }

    @Test
    fun `repeated thread creation failures make exactly one attempt per call and the first call after them makes one thread`() = runBlocking {
        val failures = 50
        val (executor: NativeExecutor, factory: CountingFactory) = newExecutor(failures)

        repeat(failures) { index: Int ->
            val outcome: NativeOutcome<Int> = executor.call(null) { index }
            assertIs<NativeOutcome.Threw>(outcome, "call $index: a thread that cannot be made answers that call typed")
            assertEquals(index + 1, factory.attempts.get(), "call $index: one creation attempt per call, never a retry loop")
            assertEquals(0, executor.pendingCalls, "call $index: the failed call stayed queued")
        }
        val outcome: NativeOutcome<String> = executor.call(null) { "ran" }

        assertEquals("ran", assertIs<NativeOutcome.Completed<String>>(outcome).value)
        assertEquals(failures + 1, factory.attempts.get())
        assertEquals(1, factory.created.size, "exactly one thread once creation works again")
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_MS: Long = 30_000
    }
}

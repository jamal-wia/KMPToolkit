package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 5 (phase A breaker round 3: M1 interrupted caller, m3 failed start racing another caller).
 * The breaker class proves the outcome; these tests pin the design behind it: the thread is made BEFORE the call is queued
 * (so a call that could not get a thread is never in the queue where another caller's new thread could take it), a native
 * thread that died is replaced by the next call, and the caller's interrupt status is never consumed, with and without a
 * time limit.
 */
class NativeExecutorRound5Test {

    /** Records what the executor looked like at the moment a thread was asked for. */
    private class ObservingFactory(private val failFirst: Boolean = false) : ThreadFactory {
        val executor: AtomicReference<NativeExecutor?> = AtomicReference()
        val queuedWhenAsked: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()
        val attempts: AtomicInteger = AtomicInteger()
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread {
            queuedWhenAsked += checkNotNull(executor.get()).pendingCalls
            if (failFirst && attempts.incrementAndGet() == 1) throw IllegalStateException("no thread (test)")
            return Thread(runnable, "round5-native").apply {
                isDaemon = true
                created += this
            }
        }
    }

    /** The first thread it makes ends at once without running the loop, as a thread killed by an Error would; the rest are normal. */
    private class DyingFirstFactory : ThreadFactory {
        val first: CompletableFuture<Thread> = CompletableFuture()
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread {
            val thread: Thread = if (first.isDone) {
                Thread(runnable, "round5-native").apply { isDaemon = true }
            } else {
                Thread({ }, "round5-dying").apply { isDaemon = true }.also { first.complete(it) }
            }
            created += thread
            return thread
        }
    }

    private val factories: MutableList<() -> List<Thread>> = CopyOnWriteArrayList()
    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        factories.forEach { threads: () -> List<Thread> -> threads().forEach { it.join(HANG_BOUND_MS) } }
    }

    private fun executorOf(factory: ThreadFactory, threads: () -> List<Thread>): NativeExecutor =
        NativeExecutor(factory).also {
            executors += it
            factories += threads
        }

    @Test
    fun `the thread is asked for before the call is queued, so a call that got no thread is never in the queue`() = runBlocking {
        val factory = ObservingFactory(failFirst = true)
        val executor: NativeExecutor = executorOf(factory) { factory.created }
        factory.executor.set(executor)

        val first: NativeOutcome<Int> = executor.call(null) { 1 }
        val second: NativeOutcome<Int> = executor.call(null) { 2 }

        assertIs<NativeOutcome.Threw>(first, "control: the first call could not get a thread")
        assertEquals(2, assertIs<NativeOutcome.Completed<Int>>(second).value)
        assertEquals(listOf(0, 0), factory.queuedWhenAsked.toList(), "a call was already queued when its thread was asked for")
    }

    @Test
    fun `a native thread that died is replaced by the next call and the call queued behind it still runs`() = runBlocking {
        val factory = DyingFirstFactory()
        val executor: NativeExecutor = executorOf(factory) { factory.created }

        withTimeout(HANG_BOUND_SECONDS.seconds) {
            val stuck = async(Dispatchers.Default) { executor.call(null) { 1 } }
            factory.first.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS).join(HANG_BOUND_MS)
            assertFalse(factory.first.get().isAlive, "control: the first native thread is gone")

            val next: NativeOutcome<Int> = executor.call(null) { 2 }

            assertEquals(2, assertIs<NativeOutcome.Completed<Int>>(next).value)
            assertEquals(1, assertIs<NativeOutcome.Completed<Int>>(stuck.await()).value, "the call the dead thread left queued ran too")
        }
        assertEquals(2, factory.created.size, "the dead thread was replaced by exactly one new thread")
    }

    @Test
    fun `a caller thread that is interrupted keeps its status through a call with a time limit`() {
        val factory = ObservingFactory()
        val executor: NativeExecutor = executorOf(factory) { factory.created }
        factory.executor.set(executor)
        val result: CompletableFuture<Result<NativeOutcome<Int>>> = CompletableFuture()
        val statusAfterCall: CompletableFuture<Boolean> = CompletableFuture()
        val caller = Thread {
            Thread.currentThread().interrupt()
            val body: suspend () -> NativeOutcome<Int> = { executor.call(HANG_BOUND_SECONDS.seconds) { 7 } }
            body.startCoroutine(Continuation(EmptyCoroutineContext) { outcome: Result<NativeOutcome<Int>> -> result.complete(outcome) })
            statusAfterCall.complete(Thread.interrupted())
        }
        caller.start()
        caller.join(HANG_BOUND_MS)

        val outcome: Result<NativeOutcome<Int>> = result.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
        assertTrue(outcome.isSuccess, "call() threw ${outcome.exceptionOrNull()} for an interrupted caller with a limit")
        assertEquals(7, assertIs<NativeOutcome.Completed<Int>>(outcome.getOrThrow()).value)
        assertEquals(true, statusAfterCall.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS), "the caller's interrupt status was consumed")
        assertEquals(0, executor.pendingCalls)
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_MS: Long = 30_000
        const val HANG_BOUND_SECONDS: Int = 30
    }
}

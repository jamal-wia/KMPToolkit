package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The native executor (plan 4.11, T6): one daemon thread per process, a limit for the caller that never applies a
 * withdrawn call later, a late result discarded, cancellation, and a terminal release.
 *
 * Time limits run on virtual time (`runTest`); a call that must really run uses `runBlocking`, because a test scheduler
 * that is idle would otherwise fire a limit while the native thread is still working. Every blocking wait of a test has a
 * hard real-time bound (a hang bound, never a timing assertion).
 */
class NativeExecutorTest {

    private class CountingFactory : ThreadFactory {
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "test-native-${created.size}").apply {
                isDaemon = true
                created += this
            }
    }

    private val factory = CountingFactory()
    private val executor = NativeExecutor(factory)

    @AfterTest
    fun closeExecutor() {
        executor.close()
        factory.created.forEach { it.join(HANG_BOUND_MS) }
        assertTrue(factory.created.none { it.isAlive }, "an executor thread outlived its close()")
    }

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue(latch.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS), "timed out waiting for $what")
    }

    @Test
    fun `no thread exists before the first call and exactly one thread serves every call`() = runBlocking {
        assertEquals(0, factory.created.size, "the thread must start lazily")
        val first: NativeOutcome<Int> = executor.call(null) { 7 }
        assertEquals(7, (first as NativeOutcome.Completed<Int>).value)
        assertEquals(1, factory.created.size, "control: the first call starts the thread")

        repeat(50) { index: Int ->
            assertEquals(index, (executor.call(null) { index } as NativeOutcome.Completed<Int>).value)
        }
        val concurrent: List<Int> = withTimeout(60.seconds) {
            (0 until 8).map { worker: Int ->
                async(Dispatchers.Default) {
                    (0 until 25).sumOf { step: Int -> (executor.call(null) { worker * 100 + step } as NativeOutcome.Completed<Int>).value }
                }
            }.awaitAll()
        }
        assertEquals((0 until 8).map { worker: Int -> (0 until 25).sumOf { worker * 100 + it } }, concurrent)
        assertEquals(1, factory.created.size, "calls from many threads still run on the one native thread")
        assertTrue(factory.created.single().isDaemon, "the native thread must not keep the JVM alive")
    }

    @Test
    fun `a call that is not given a positive limit is refused`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { executor.call(0.seconds) { 1 } }
        assertFailsWith<IllegalArgumentException> { executor.call((-1).seconds) { 1 } }
        assertEquals(0, factory.created.size, "a refused call must not start the thread")
    }

    @Test
    fun `a hung call answers TimedOut at the limit and its late result is discarded`() = runTest {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val discarded: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()
        val discardSeen = CountDownLatch(1)

        val call = async(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Int>(
                NativeExecutor.NON_INTERACTIVE_LIMIT,
                discard = { value: Int ->
                    discarded += value
                    discardSeen.countDown()
                },
            ) {
                started.countDown()
                await(release, "the test to release the hung call")
                41
            }
        }
        await(started, "the hung call to start") // the virtual clock cannot advance while the test thread blocks here
        assertEquals(NativeOutcome.TimedOut, call.await())
        assertEquals(emptyList(), discarded, "control: the call is still running, nothing was delivered yet")

        release.countDown()
        await(discardSeen, "the late result to reach discard")
        assertEquals(listOf(41), discarded)
        assertEquals(1, factory.created.size, "a hung call must not start another thread")
    }

    @Test
    fun `a call queued behind a hung one times out, is withdrawn and never runs afterwards`() = runTest {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val ranWithdrawn = AtomicInteger()

        val hung = async(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Unit>(null) {
                started.countDown()
                await(release, "release")
            }
        }
        await(started, "the hung call to start")
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Int>(NativeExecutor.NON_INTERACTIVE_LIMIT) { ranWithdrawn.incrementAndGet() }
        }

        assertEquals(NativeOutcome.TimedOut, queued.await())
        release.countDown()
        assertIs<NativeOutcome.Completed<Unit>>(hung.await())
        val control: NativeOutcome<Int> = executor.call(null) { 99 }
        assertEquals(99, (control as NativeOutcome.Completed<Int>).value)

        assertEquals(0, ranWithdrawn.get(), "a timed-out call must never be applied later")
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `cancelling the caller of a running call returns at once and the late result is discarded`() = runTest {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val discarded: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()
        val discardSeen = CountDownLatch(1)

        val call = launch(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Int>(
                null,
                discard = { value: Int ->
                    discarded += value
                    discardSeen.countDown()
                },
            ) {
                started.countDown()
                await(release, "release")
                5
            }
        }
        await(started, "the call to start")
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        assertEquals(emptyList(), discarded, "control: the call is still running")

        release.countDown()
        await(discardSeen, "the late result to reach discard")
        assertEquals(listOf(5), discarded)
    }

    @Test
    fun `cancelling the caller of a queued call withdraws it`() = runTest {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val ran = AtomicInteger()

        val hung = launch(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Unit>(null) {
                started.countDown()
                await(release, "release")
            }
        }
        await(started, "the hung call to start")
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { executor.call<Int>(null) { ran.incrementAndGet() } }
        queued.cancelAndJoin()
        release.countDown()
        hung.join()
        assertEquals(99, (executor.call(null) { 99 } as NativeOutcome.Completed<Int>).value)

        assertEquals(0, ran.get(), "a cancelled queued call must never run")
    }

    @Test
    fun `an exception of the binding is classified and the thread survives it`() = runBlocking {
        val unavailable: List<Throwable> = listOf(
            NoSuchMethodError("a JDK 22-24 runtime"),
            UnsatisfiedLinkError("jnidispatch"),
            ExceptionInInitializerError(RuntimeException("init")),
            NoClassDefFoundError("com/sun/jna/Native"),
            IllegalCallerException("--illegal-native-access=deny"),
            SecurityException("no native access"),
        )
        for (error: Throwable in unavailable) {
            val outcome: NativeOutcome<Unit> = executor.call(null) { throw error }
            assertTrue(
                outcome is NativeOutcome.NativeUnavailable && outcome.cause === error,
                "${error::class.java.simpleName} must be NativeUnavailable, was $outcome",
            )
        }
        val threw: List<Throwable> = listOf(
            IllegalStateException("bug"),
            java.io.IOException("io"),
            OutOfMemoryError("test"),
            InterruptedException("stray"),
        )
        for (error: Throwable in threw) {
            val outcome: NativeOutcome<Unit> = executor.call(null) { throw error }
            assertTrue(
                outcome is NativeOutcome.Threw && outcome.cause === error,
                "${error::class.java.simpleName} must be Threw, was $outcome",
            )
        }
        assertEquals(3, (executor.call(null) { 3 } as NativeOutcome.Completed<Int>).value, "the thread must survive")
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `close withdraws queued calls, interrupts the running one, ends the thread and refuses new calls`() = runTest {
        val started = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val ran = AtomicInteger()

        val hung = async(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Unit>(null) {
                started.countDown()
                // Only close()'s interrupt ends this wait (an InterruptedException); the bound is a hang bound.
                neverReleased.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
            }
        }
        await(started, "the call to start")
        val queued = async(start = CoroutineStart.UNDISPATCHED) { executor.call<Int>(null) { ran.incrementAndGet() } }
        val thread: Thread = factory.created.single()
        assertTrue(thread.isAlive, "control: the thread exists before close()")

        executor.close()

        assertIs<NativeOutcome.Threw>(queued.await(), "a queued call must be refused, not left hanging")
        assertIs<NativeOutcome.Threw>(hung.await(), "the running call ends when interrupted")
        thread.join(HANG_BOUND_MS)
        assertFalse(thread.isAlive, "close() must end the native thread")
        assertIs<NativeOutcome.Threw>(executor.call(null) { 1 }, "a closed executor refuses new calls")
        assertEquals(0, ran.get())
        assertEquals(0, executor.pendingCalls)
        executor.close() // idempotent
        assertTrue(executor.isClosed)
    }

    @Test
    fun `an executor that never ran a call starts no thread and closes cleanly`() {
        executor.close()
        assertEquals(0, factory.created.size)
        assertEquals(0, executor.pendingCalls)
    }

    @Test
    fun `a seeded storm with random limits and cancellations loses no result and leaves nothing behind`() = runBlocking {
        val seed = 20_261_115L
        val completedOk = AtomicInteger()
        val unexpected: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        withTimeout(120.seconds) {
            (0 until 8).map { worker: Int ->
                launch(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(150) { step: Int ->
                        val expected: Int = worker * 1000 + step
                        val limit = if (random.nextBoolean()) null else 30.seconds
                        val cancelAfterYields: Int = if (random.nextInt(4) == 0) random.nextInt(3) else -1
                        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            when (val outcome: NativeOutcome<Int> = executor.call(limit) { expected }) {
                                is NativeOutcome.Completed -> {
                                    if (outcome.value == expected) completedOk.incrementAndGet() else unexpected += "seed=$seed w=$worker s=$step wrong ${outcome.value}"
                                }
                                else -> unexpected += "seed=$seed w=$worker s=$step outcome $outcome"
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
        }
        assertEquals(emptyList(), unexpected.take(5), "seed=$seed")
        assertTrue(completedOk.get() > 0, "seed=$seed control: some calls completed")
        executor.close()
        factory.created.forEach { it.join(HANG_BOUND_MS) }
        assertEquals(0, executor.pendingCalls, "seed=$seed: calls left behind")
        assertEquals(1, factory.created.size, "seed=$seed: more than one native thread")
        withContext(Dispatchers.Default) { assertFalse(factory.created.single().isAlive) }
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_MS: Long = 60_000
    }
}

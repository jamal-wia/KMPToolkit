package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Group sto-desktop, round 4 (phase A breaker round 2: M2 interrupt status, m1 unstartable thread). The breaker classes
 * prove the thread survives; these tests pin the rest of the contract: the interrupt status a call leaves is cleared before
 * the next call, [NativeExecutor.close] still ends a thread that was interrupted by a call, and a thread that cannot be
 * made answers that one call typed, leaves nothing queued, and every later call tries again.
 */
class NativeExecutorRound4Test {

    private class Factory(private val failures: Int = 0, private val failWith: () -> Throwable = { IllegalStateException("no thread (test)") }) :
        ThreadFactory {
        val created: CopyOnWriteArrayList<Thread> = CopyOnWriteArrayList()
        val attempts: AtomicInteger = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            if (attempts.incrementAndGet() <= failures) throw failWith()
            return Thread(runnable, "round4-native-${created.size}").apply {
                isDaemon = true
                created += this
            }
        }
    }

    private val executors: MutableList<Pair<NativeExecutor, Factory>> = CopyOnWriteArrayList()

    private fun newExecutor(factory: Factory): NativeExecutor = NativeExecutor(factory).also { executors += it to factory }

    @AfterTest
    fun closeAll() {
        executors.forEach { (executor: NativeExecutor, factory: Factory) ->
            executor.close()
            factory.created.forEach { it.join(HANG_BOUND_MS) }
        }
    }

    @Test
    fun `the interrupt status a call leaves behind is cleared before the next call runs`() = runBlocking {
        val executor: NativeExecutor = newExecutor(Factory())
        executor.call(null) { Thread.currentThread().interrupt() }

        val seen: NativeOutcome<Boolean> = executor.call(null) { Thread.currentThread().isInterrupted }

        assertEquals(false, (seen as NativeOutcome.Completed<Boolean>).value, "the next call started with its predecessor's interrupt status")
    }

    @Test
    fun `close ends a native thread that an earlier call interrupted and refuses new calls`() = runBlocking {
        val factory = Factory()
        val executor: NativeExecutor = newExecutor(factory)
        executor.call(null) { Thread.currentThread().interrupt() }
        assertTrue(factory.created.single().isAlive, "control: the thread survived the interrupting call")

        executor.close()

        factory.created.single().join(HANG_BOUND_MS)
        assertFalse(factory.created.single().isAlive, "the native thread did not end after close")
        assertIs<NativeOutcome.Threw>(executor.call(null) { 1 }, "a closed executor refuses new calls")
        assertEquals(0, executor.pendingCalls)
    }

    @Test
    fun `a thread that cannot be made answers that call Threw, queues nothing, and the next call makes the thread`() = runBlocking {
        val factory = Factory(failures = 2)
        val executor: NativeExecutor = newExecutor(factory)
        val ran = AtomicBoolean(false)

        val first: NativeOutcome<Int> = executor.call(null) { ran.set(true); 1 }
        assertIs<NativeOutcome.Threw>(first, "control: the first call could not start")
        assertIs<IllegalStateException>(first.cause)
        assertEquals(0, executor.pendingCalls, "the call that could not start stayed in the queue")
        assertEquals(0, factory.created.size, "control: no thread exists yet")

        assertIs<NativeOutcome.Threw>(executor.call(null) { 2 }, "the second attempt failed too (two failures were scripted)")
        assertEquals(0, executor.pendingCalls)

        val third: NativeOutcome<Int> = executor.call(null) { 3 }

        assertEquals(3, (third as NativeOutcome.Completed<Int>).value, "the third call makes the thread and runs")
        assertEquals(1, factory.created.size, "exactly one thread was made")
        assertFalse(ran.get(), "a call that was answered as failed ran later")
    }

    @Test
    fun `an Error out of the thread factory is typed too and never thrown out of the call`() = runBlocking {
        val executor: NativeExecutor = newExecutor(
            Factory(failures = 1, failWith = { OutOfMemoryError("unable to create native thread (test)") }),
        )

        val first: NativeOutcome<Int> = executor.call(null) { 1 }

        assertIs<OutOfMemoryError>(assertIs<NativeOutcome.Threw>(first).cause)
        assertEquals(2, (executor.call(null) { 2 } as NativeOutcome.Completed<Int>).value)
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_MS: Long = 30_000
    }
}

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Group sto-desktop, round 3 (breaker B1/m3): callers that are cancelled while their call is queued behind a hung call
 * must leave the queue too (the timeout path is covered by `NativeExecutorBreakerR1Test`), and a withdrawn call never runs.
 */
class NativeExecutorWithdrawalTest {

    private val executor = NativeExecutor()

    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    @Test
    fun `callers cancelled while queued behind a hung call leave the queue and their calls never run`() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val ran = AtomicInteger()
        val hung = async(start = CoroutineStart.UNDISPATCHED) {
            executor.call<Unit>(null) {
                started.countDown()
                assertTrue(release.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS), "timed out waiting for the test to release the hung call")
            }
        }
        assertTrue(started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS), "timed out waiting for the hung call to start")
        assertEquals(1, executor.pendingCalls, "control: only the hung call is pending")

        val waiting = List(QUEUED_CALLS) {
            async(start = CoroutineStart.UNDISPATCHED) { executor.call<Int>(null) { ran.incrementAndGet() } }
        }
        yield()
        assertEquals(QUEUED_CALLS + 1, executor.pendingCalls, "control: the queued calls are pending")

        waiting.forEach { it.cancelAndJoin() }

        assertEquals(1, executor.pendingCalls, "cancelled callers left their calls in the queue")
        release.countDown()
        hung.await()
        // A follow-up call runs after the withdrawn ones: none of them ran in between.
        val follow: NativeOutcome<Int> = executor.call(null) { 7 }
        assertEquals(7, (follow as NativeOutcome.Completed<Int>).value)
        assertEquals(0, ran.get(), "a withdrawn call ran")
        assertEquals(0, executor.pendingCalls, "nothing pending when idle")
    }

    private companion object {
        const val HANG_BOUND_MS: Long = 60_000
        const val QUEUED_CALLS: Int = 50
    }
}

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, phase A breaker round 7 (decisions.md D-23), part A7: `NativeExecutor.call` (plan 3.5, the native executor
 * of 6a) against its KDoc: "A call that has not started when its limit passes (or its caller is cancelled) is withdrawn from the
 * queue and never runs, so a timed-out write is never applied later."
 *
 * A caller that is ALREADY cancelled when it calls is the plainest case of "cancelled before the call started". `call` does not
 * look at its caller's state before it queues the task: it offers it, and only the `await` that follows throws. On an idle
 * executor the native thread, woken by the offer, races the caller's `abandon()` for the task, and when it wins the call runs
 * for a caller that has been answered `CancellationException` (an OS write applied although its caller was told it was cancelled;
 * with `callOwning`, a block that reads the key for nobody). A scratch run counted 2503 of 20000 such calls run (JBR 21, macOS).
 *
 * The race cannot be held by a seam, so this test repeats the call [ATTEMPTS] times on an idle executor and counts the calls that
 * ran; with the defect a run of this length has never come out zero, and with a check of the caller's cancellation before the
 * offer no attempt can run at all (green deterministically). Every attempt is joined; the executor serves afterwards (control).
 */
class NativeExecutorA7BreakerR7Test {

    @Test
    fun `a caller that is cancelled before it calls never has its call run`() = runBlocking {
        withTimeout(HANG_BOUND) {
            NativeExecutor().use { executor ->
                assertIs<NativeOutcome.Completed<Unit>>(executor.call(null) { Unit }, "control: the native thread is up and idle")
                val ran = AtomicInteger()
                val outcomes = AtomicInteger()
                repeat(ATTEMPTS) {
                    val attempt = async(Dispatchers.Default) {
                        currentCoroutineContext().job.cancel()
                        executor.call(null) { ran.incrementAndGet() }
                        outcomes.incrementAndGet()
                    }
                    attempt.join()
                    check(attempt.isCancelled) { "control: the attempt ended cancelled" }
                    // FIFO on one thread: a task the thread took has finished before this one answers.
                    assertIs<NativeOutcome.Completed<Unit>>(executor.call(null) { Unit })
                }
                assertEquals(
                    0,
                    ran.get(),
                    "KDoc: 'a call that has not started when ... its caller is cancelled is withdrawn ... and never runs'; " +
                        "${ran.get()} of $ATTEMPTS calls made by an already-cancelled caller ran anyway (${outcomes.get()} callers got an outcome)",
                )
                assertEquals(0, executor.pendingCalls, "nothing is left queued")
            }
        }
        Unit
    }

    private companion object {
        const val ATTEMPTS: Int = 20_000
        val HANG_BOUND = 300.seconds
    }
}

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, phase A breaker round 4 (decisions.md D-23): the round-5 order of [NativeExecutor.call] ("the thread
 * first, the queue second", `offer`, then the `closed` re-check) attacked where it is new:
 *
 * - `close()` lands between the caller's `closed` check and the thread start (forced from inside the thread factory, the one
 *   place that runs exactly there): the call must answer, the thread that was made must end, nothing stays queued, and the
 *   executor stays terminal;
 * - a seeded storm of callers racing `close()` while the thread is being made and while calls run: every call answers exactly
 *   once (an outcome, never a hang and never an exception), the native thread ends, and nothing stays queued.
 */
class NativeExecutorBreakerR4Test {

    private val threads: MutableList<Thread> = CopyOnWriteArrayList()
    private val executors: MutableList<NativeExecutor> = CopyOnWriteArrayList()

    @AfterTest
    fun closeAll() {
        executors.forEach { it.close() }
        threads.forEach { it.join(HANG_BOUND_MS) }
    }

    /** Makes daemon threads; [beforeReturn] runs inside `newThread`, i.e. inside `ensureStarted`, after the caller's `closed` check. */
    private inner class HookedFactory(private val beforeReturn: () -> Unit = {}) : ThreadFactory {
        val asked: AtomicInteger = AtomicInteger()
        val executor: AtomicReference<NativeExecutor?> = AtomicReference()

        /** Threads asked for when the executor was ALREADY closed: release is terminal, so there must be none. */
        val askedWhileClosed: AtomicInteger = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            asked.incrementAndGet()
            if (executor.get()?.isClosed == true) askedWhileClosed.incrementAndGet()
            beforeReturn()
            return Thread(runnable, "breaker4-native").apply {
                isDaemon = true
                threads += this
            }
        }
    }

    private fun executorOf(factory: ThreadFactory): NativeExecutor = NativeExecutor(factory).also { executors += it }

    @Test
    fun `a close that lands while the first call makes the thread answers that call, ends the thread and leaves nothing queued`() = runBlocking {
        val executor = AtomicReference<NativeExecutor>()
        val factory = HookedFactory(beforeReturn = { executor.get().close() })
        executor.set(executorOf(factory))
        val ran = AtomicInteger()

        val outcome: NativeOutcome<Int> = withTimeout(HANG_BOUND) { executor.get().call(null) { ran.incrementAndGet() } }

        assertEquals(1, factory.asked.get(), "control: the close ran inside the thread start")
        assertTrue(executor.get().isClosed, "control: the executor is closed")
        // Either answer is honest (the call was refused, or it was taken in the instant before the refusal and ran once);
        // a hang, an exception or a second run is not.
        when (outcome) {
            is NativeOutcome.Threw -> assertEquals(0, ran.get(), "a refused call ran anyway")
            is NativeOutcome.Completed<Int> -> assertEquals(1, ran.get(), "a delivered call ran ${ran.get()} times")
            else -> throw AssertionError("unexpected outcome $outcome")
        }
        val made: Thread = threads.single()
        made.join(HANG_BOUND_MS)
        assertFalse(made.isAlive, "the thread made after close() is still running (state ${made.state})")
        assertEquals(0, executor.get().pendingCalls, "a call stayed queued")

        val later: NativeOutcome<Int> = executor.get().call(null) { ran.incrementAndGet() }
        assertIs<NativeOutcome.Threw>(later, "close is terminal")
        assertEquals(1, factory.asked.get(), "a closed executor asked for another thread")
    }

    @Test
    fun `a seeded storm of callers racing close while the thread is made and while calls run answers every call exactly once`() {
        val seed = 20_261_128
        val madeAfterClose: MutableList<String> = mutableListOf()
        repeat(ROUNDS) { round: Int ->
            val random = Random(seed * 1_000 + round)
            val closeAt: Int = random.nextInt(0, CALLERS + 1)
            val started = CountDownLatch(1)
            val factory = HookedFactory(beforeReturn = { started.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS) })
            val executor: NativeExecutor = executorOf(factory)
            factory.executor.set(executor)
            val runs = AtomicInteger()
            val answered = AtomicInteger()

            val limited: List<Boolean> = List(CALLERS) { random.nextBoolean() }

            // The body runs on this test thread; the callers run on Dispatchers.Default, so the first caller can sit inside
            // the thread start (blocking its worker) while this thread decides when it may go on.
            runBlocking {
                withTimeout(HANG_BOUND) {
                    val calls = (0 until CALLERS).map { caller: Int ->
                        async(Dispatchers.Default) {
                            if (caller == closeAt) executor.close()
                            val outcome: NativeOutcome<Int> = executor.call(if (limited[caller]) 30.seconds else null) {
                                runs.incrementAndGet()
                                caller
                            }
                            answered.incrementAndGet()
                            outcome
                        }
                    }
                    // closeAt == CALLERS: close() while the first caller is still inside the thread start.
                    if (closeAt == CALLERS) executor.close()
                    started.countDown()
                    val outcomes: List<NativeOutcome<Int>> = calls.awaitAll()
                    val completed: Int = outcomes.count { it is NativeOutcome.Completed<Int> }
                    assertTrue(
                        outcomes.all { it is NativeOutcome.Completed<Int> || it is NativeOutcome.Threw },
                        "seed=$seed round=$round closeAt=$closeAt: an outcome that is neither delivered nor refused: $outcomes",
                    )
                    assertEquals(completed, runs.get(), "seed=$seed round=$round closeAt=$closeAt: runs and delivered results disagree (a call ran for nobody or twice)")
                }
            }
            assertEquals(CALLERS, answered.get(), "seed=$seed round=$round: a caller never got an answer")
            threads.forEach { it.join(HANG_BOUND_MS) }
            assertTrue(threads.none { it.isAlive }, "seed=$seed round=$round: a native thread outlived close()")
            assertEquals(0, executor.pendingCalls, "seed=$seed round=$round: a call stayed queued after close()")
            // Before close() the first thread never ends, so a second thread can only be one made after close() ended the first.
            // (askedWhileClosed is reported for diagnosis only: one ask may race a close() fairly.)
            if (factory.asked.get() > 1) {
                madeAfterClose += "round=$round closeAt=$closeAt asked=${factory.asked.get()} whileClosed=${factory.askedWhileClosed.get()}"
            }
            threads.clear()
        }
        assertEquals(
            emptyList(),
            madeAfterClose,
            "seed=$seed: native threads were made and started by an executor that was already closed (release is terminal; " +
                "each such thread sees `closed` and ends at once, the next in-flight caller makes another)",
        )
    }

    private companion object {
        const val ROUNDS: Int = 40
        const val CALLERS: Int = 12

        /** Hang bounds only, never timing assertions. */
        const val HANG_BOUND_MS: Long = 30_000
        val HANG_BOUND = 30_000.milliseconds
    }
}

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Group sto-desktop, phase A breaker round 7 (decisions.md D-23), part A7: `NativeCaller.callOwning` (round 8, 7ac79df7) against
 * its KDoc: "a call that takes OWNERSHIP of [secret] ... the array is wiped, exactly once, on every path. Whether the native thread
 * wipes it or this caller does is decided by one compare-and-set, so a thread that is still reading it is never wiped under",
 * "a block that never starts (the caller left while it was queued, the executor was closed, no thread could be made) ... the
 * caller claims the array when it stops waiting, whatever ended the wait (an outcome, a time limit, a cancellation)" and "the
 * outcome is Completed(null) only for that last race, which no caller can see".
 *
 * The round-8 tests cover a cancelled caller (queued and running), a closed executor and a throwing block. Here: the TIME LIMIT of
 * a non-interactive call, queued and running (virtual time, the 10 s limit of `NativeCaller`); a thread that cannot be made; and a
 * seeded storm on real threads that crosses every instant the round-8 tests could not hold, in particular a caller that leaves in
 * the window between the task's RUNNING state and the block's claim (no seam exists for it): callers cancelled before the call,
 * while queued, while running and right as the block is released, blocks that throw, and `close()` at a random point. Every
 * array is held by the test and checked from inside the block (never handed a wiped array, never wiped under the block) and after
 * everything has ended (all zero). Latches and joins with hang bounds only.
 */
class NativeCallerOwnershipA7BreakerR7Test {

    private fun live(bytes: ByteArray): Boolean = bytes.all { it != 0.toByte() }

    private fun dead(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    private fun secret(fill: Int = 1): ByteArray = ByteArray(32) { ((it + fill) % 250 + 1).toByte() }

    private fun await(latch: CountDownLatch, what: String) {
        check(latch.await(HANG_SECONDS, TimeUnit.SECONDS)) { "hang bound: $what" }
    }

    /** A non-interactive call queued behind a busy thread reaches its 10 s limit: withdrawn, the block never runs, the caller wipes. */
    @Test
    fun `a limited call that times out while queued never runs and its array is wiped`() = runTest {
        NativeExecutor().use { executor ->
            val busy = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker = async(Dispatchers.Default) {
                executor.call(null) {
                    busy.countDown()
                    await(release, "the test to release the busy call")
                }
            }
            try {
                await(busy, "the busy call to start")
                val owned: ByteArray = secret()
                val ran = AtomicInteger()
                val call: Deferred<NativeOutcome<Int?>> = async(start = CoroutineStart.UNDISPATCHED) {
                    NativeCaller(executor, allowUi = false).callOwning(owned) { _: ByteArray -> ran.incrementAndGet() }
                }
                assertEquals(2, executor.pendingCalls, "control: the owning call is queued behind the busy one")
                assertTrue(live(owned), "control: the array is live while the call is queued")
                assertEquals(NativeOutcome.TimedOut, call.await(), "the 10 s limit passes on virtual time")
                assertTrue(dead(owned), "a call withdrawn by its time limit never runs its block, so the caller must wipe the copy")
                assertEquals(0, ran.get(), "the withdrawn block never ran")
            } finally {
                release.countDown()
            }
            blocker.await()
            val idle: NativeOutcome<Int> = executor.call(null) { 1 }
            assertIs<NativeOutcome.Completed<Int>>(idle, "control: the executor still serves")
            assertEquals(0, executor.pendingCalls)
        }
    }

    /**
     * A non-interactive call whose limit passes while its block RUNS: the caller gets `TimedOut` at once, the array stays live until
     * the block returns (the native thread still reads it), and the native thread wipes it then.
     */
    @Test
    fun `a limited call that times out while running keeps its array live until the block returns`() = runTest {
        NativeExecutor().use { executor ->
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val owned: ByteArray = secret()
            var liveAtEnd = false
            val call: Deferred<NativeOutcome<Int?>> = async(start = CoroutineStart.UNDISPATCHED) {
                NativeCaller(executor, allowUi = false).callOwning(owned) { bytes: ByteArray ->
                    inside.countDown()
                    await(release, "the test to release the running call")
                    liveAtEnd = live(bytes)
                    5
                }
            }
            try {
                await(inside, "the owning call to start") // virtual time cannot pass while the test thread blocks here
                assertEquals(NativeOutcome.TimedOut, call.await(), "the limit passes while the block runs")
                assertTrue(live(owned), "the caller timed out, but the native thread still reads the array: it must not be wiped yet")
            } finally {
                release.countDown()
            }
            // FIFO on one thread: this call completes only after the owning task's block and its wipe have finished.
            val after: NativeOutcome<Int> = executor.call(null) { 1 }
            assertIs<NativeOutcome.Completed<Int>>(after)
            assertTrue(liveAtEnd, "the block saw its live array to its last read")
            assertTrue(dead(owned), "the native thread wipes the array after the block returned")
        }
    }

    /** A thread that cannot be made (a thread limit): the call answers `Threw`, its block never runs and the caller wipes. */
    @Test
    fun `a call whose native thread cannot be made gets its array wiped and the next call works`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val refusals = AtomicInteger(1)
            val factory = ThreadFactory { runnable: Runnable ->
                if (refusals.getAndDecrement() > 0) throw OutOfMemoryError("unable to create native thread (breaker A7)")
                Thread(runnable, "a7-native").apply { isDaemon = true }
            }
            NativeExecutor(factory).use { executor ->
                val owned: ByteArray = secret()
                val ran = AtomicInteger()
                val outcome: NativeOutcome<Int?> = NativeCaller(executor, allowUi = true).callOwning(owned) { _: ByteArray -> ran.incrementAndGet() }
                assertIs<NativeOutcome.Threw>(outcome, "no thread, no call: $outcome")
                assertTrue(dead(owned), "a call that never got a thread never runs its block, so the caller must wipe the copy")
                assertEquals(0, ran.get())
                val second: ByteArray = secret(7)
                var seenLive = false
                val next: NativeOutcome<Int?> = NativeCaller(executor, allowUi = true).callOwning(second) { bytes: ByteArray ->
                    seenLive = live(bytes)
                    3
                }
                assertEquals(3, assertIs<NativeOutcome.Completed<Int?>>(next).value, "control: the next call gets a thread")
                assertTrue(seenLive && dead(second), "control: the next call saw its live array and left it wiped")
            }
        }
        Unit
    }

    private enum class Leave { NEVER, BEFORE_CALL, WHILE_QUEUED, WHILE_RUNNING, AT_RELEASE }

    private enum class Close { NEVER, WHILE_HELD, AT_RELEASE }

    /**
     * The storm. Per round (seeded): a fresh executor whose threads are recorded, one holder call that keeps the native thread busy
     * until released, then N owning calls (interactive, so only a cancellation or close ends a wait), each leaving at a random
     * instant or never, some blocks throwing, and `close()` at a random point. After the round has ended (callers joined, executor
     * closed, its threads joined): every array is zero; no block was handed a wiped array or had its array wiped while it ran; no
     * block ran twice; no caller saw `Completed(null)`; a caller that saw `Completed(v)` saw its own block's value.
     */
    @Test
    fun `a seeded storm of leaving callers and closes never wipes under a block and never leaves a live copy`() = runBlocking {
        withTimeout(STORM_BOUND) {
            val random = Random(SEED)
            val stats = StormStats()
            repeat(ROUNDS) { round: Int -> stormRound(round, random, stats) }
            println("seed=$SEED: $stats")
            // Controls: the storm reached every way an owned copy can end.
            assertTrue(stats.ranAndDelivered.get() > 0, "seed=$SEED: control, some blocks ran for a caller that got the value ($stats)")
            assertTrue(stats.neverRan.get() > 0, "seed=$SEED: control, some blocks never ran (withdrawn, refused) ($stats)")
            assertTrue(stats.ranForLeftCaller.get() > 0, "seed=$SEED: control, some blocks ran for a caller that had left ($stats)")
        }
        Unit
    }

    private suspend fun stormRound(round: Int, random: Random, stats: StormStats): Unit = coroutineScope {
        val label = "seed=$SEED round=$round"
        val threads: ConcurrentLinkedQueue<Thread> = ConcurrentLinkedQueue()
        val executor = NativeExecutor(ThreadFactory { runnable: Runnable ->
            Thread(runnable, "a7-storm").apply { isDaemon = true }.also { threads += it }
        })
        val violations: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val holderInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callers: Int = 2 + random.nextInt(CALLERS - 1)
        val leaves: List<Leave> = List(callers) { Leave.entries[random.nextInt(Leave.entries.size)] }
        val throwing: List<Boolean> = List(callers) { random.nextInt(4) == 0 }
        val close: Close = if (random.nextBoolean()) Close.NEVER else Close.entries[1 + random.nextInt(Close.entries.size - 1)]
        val arrays: List<ByteArray> = List(callers) { secret(round + it) }
        val ranCount = AtomicIntegerArray(callers)
        val entered: List<CountDownLatch> = List(callers) { CountDownLatch(1) }
        val outcomes = AtomicReferenceArray<Any?>(callers)
        val caller = NativeCaller(executor, allowUi = true)
        try {
            val holder: Deferred<NativeOutcome<Unit>> = async(Dispatchers.Default) {
                executor.call(null) {
                    holderInside.countDown()
                    try {
                        release.await(HANG_SECONDS, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // close() interrupts the running call: fine, it ends.
                    }
                    Unit
                }
            }
            await(holderInside, "$label: the holder call to start")
            val jobs: List<Job> = List(callers) { i: Int ->
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    if (leaves[i] == Leave.BEFORE_CALL) currentCoroutineContext().job.cancel()
                    val outcome: NativeOutcome<Int?> = caller.callOwning(arrays[i]) { bytes: ByteArray ->
                        ranCount.incrementAndGet(i)
                        if (!live(bytes)) violations += "$label call $i: the block was handed a wiped array"
                        entered[i].countDown()
                        // A block that is still running when its caller leaves keeps reading: give the leaving caller its chance.
                        for (spin in 0 until 64) {
                            if (!live(bytes)) {
                                violations += "$label call $i: the array was wiped while the block ran"
                                break
                            }
                            Thread.yield()
                        }
                        if (throwing[i]) throw IllegalStateException("the native call failed ($label call $i)")
                        i
                    }
                    outcomes.set(i, outcome)
                }
            }
            yield()
            // Every caller is past its offer now (UNDISPATCHED runs up to the first suspension) and queued behind the holder.
            for (i in 0 until callers) if (leaves[i] == Leave.WHILE_QUEUED) jobs[i].cancel()
            if (close == Close.WHILE_HELD) executor.close()
            release.countDown()
            if (close == Close.AT_RELEASE) executor.close()
            for (i in 0 until callers) if (leaves[i] == Leave.AT_RELEASE) jobs[i].cancel()
            for (i in 0 until callers) {
                if (leaves[i] != Leave.WHILE_RUNNING) continue
                // Leave while (or right after) the block runs. With the executor open every queued block runs in turn, so wait for
                // this one to start; once closed, a block that was refused never starts, so leave at once.
                if (close == Close.NEVER) await(entered[i], "$label call $i to start")
                jobs[i].cancel()
            }
            for (job: Job in jobs) withTimeout(HANG_BOUND) { job.join() }
            withTimeout(HANG_BOUND) { holder.join() }
        } finally {
            release.countDown()
            executor.close()
            for (thread: Thread in threads) {
                thread.join(TimeUnit.SECONDS.toMillis(HANG_SECONDS))
                check(!thread.isAlive) { "$label: hang bound, the native thread did not end after close: ${thread.stackTrace.joinToString()}" }
            }
        }
        assertEquals(emptyList(), violations.toList(), label)
        for (i in 0 until callers) {
            val ran: Int = ranCount.get(i)
            assertTrue(ran <= 1, "$label call $i: the block ran $ran times")
            assertTrue(dead(arrays[i]), "$label call $i (${leaves[i]}, close=$close, ran=$ran): the owned copy is still live after the round ended")
            val seen: Any? = outcomes.get(i)
            when {
                ran == 0 -> stats.neverRan.incrementAndGet()
                seen is NativeOutcome.Completed<*> -> stats.ranAndDelivered.incrementAndGet()
                seen == null -> stats.ranForLeftCaller.incrementAndGet()
                else -> stats.ranOther.incrementAndGet()
            }
            when (val outcome: Any? = seen) {
                is NativeOutcome.Completed<*> -> {
                    assertTrue(outcome.value != null, "$label call $i: a caller saw Completed(null), which the KDoc says no caller can see")
                    assertEquals(i, outcome.value, "$label call $i: the caller saw another block's value")
                    assertEquals(1, ran, "$label call $i: Completed without its block having run")
                }
                else -> Unit
            }
        }
    }

    private class StormStats {
        val ranAndDelivered = AtomicInteger()
        val neverRan = AtomicInteger()
        val ranForLeftCaller = AtomicInteger()
        val ranOther = AtomicInteger()

        override fun toString(): String =
            "ran and delivered=${ranAndDelivered.get()}, never ran=${neverRan.get()}, ran for a caller that left=${ranForLeftCaller.get()}, ran otherwise=${ranOther.get()}"
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND = 120.seconds
        val STORM_BOUND = 600.seconds
        const val SEED: Long = 20261115L
        const val ROUNDS: Int = 300
        const val CALLERS: Int = 8
    }
}

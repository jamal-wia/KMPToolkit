package io.github.jamal_wia.kmptoolkit.core

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The contract of [StateMachineLock]: [StateMachineLock.exclusive] blocks and runs inline,
 * [StateMachineLock.submit] never blocks and is run by whoever holds the lock before it lets go.
 *
 * The first group is deterministic and single-threaded; the second uses real threads
 * (`Dispatchers.Default`) with the holder parked inside the lock to hold each race window open.
 */
@OptIn(ExperimentalAtomicApi::class, ToolkitInternalApi::class)
class StateMachineLockTest {

    // --- Single thread ---

    @Test
    fun `exclusive runs the block inline and returns its value`() {
        val lock = StateMachineLock()
        var ranOn: Int = 0

        val result: String = lock.exclusive {
            ranOn++
            "value"
        }

        assertEquals("value", result)
        assertEquals(1, ranOn)
    }

    @Test
    fun `exclusive is reentrant`() {
        val lock = StateMachineLock()
        val trace: MutableList<String> = mutableListOf()

        val result: Int = lock.exclusive {
            trace += "outer-start"
            val inner: Int = lock.exclusive {
                trace += "inner"
                21
            }
            trace += "outer-end"
            inner * 2
        }

        assertEquals(42, result)
        assertEquals(listOf("outer-start", "inner", "outer-end"), trace)
    }

    @Test
    fun `exclusive propagates the exception of its block and stays usable`() {
        val lock = StateMachineLock()
        val failure = IllegalStateException("boom")

        val thrown: IllegalStateException = assertFailsWith<IllegalStateException> {
            lock.exclusive { throw failure }
        }

        assertSame(failure, thrown)
        assertEquals(7, lock.exclusive { 7 })
    }

    @Test
    fun `submit runs at once when the lock is free`() {
        val lock = StateMachineLock()
        var ran = false

        lock.submit { ran = true }

        assertTrue(ran, "a free lock must run the action before submit returns")
    }

    @Test
    fun `submit from inside exclusive on the same thread runs after the block and before the outermost return`() {
        val lock = StateMachineLock()
        val trace: MutableList<String> = mutableListOf()

        lock.exclusive {
            trace += "block-start"
            lock.submit { trace += "submitted" }
            trace += "block-end"
        }
        trace += "returned"

        assertEquals(listOf("block-start", "block-end", "submitted", "returned"), trace)
    }

    @Test
    fun `a submit inside a nested exclusive waits for the outermost block`() {
        val lock = StateMachineLock()
        val trace: MutableList<String> = mutableListOf()

        lock.exclusive {
            lock.exclusive {
                lock.submit { trace += "submitted" }
                trace += "inner-end"
            }
            trace += "outer-end"
        }

        assertEquals(listOf("inner-end", "outer-end", "submitted"), trace)
    }

    @Test
    fun `queued actions run in submission order`() {
        val lock = StateMachineLock()
        val trace: MutableList<Int> = mutableListOf()

        lock.exclusive {
            for (index: Int in 1..5) lock.submit { trace += index }
        }

        assertEquals(listOf(1, 2, 3, 4, 5), trace)
    }

    @Test
    fun `an action queued by a queued action runs after the ones already queued`() {
        val lock = StateMachineLock()
        val trace: MutableList<String> = mutableListOf()

        lock.exclusive {
            lock.submit {
                trace += "first"
                lock.submit { trace += "nested" }
            }
            lock.submit { trace += "second" }
        }

        assertEquals(listOf("first", "second", "nested"), trace)
    }

    @Test
    fun `a failing queued action is rethrown after the ones behind it ran and later failures are suppressed`() {
        val lock = StateMachineLock()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        var thirdRan = false

        val thrown: IllegalStateException = assertFailsWith<IllegalStateException> {
            lock.exclusive {
                lock.submit { throw first }
                lock.submit { throw second }
                lock.submit { thirdRan = true }
            }
        }

        assertSame(first, thrown)
        assertEquals(listOf<Throwable>(second), thrown.suppressedExceptions)
        assertTrue(thirdRan, "an action behind a failing one must still run")
        assertEquals(1, lock.exclusive { 1 }, "the lock must be released and usable afterwards")
    }

    @Test
    fun `a failing action submitted to a free lock is rethrown by submit`() {
        val lock = StateMachineLock()
        val failure = IllegalStateException("boom")

        val thrown: IllegalStateException = assertFailsWith<IllegalStateException> {
            lock.submit { throw failure }
        }

        assertSame(failure, thrown)
        var ran = false
        lock.submit { ran = true }
        assertTrue(ran, "the lock must be released and usable afterwards")
    }

    @Test
    fun `queued actions still run when the block throws`() {
        val lock = StateMachineLock()
        val failure = IllegalStateException("block")
        var ran = false

        val thrown: IllegalStateException = assertFailsWith<IllegalStateException> {
            lock.exclusive {
                lock.submit { ran = true }
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertTrue(ran, "an action queued before the block failed must not be stranded")
    }

    // --- Several threads ---

    @Test
    fun `submit from another thread while the lock is held is queued and runs before exclusive returns`() = runTest {
        val lock = StateMachineLock()
        val ran = AtomicBoolean(false)
        val submitReturned = AtomicBoolean(false)
        val ranWhileHeld = AtomicBoolean(false)

        withContext(Dispatchers.Default) {
            val submitter = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                lock.submit { ran.store(true) }
                submitReturned.store(true)
            }
            lock.exclusive {
                submitter.start()
                // submit never blocks, so it returns while this thread still holds the lock.
                spinUntil("submit to return while the lock is held") { submitReturned.load() }
                ranWhileHeld.store(ran.load())
            }
            submitter.await()
        }

        assertFalse(ranWhileHeld.load(), "the action must wait for the holder, not run on the submitter")
        assertTrue(ran.load(), "the holder must run the queued action before exclusive returns")
    }

    @Test
    fun `a blocked exclusive waits for the holder to finish`() = runTest {
        val lock = StateMachineLock()
        val holding = AtomicBoolean(false)
        val release = AtomicBoolean(false)
        val holderFinished = AtomicBoolean(false)
        val waiterEntered = AtomicBoolean(false)
        val waiterSawHolderFinished = AtomicBoolean(false)
        val waiterEnteredEarly = AtomicBoolean(false)

        withContext(Dispatchers.Default) {
            val holder = async {
                lock.exclusive {
                    holding.store(true)
                    spinUntil("release signal") { release.load() }
                    holderFinished.store(true)
                }
            }
            spinUntil("holder to take the lock") { holding.load() }
            val waiter = async(Dispatchers.Default) {
                lock.exclusive {
                    waiterEntered.store(true)
                    waiterSawHolderFinished.store(holderFinished.load())
                }
            }
            // The waiter cannot get in while the holder is parked; give it a real chance to try.
            val grace: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() + 100.milliseconds
            while (!grace.hasPassedNow()) {
                if (waiterEntered.load()) waiterEnteredEarly.store(true)
            }
            release.store(true)
            awaitAll(holder, waiter)
        }

        assertFalse(waiterEnteredEarly.load(), "exclusive must block while another thread holds the lock")
        assertTrue(waiterEntered.load())
        assertTrue(waiterSawHolderFinished.load(), "the waiter must enter only after the holder left")
    }

    @Test
    fun `many threads mixing exclusive and submit never overlap and lose nothing`() = runTest {
        val lock = StateMachineLock()
        val inside = AtomicInt(0)
        val maxInside = AtomicInt(0)
        val overlaps = AtomicInt(0)
        // Deliberately plain: only mutual exclusion keeps these exact.
        var exclusiveRuns = 0
        var submitRuns = 0

        fun critical(bump: () -> Unit) {
            val now: Int = inside.incrementAndFetch()
            if (now > 1) overlaps.incrementAndFetch()
            if (now > maxInside.load()) maxInside.store(now)
            bump()
            inside.decrementAndFetch()
        }

        val workers = 8
        val rounds = 2_000
        withContext(Dispatchers.Default) {
            (0 until workers).map { worker: Int ->
                async {
                    for (round: Int in 0 until rounds) {
                        if ((round + worker) % 2 == 0) {
                            lock.exclusive { critical { exclusiveRuns++ } }
                        } else {
                            lock.submit { critical { submitRuns++ } }
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(0, overlaps.load(), "two actions ran at the same time")
        assertEquals(workers * rounds, exclusiveRuns + submitRuns, "an action was lost or run twice")
        assertEquals(workers * rounds / 2, exclusiveRuns)
        assertEquals(workers * rounds / 2, submitRuns)
    }

    private inline fun spinUntil(what: String, condition: () -> Boolean) {
        val deadline: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() + 10.seconds
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("Timed out waiting for $what")
        }
    }
}

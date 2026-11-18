package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Contract of the timed [KeyedMutex.withLock]: the wait for the key is bounded by one timeout, the action is never
 * timed, a timeout releases everything it registered, and a lock won in the instant of the timeout or of a
 * cancellation is used or released, never leaked. All waits run on virtual time; every join is bounded by `runTest`.
 * Group `sto-desktop` (the one 10 s budget of a store write that takes this lock and then a file lock).
 */
@OptIn(ToolkitInternalApi::class, ExperimentalCoroutinesApi::class)
class KeyedMutexTimedWithLockTest {

    private val timeout: Duration = 10.seconds

    @Test
    fun `a free key runs the action and returns its value`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val answer: String = mutex.withLock("k", timeout, onTimeout = { "timeout" }) { "ran" }
        assertEquals("ran", answer)
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a held key answers onTimeout at exactly the timeout and leaves the registry as it was`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        assertEquals(1, mutex.activeKeyCount, "control: the key is registered while it is held")

        var actionRan: Boolean = false
        val before: Long = testScheduler.currentTime
        val answer: String = mutex.withLock("k", timeout, onTimeout = { "timeout" }) { actionRan = true; "ran" }

        assertEquals("timeout", answer)
        assertFalse(actionRan, "the action never runs after a timeout")
        assertEquals(timeout.inWholeMilliseconds, testScheduler.currentTime - before, "the wait ends at the timeout, not before or after")
        release.complete(Unit)
        holder.join()
        assertEquals(0, mutex.activeKeyCount, "a timed-out waiter must not leave a user behind in the registry")
        assertEquals("again", mutex.withLock("k") { "again" }, "the key is usable afterwards")
    }

    @Test
    fun `five queued waiters all answer at one timeout and not at the sum of the timeouts`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        val start: Long = testScheduler.currentTime
        val answeredAt: MutableList<Long> = mutableListOf()
        val waiters: List<Deferred<Unit>> = List(5) {
            async(start = CoroutineStart.UNDISPATCHED) {
                mutex.withLock("k", timeout, onTimeout = { answeredAt += testScheduler.currentTime - start }) { }
            }
        }
        waiters.forEach { it.await() }
        assertEquals(List(5) { timeout.inWholeMilliseconds }, answeredAt, "every waiter answers at exactly one timeout")
        release.complete(Unit)
        holder.join()
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `the action is never timed even when it runs for many timeouts`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val before: Long = testScheduler.currentTime
        val answer: String = mutex.withLock("k", timeout, onTimeout = { "timeout" }) {
            delay(timeout * 3)
            "ran"
        }
        assertEquals("ran", answer)
        assertEquals((timeout * 3).inWholeMilliseconds, testScheduler.currentTime - before)
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a waiter behind a long action gets the key when it is released inside the timeout`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); delay(timeout - 1.seconds) } }
        holding.await()
        val answer: String = mutex.withLock("k", timeout, onTimeout = { "timeout" }) { "ran" }
        assertEquals("ran", answer)
        holder.join()
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a zero or negative timeout does not wait and does not try the key`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        for (zero: Duration in listOf(Duration.ZERO, (-1).seconds)) {
            val before: Long = testScheduler.currentTime
            var actionRan: Boolean = false
            val answer: String = mutex.withLock("k", zero, onTimeout = { "timeout" }) { actionRan = true; "ran" }
            assertEquals("timeout", answer, "timeout=$zero")
            assertFalse(actionRan)
            assertEquals(before, testScheduler.currentTime)
            assertEquals(0, mutex.activeKeyCount, "timeout=$zero")
        }
    }

    @Test
    fun `an infinite timeout waits like withLock`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        val waiter: Deferred<String> = async { mutex.withLock("k", Duration.INFINITE, onTimeout = { "timeout" }) { "ran" } }
        delay(1_000.seconds)
        assertFalse(waiter.isCompleted, "control: still waiting after a long virtual time")
        release.complete(Unit)
        assertEquals("ran", waiter.await())
        holder.join()
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a waiter cancelled during the timed wait leaves the holder and the registry intact`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        var actionRan: Boolean = false
        var timedOut: Boolean = false
        val waiter: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock("k", timeout, onTimeout = { timedOut = true }) { actionRan = true }
        }
        delay(timeout / 2)
        assertTrue(waiter.isActive, "control: the waiter is waiting")
        waiter.cancelAndJoin()
        assertFalse(actionRan)
        assertFalse(timedOut, "a cancelled waiter does not answer onTimeout")
        assertEquals(1, mutex.activeKeyCount, "only the holder's key remains")
        release.complete(Unit)
        holder.join()
        assertEquals(0, mutex.activeKeyCount)
        assertEquals("again", mutex.withLock("k", timeout, onTimeout = { "timeout" }) { "again" })
    }

    @Test
    fun `a waiter cancelled in the very instant the key is handed to it neither runs nor leaks the key`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        var actionRan: Boolean = false
        lateinit var waiter: Job
        val holder: Job = launch {
            mutex.withLock("k") { holding.complete(Unit); release.await() }
            // The key was just handed to the waiter, whose resumption is still queued: cancel it in that instant.
            waiter.cancel()
        }
        holding.await()
        waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock("k", timeout, onTimeout = { }) { actionRan = true }
        }
        assertEquals(1, mutex.activeKeyCount, "control: the key is registered")
        release.complete(Unit)
        holder.join()
        waiter.join()

        assertTrue(waiter.isCancelled)
        assertFalse(actionRan, "a cancelled waiter must not run its action")
        assertEquals(0, mutex.activeKeyCount)
        assertEquals("free", mutex.withLock("k", timeout, onTimeout = { "timeout" }) { "free" }, "the key was not leaked")
    }

    @Test
    fun `an action that throws releases the key`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val failure: Result<String> = runCatching {
            mutex.withLock("k", timeout, onTimeout = { "timeout" }) { throw IllegalStateException("boom") }
        }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(0, mutex.activeKeyCount)
        assertEquals("again", mutex.withLock("k", timeout, onTimeout = { "timeout" }) { "again" })
    }

    @Test
    fun `a key released in the instant of the timeout is either used or left free and never leaked`() = runTest {
        // seed=20261115: the holder releases a few milliseconds before, exactly at, and after the waiter's timeout.
        for (offset: Long in listOf(-2L, -1L, 0L, 1L, 2L)) {
            val mutex: KeyedMutex<String> = KeyedMutex()
            val holding: CompletableDeferred<Unit> = CompletableDeferred()
            val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); delay(timeout + offset.milliseconds) } }
            holding.await()
            var actionRan: Boolean = false
            val answer: String = mutex.withLock("k", timeout, onTimeout = { "timeout" }) { actionRan = true; "ran" }
            holder.join()
            assertEquals(if (actionRan) "ran" else "timeout", answer, "seed=20261115 offset=$offset")
            assertEquals(0, mutex.activeKeyCount, "seed=20261115 offset=$offset: nothing registered afterwards")
            assertEquals("free", mutex.withLock("k") { "free" }, "seed=20261115 offset=$offset: the key is free afterwards")
        }
    }

    @Test
    fun `seeded storm of timed and untimed callers never overlaps and leaves no registry entry`() = runTest {
        val seed: Long = 20261115L
        val mutex: KeyedMutex<String> = KeyedMutex()
        val inside: IntArray = IntArray(3)
        var peak: Int = 0
        var ran: Int = 0
        var timedOut: Int = 0
        val jobs: List<Job> = List(40) { worker: Int ->
            launch {
                val random: Random = Random(seed * 1000 + worker)
                repeat(25) {
                    val index: Int = random.nextInt(3)
                    val key: String = "k$index"
                    val hold: Duration = random.nextInt(0, 400).milliseconds
                    val action: suspend () -> Unit = {
                        inside[index]++
                        peak = maxOf(peak, inside[index])
                        delay(hold)
                        inside[index]--
                    }
                    if (random.nextBoolean()) {
                        mutex.withLock(key, random.nextInt(1, 600).milliseconds, onTimeout = { timedOut++ }) { ran++; action() }
                    } else {
                        mutex.withLock(key) { ran++; action() }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(0, mutex.activeKeyCount, "seed=$seed")
        assertEquals(1, peak, "seed=$seed: two callers of one key overlapped")
        assertTrue(inside.all { it == 0 }, "seed=$seed")
        assertTrue(timedOut > 0 && ran > 0, "seed=$seed: control, both outcomes happened (ran=$ran timedOut=$timedOut)")
    }
}

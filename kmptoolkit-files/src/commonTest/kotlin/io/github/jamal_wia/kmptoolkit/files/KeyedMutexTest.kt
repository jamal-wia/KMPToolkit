package io.github.jamal_wia.kmptoolkit.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** M-1..M-10: the contract of [KeyedMutex]. Every wait is bounded in real time. */
@OptIn(ExperimentalAtomicApi::class)
class KeyedMutexTest {

    @Test
    fun `callers of one key run one at a time`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        var inside: Int = 0
        var peak: Int = 0
        val jobs: List<Job> = List(10) {
            launch {
                mutex.withLock("k") {
                    inside++
                    peak = maxOf(peak, inside)
                    yield()
                    yield()
                    inside--
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, peak)
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `callers of different keys never wait for each other`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("a") { holding.complete(Unit); release.await() } }
        holding.await()
        // The other key completes while "a" is still held.
        assertEquals("done", mutex.withLock("b") { "done" })
        assertEquals(1, mutex.activeKeyCount)
        release.complete(Unit)
        holder.join()
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a caller cancelled while waiting leaves the key usable and the registry empty`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        var ran: Boolean = false
        val waiter: Job = launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock("k") { ran = true } }
        waiter.cancelAndJoin()
        assertFalse(ran)
        release.complete(Unit)
        holder.join()
        assertEquals("after", mutex.withLock("k") { "after" })
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a caller cancelled while running releases the key`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val inside: CompletableDeferred<Unit> = CompletableDeferred()
        val runner: Job = launch { mutex.withLock("k") { inside.complete(Unit); CompletableDeferred<Unit>().await() } }
        inside.await()
        runner.cancelAndJoin()
        assertEquals(0, mutex.activeKeyCount)
        assertEquals(1, mutex.withLock("k") { 1 })
    }

    @Test
    fun `an action that throws releases the key and the exception propagates`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        assertFailsWith<IllegalStateException> { mutex.withLock("k") { error("boom") } }
        assertEquals(0, mutex.activeKeyCount)
        assertEquals(2, mutex.withLock("k") { 2 })
    }

    @Test
    fun `tryWithLock runs when the key is free and reports it`() {
        val mutex: KeyedMutex<String> = KeyedMutex()
        var ran: Boolean = false
        assertTrue(mutex.tryWithLock("k") { ran = true })
        assertTrue(ran)
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `tryWithLock is false while a withLock holds the key and does not run its action`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        var ran: Boolean = false
        assertFalse(mutex.tryWithLock("k") { ran = true })
        assertFalse(ran)
        assertTrue(mutex.tryWithLock("other") { })
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `tryWithLock is false while a caller only awaits the key`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        // The waiter is registered and suspended in lock().
        val waiter: Job = launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock("k") { } }
        assertTrue(waiter.isActive, "the waiter must be suspended behind the holder")
        assertFalse(mutex.tryWithLock("k") { }, "held by the holder and awaited")
        // The holder is released but nothing has been resumed yet: no yield, no join before the check.
        release.complete(Unit)
        assertTrue(waiter.isActive && holder.isActive, "the test dispatcher has resumed nobody yet")
        assertFalse(mutex.tryWithLock("k") { }, "released but still awaited: the key is registered")
        holder.join()
        waiter.join()
        assertTrue(mutex.tryWithLock("k") { })
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a withLock that arrives while a try action runs waits for it`() = runTest(timeout = 60.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val order: MutableList<String> = mutableListOf()
        val reachedLock: CompletableDeferred<Unit> = CompletableDeferred()
        var waiter: Job? = null
        withContext(Dispatchers.Default) {
            val ran: Boolean = mutex.tryWithLock("k") {
                waiter = launch {
                    reachedLock.complete(Unit)
                    mutex.withLock("k") { order += "waiter" }
                }
                while (!reachedLock.isCompleted) Unit
                // The waiter is about to call withLock; the action is allowed to be short, 200 ms is enough for it
                // to register and suspend. An implementation that does not hold the key lets it run right away.
                val begin = TimeSource.Monotonic.markNow()
                while (begin.elapsedNow() < 200.milliseconds) Unit
                order += "try-end"
            }
            assertTrue(ran)
            withTimeout(30_000) { waiter?.join() }
        }
        assertEquals(listOf("try-end", "waiter"), order)
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a try action that throws releases the key`() {
        val mutex: KeyedMutex<String> = KeyedMutex()
        assertFailsWith<IllegalStateException> { mutex.tryWithLock("k") { error("boom") } }
        assertEquals(0, mutex.activeKeyCount)
        assertTrue(mutex.tryWithLock("k") { })
    }

    @Test
    fun `a storm of withLock and tryWithLock with cancellations never overlaps and leaks nothing`() =
        runTest(timeout = 180.seconds) {
            val mutex: KeyedMutex<Int> = KeyedMutex()
            val keys: Int = 4
            val inside: List<AtomicInt> = List(keys) { AtomicInt(0) }
            val overlaps: AtomicInt = AtomicInt(0)
            val completed: AtomicInt = AtomicInt(0)
            withContext(Dispatchers.Default) {
                withTimeout(150_000) {
                    coroutineScope {
                        val jobs: List<Job> = List(64) { index ->
                            launch {
                                repeat(500) { iteration ->
                                    val key: Int = (index + iteration) % keys
                                    if (iteration % 7 == 0) {
                                        mutex.tryWithLock(key) {
                                            if (inside[key].addAndFetch(1) != 1) overlaps.addAndFetch(1)
                                            inside[key].addAndFetch(-1)
                                        }
                                    } else {
                                        mutex.withLock(key) {
                                            if (inside[key].addAndFetch(1) != 1) overlaps.addAndFetch(1)
                                            // A cancellation thrown by yield() must still leave the counter balanced.
                                            try {
                                                if (iteration % 3 == 0) yield()
                                            } finally {
                                                inside[key].addAndFetch(-1)
                                            }
                                        }
                                    }
                                    completed.addAndFetch(1)
                                }
                            }
                        }
                        // A third of the coroutines are cancelled mid-flight.
                        jobs.filterIndexed { index, _ -> index % 3 == 0 }.forEach { it.cancel() }
                    }
                }
            }
            assertEquals(0, overlaps.load(), "two callers of one key ran together")
            assertEquals(0, mutex.activeKeyCount, "the registry leaked")
            assertTrue(completed.load() > 0)
        }

    @Test
    fun `uncancelled callers of one key all complete in a parallel storm`() = runTest(timeout = 120.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        var counter: Int = 0
        withContext(Dispatchers.Default) {
            withTimeout(100_000) {
                List(16) {
                    async { repeat(300) { mutex.withLock("one") { val read: Int = counter; yield(); counter = read + 1 } } }
                }.awaitAll()
            }
        }
        assertEquals(16 * 300, counter)
        assertEquals(0, mutex.activeKeyCount)
    }
}

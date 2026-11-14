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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A key whose hash code is the same for every instance: the registry must tell keys apart by equals alone. */
private data class CollidingKey(val id: Int) {
    override fun hashCode(): Int = 42
}

/** Round 1 of the breaker for unit G: KeyedMutex and PathLocks storms on real threads with cancellation at random points. */
@OptIn(ExperimentalAtomicApi::class)
class KeyedMutexStormR1Test {

    @Test
    fun `a storm of withLock tryWithLock and cancelled waiters on colliding keys never overlaps and leaks nothing`() = runTest(timeout = 180.seconds) {
        val mutex: KeyedMutex<CollidingKey> = KeyedMutex()
        val keyCount: Int = 6
        val inside: List<AtomicInt> = List(keyCount) { AtomicInt(0) }
        val violations: AtomicInt = AtomicInt(0)
        val entered: AtomicInt = AtomicInt(0)
        withContext(Dispatchers.Default) {
            withTimeout(120.seconds) {
                coroutineScope {
                    repeat(48) { worker: Int ->
                        launch {
                            val random: Random = Random(1000 + worker)
                            repeat(500) {
                                val index: Int = random.nextInt(keyCount)
                                val key: CollidingKey = CollidingKey(index)
                                val critical: suspend () -> Unit = {
                                    if (inside[index].addAndFetch(1) != 1) violations.addAndFetch(1)
                                    try {
                                        entered.addAndFetch(1)
                                        repeat(random.nextInt(0, 3)) { yield() }
                                    } finally {
                                        inside[index].addAndFetch(-1)
                                    }
                                }
                                when (random.nextInt(4)) {
                                    0 -> mutex.withLock(key, critical)
                                    1 -> mutex.tryWithLock(key) {
                                        if (inside[index].addAndFetch(1) != 1) violations.addAndFetch(1)
                                        entered.addAndFetch(1)
                                        inside[index].addAndFetch(-1)
                                    }
                                    else -> {
                                        val waiter: Job = launch { mutex.withLock(key, critical) }
                                        repeat(random.nextInt(0, 4)) { yield() }
                                        waiter.cancelAndJoin()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(0, violations.load(), "two callers of one key ran at the same time")
        assertEquals(0, mutex.activeKeyCount, "the registry leaked a key")
        assertTrue(entered.load() > 0)
        for (index: Int in 0 until keyCount) {
            assertEquals(0, inside[index].load())
            mutex.withLock(CollidingKey(index)) { }
        }
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `a waiter cancelled in the instant the holder releases never strands the key`() = runTest(timeout = 180.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        withContext(Dispatchers.Default) {
            withTimeout(120.seconds) {
                repeat(3_000) { round: Int ->
                    val holding: CompletableDeferred<Unit> = CompletableDeferred()
                    val release: CompletableDeferred<Unit> = CompletableDeferred()
                    val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
                    holding.await()
                    val waiters: List<Job> = List(3) { launch { mutex.withLock("k") { yield() } } }
                    yield()
                    // Release and cancel from two threads at once.
                    val canceller: Job = launch(Dispatchers.Default) { waiters[round % 3].cancel() }
                    release.complete(Unit)
                    canceller.join()
                    holder.join()
                    waiters.forEach { it.join() }
                    assertEquals(0, mutex.activeKeyCount, "round $round leaked a key")
                    assertTrue(mutex.tryWithLock("k") { }, "round $round left the key held")
                }
            }
        }
    }

    @Test
    fun `waiters of one key acquire in arrival order and a cancelled waiter does not reorder the rest`() = runTest(timeout = 60.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val order: MutableList<Int> = ArrayList()
        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); release.await() } }
        holding.await()
        val waiters: List<Job> = List(10) { index: Int ->
            // UNDISPATCHED: the coroutine runs up to its first suspension, which is the wait for the lock, so the
            // arrival order is the loop order.
            launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock("k") { order += index } }
        }
        waiters[3].cancel()
        waiters[7].cancel()
        release.complete(Unit)
        holder.join()
        waiters.forEach { it.join() }
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 8, 9), order)
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `five hundred keys with one hash code are all held at once`() = runTest(timeout = 120.seconds) {
        val mutex: KeyedMutex<CollidingKey> = KeyedMutex()
        val total: Int = 500
        val arrived: AtomicInt = AtomicInt(0)
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val proceed: CompletableDeferred<Unit> = CompletableDeferred()
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                val holders: List<Job> = List(total) { id: Int ->
                    launch { mutex.withLock(CollidingKey(id)) { if (arrived.addAndFetch(1) == total) gate.complete(Unit); proceed.await() } }
                }
                gate.await()
                assertEquals(total, mutex.activeKeyCount)
                assertFalse(mutex.tryWithLock(CollidingKey(total - 1)) { }, "a held key must refuse")
                assertTrue(mutex.tryWithLock(CollidingKey(total + 1)) { }, "a fresh key with the same hash code must run")
                proceed.complete(Unit)
                holders.forEach { it.join() }
            }
        }
        assertEquals(0, mutex.activeKeyCount)
    }

    @Test
    fun `an Error thrown by the action releases the key and propagates`() = runTest(timeout = 30.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        assertFailsWith<AssertionError> { mutex.withLock("k") { throw AssertionError("boom") } }
        assertEquals(0, mutex.activeKeyCount)
        assertFailsWith<AssertionError> { mutex.tryWithLock("k") { throw AssertionError("boom") } }
        assertEquals(0, mutex.activeKeyCount)
        assertTrue(mutex.tryWithLock("k") { })
    }

    @Test
    fun `a CancellationException thrown by the action itself releases the key without cancelling the caller`() = runTest(timeout = 30.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val outcome: Result<Unit> = runCatching { mutex.withLock("k") { throw kotlinx.coroutines.CancellationException("self") } }
        assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, mutex.activeKeyCount)
        mutex.withLock("k") { }
    }

    @Test
    fun `tryWithLock racing withLock registers exactly one runner at a time on real threads`() = runTest(timeout = 120.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val inside: AtomicInt = AtomicInt(0)
        val violations: AtomicInt = AtomicInt(0)
        val tries: AtomicInt = AtomicInt(0)
        val successes: AtomicInt = AtomicInt(0)
        withContext(Dispatchers.Default) {
            withTimeout(90.seconds) {
                List(16) { worker: Int ->
                    async {
                        repeat(2_000) {
                            if (worker % 2 == 0) {
                                mutex.withLock("k") {
                                    if (inside.addAndFetch(1) != 1) violations.addAndFetch(1)
                                    inside.addAndFetch(-1)
                                }
                            } else {
                                tries.addAndFetch(1)
                                val ran: Boolean = mutex.tryWithLock("k") {
                                    if (inside.addAndFetch(1) != 1) violations.addAndFetch(1)
                                    inside.addAndFetch(-1)
                                }
                                if (ran) successes.addAndFetch(1)
                                yield()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        assertEquals(0, violations.load())
        assertEquals(0, mutex.activeKeyCount)
        assertTrue(successes.load() <= tries.load())
    }

    @Test
    fun `PathLocks serializes spellings of one path and lets another path through under a storm`() = runTest(timeout = 120.seconds) {
        val inside: AtomicInt = AtomicInt(0)
        val violations: AtomicInt = AtomicInt(0)
        val spellings: List<String> = listOf("/d/File.txt", "/d/file.TXT", "/d/x/../File.txt", "/d//FILE.txt", "/d/./file.txt", "/d/Café.txt")
        withContext(Dispatchers.Default) {
            withTimeout(90.seconds) {
                List(12) { worker: Int ->
                    async {
                        repeat(300) {
                            val spelling: String = spellings[(worker + it) % 5]
                            PathLocks.withLock(spelling) {
                                if (inside.addAndFetch(1) != 1) violations.addAndFetch(1)
                                yield()
                                inside.addAndFetch(-1)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        assertEquals(0, violations.load(), "two spellings of one path ran at the same time")
        // The sixth spelling is a different file: it must not wait for a holder of the first five.
        PathLocks.withLock(spellings[0]) {
            assertTrue(PathLocks.tryWithLock(spellings[5]) { }, "a different path must not wait")
            assertFalse(PathLocks.tryWithLock(spellings[3]) { }, "a spelling of the held path must wait")
        }
        assertTrue(PathLocks.tryWithLock(spellings[0]) { }, "the registry of PathLocks leaked a key")
    }

    @Test
    fun `five thousand distinct keys held at once are all registered and all released`() = runTest(timeout = 300.seconds) {
        val mutex: KeyedMutex<String> = KeyedMutex()
        val total: Int = 5_000
        val arrived: AtomicInt = AtomicInt(0)
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val proceed: CompletableDeferred<Unit> = CompletableDeferred()
        val started: TimeMark = TimeSource.Monotonic.markNow()
        withContext(Dispatchers.Default) {
            withTimeout(240.seconds) {
                val holders: List<Job> = List(total) { id: Int ->
                    launch { mutex.withLock("/data/dir/file-$id") { if (arrived.addAndFetch(1) == total) gate.complete(Unit); proceed.await() } }
                }
                gate.await()
                println("R1-OBSERVATION registering $total concurrent keys took ${started.elapsedNow()}")
                assertEquals(total, mutex.activeKeyCount)
                proceed.complete(Unit)
                holders.forEach { it.join() }
            }
        }
        println("R1-OBSERVATION registering and releasing $total concurrent keys took ${started.elapsedNow()}")
        assertEquals(0, mutex.activeKeyCount)
    }
}

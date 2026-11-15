package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationUpdatesOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 1 of group loc-jvm-fakes (BREAKER-REVIEW.md, D-23): tests that try to break
 * [FakeLocationProvider] against its stated contract (PLAN 3.5, D8, D32, the class KDoc "its stream
 * ends exactly like the real providers'").
 *
 * Targets: a terminal reading lost to conflation (wrong terminal state), re-entrant use from inside a
 * collector and from inside a released call, hold/release lost wakeups and stranded callers under
 * real threads, release-proof counters going negative or sticking when cancellation races
 * registration and the terminal completion, and the replay of a terminal reading to collectors that
 * start after a storm. Storms use `Random(seed ...)`; every failure message starts with `seed=`.
 * The real-time bounds are hang bounds only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLocationProviderBreakerR1Test {

    private val workers = 8

    // ---- terminal reading vs conflation (PLAN D8 / D32 / 3.5 emit KDoc) --------------------------

    /**
     * A collector that has not yet taken the terminal reading when the test emits the next reading
     * (the everyday `runTest` shape: two emits with no yield between) must still end on the terminal
     * reading: a real provider never delivers anything after PERMISSION_MISSING or UNSUPPORTED to the
     * same collection, so the code under test must see the stream end.
     *
     * Mutation by reading: this is RED while the shared replay-1 DROP_OLDEST source lets the next
     * emit overwrite the terminal reading before the collector reads it.
     */
    @Test
    fun `a terminal reading followed at once by another emit still completes a live collector`() = runTest {
        for (terminal in listOf(permissionMissing, unsupported)) {
            val provider = FakeLocationProvider()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { provider.observeLocation().collect { seen += it } }
            runCurrent()
            assertEquals(listOf(noFix), seen, "terminal=$terminal control: the collector is live and took the seed")
            assertEquals(1, provider.activeObservers.value, "terminal=$terminal control")

            provider.emit(terminal)
            provider.emit(available(10.0, 20.0)) // e.g. the test "grants permission again" right away
            runCurrent()

            assertEquals(listOf(noFix, terminal), seen, "terminal=$terminal: the live collection must end on the terminal reading")
            assertTrue(job.isCompleted, "terminal=$terminal: the live collection must have completed")
            assertEquals(0, provider.activeObservers.value, "terminal=$terminal: and released")
            // The new reading still serves the next collection (re-initialisation after release).
            assertEquals(available(10.0, 20.0), provider.observeLocation().first(), "terminal=$terminal")
        }
    }

    /** The same rule for a collector that is busy in its own body when the terminal reading and a later one arrive. */
    @Test
    fun `a busy collector still ends on a terminal reading emitted while it was busy`() = runTest {
        val provider = FakeLocationProvider()
        val gate = CompletableDeferred<Unit>()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation().collect {
                seen += it
                if (seen.size == 1) gate.await() // busy with the seed
            }
        }
        runCurrent()
        assertEquals(listOf(noFix), seen, "control: busy with the seed")

        provider.emit(available(1.0, 1.0))
        provider.emit(permissionMissing)
        provider.emit(available(2.0, 2.0))
        gate.complete(Unit)
        runCurrent()

        assertEquals(permissionMissing, seen.last(), "seen=$seen: the collection must end on the terminal reading")
        assertTrue(job.isCompleted, "seen=$seen")
        assertEquals(0, provider.activeObservers.value)
    }

    // ---- re-entrancy ----------------------------------------------------------------------------

    @Test
    fun `the fake can be driven from inside its own collector without deadlock or a wrong count`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.currentLocationAnswer = available(5.0, 5.0)
        val inner: MutableList<String> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(minUpdateDistanceMeters = 3.0)).collect { reading ->
                // Every public member, from inside the collection (the recorder lock must not be held here).
                inner += "observers=${provider.activeObservers.value} options=${provider.activeUpdatesOptions.size} answer=${provider.getCurrentLocation() == available(5.0, 5.0)}"
                assertEquals(reading, provider.observeLocation().first(), "a nested collection sees the same latest reading")
                if (reading == available(1.0, 1.0)) provider.emit(permissionMissing) // end my own stream from inside
            }
        }
        provider.emit(available(1.0, 1.0))

        assertTrue(job.isCompleted, "inner=$inner: the emit from inside the collector ended the stream")
        assertEquals(listOf("observers=1 options=1 answer=true", "observers=1 options=1 answer=true", "observers=1 options=1 answer=true"), inner)
        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeUpdatesOptions.isEmpty())
        assertEquals(3, provider.currentLocationCalls.size)
    }

    @Test
    fun `a released call that holds again from its own continuation holds only the calls made after it`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val first: LocationReading = available(1.0, 1.0, timestamp = 1)
        val second: LocationReading = available(2.0, 2.0, timestamp = 2)
        provider.holdCurrentLocation()
        val again = CompletableDeferred<Deferred<LocationReading>>()

        val a = backgroundScope.async {
            val answer: LocationReading = provider.getCurrentLocation()
            provider.holdCurrentLocation() // re-entrant: from inside the release of the first hold
            again.complete(backgroundScope.async { provider.getCurrentLocation() })
            answer
        }
        val b = backgroundScope.async { provider.getCurrentLocation() }
        assertEquals(2, provider.pendingCurrentLocationCalls.value, "control: two held")

        provider.releaseCurrentLocation(first)

        assertEquals(first, a.await())
        assertEquals(first, b.await(), "a call held under the first hold must get the first release, not be caught by the new hold")
        val c: Deferred<LocationReading> = again.await()
        assertEquals(1, provider.pendingCurrentLocationCalls.value, "only the call made after the re-hold is held")
        provider.releaseCurrentLocation(second)
        assertEquals(second, c.await())
        assertEquals(0, provider.pendingCurrentLocationCalls.value)
    }

    // ---- real threads ---------------------------------------------------------------------------

    /**
     * Hold, a batch of callers on real threads, a random subset cancelled while the release runs on
     * another thread: no caller is stranded (every join returns), a caller that answered got the
     * release of its own round, and the pending count never goes negative and returns to 0.
     */
    @Test
    fun `cancel racing release on real threads strands no call and never drives the pending count negative`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 101
        val provider = FakeLocationProvider()
        provider.currentLocationAnswer = noFix
        val negative = CompletableDeferred<Int>()

        withContext(Dispatchers.Default) {
            val watcher: Job = launch { provider.pendingCurrentLocationCalls.collect { if (it < 0) negative.complete(it) } }
            withTimeout(100.seconds) {
                val rng = Random(seed)
                repeat(300) { round ->
                    val release: LocationReading = available((round % 90).toDouble(), 0.0, timestamp = round.toLong())
                    provider.holdCurrentLocation()
                    val callers: List<Deferred<LocationReading>> = List(workers) { async(start = CoroutineStart.UNDISPATCHED) { provider.getCurrentLocation() } }
                    val cancels: List<Job> = callers.filter { rng.nextBoolean() }.map { caller -> launch { caller.cancel() } }
                    val releaser: Job = launch { provider.releaseCurrentLocation(release) }
                    (cancels + releaser).joinAll()
                    callers.forEachIndexed { index, caller ->
                        caller.join()
                        if (!caller.isCancelled) {
                            val answer: LocationReading = caller.await()
                            // Every caller was held (UNDISPATCHED: it recorded and suspended before the release), so it gets this round's release.
                            assertEquals(release, answer, "seed=$seed round=$round caller=$index answered $answer")
                        }
                    }
                    assertEquals(0, provider.pendingCurrentLocationCalls.value, "seed=$seed round=$round nothing pending after the round")
                }
            }
            watcher.cancelAndJoin()
        }
        assertTrue(!negative.isCompleted, "seed=$seed the pending count went negative")
        assertEquals(300 * workers, provider.currentLocationCalls.size, "seed=$seed every call was recorded once, cancelled or not")
    }

    /**
     * Collectors started (UNDISPATCHED or not) and cancelled on real threads while a publisher emits a
     * mix in which terminal readings complete collections: cancellation races both the registration
     * and the terminal completion. The observer count must never go negative, must equal the recorded
     * options once quiet, and must return to 0. Then, with a terminal reading last, every new collector
     * on any thread must complete with exactly that reading (replay of the terminal state).
     */
    @Test
    fun `cancel racing registration and terminal completion keeps the observer count exact`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 102
        val provider = FakeLocationProvider()
        val negative = CompletableDeferred<Int>()

        withContext(Dispatchers.Default) {
            val watcher: Job = launch { provider.activeObservers.collect { if (it < 0) negative.complete(it) } }
            withTimeout(100.seconds) {
                val publisher = async {
                    val rng = Random(seed)
                    repeat(20_000) { step ->
                        provider.emit(
                            when (rng.nextInt(6)) {
                                0 -> permissionMissing
                                1 -> unsupported
                                2 -> serviceDisabled
                                3 -> noFix
                                else -> available(rng.nextDouble(-90.0, 90.0), rng.nextDouble(-180.0, 180.0), timestamp = step.toLong())
                            },
                        )
                    }
                }
                (0 until workers - 1).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        repeat(400) {
                            val start: CoroutineStart = if (rng.nextBoolean()) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
                            val job: Job = launch(start = start) { provider.observeLocation(LocationUpdatesOptions(minUpdateDistanceMeters = worker.toDouble())).collect { } }
                            if (rng.nextBoolean()) job.cancelAndJoin() else job.cancel()
                        }
                    }
                }.awaitAll()
                publisher.await()
            }
            watcher.cancelAndJoin()
        }
        assertTrue(!negative.isCompleted, "seed=$seed the observer count went negative")

        // Quiet: no collector is left except ones that are still live, and the two views agree.
        provider.emit(unsupported)
        withContext(Dispatchers.Default) {
            withTimeout(30.seconds) {
                provider.activeObservers.first { it == 0 }
            }
        }
        assertEquals(0, provider.activeObservers.value, "seed=$seed every registration released")
        assertTrue(provider.activeUpdatesOptions.isEmpty(), "seed=$seed no option left recorded")

        val replays: List<List<LocationReading>> = withContext(Dispatchers.Default) {
            withTimeout(30.seconds) { List(workers * 4) { async { provider.observeLocation().toList() } }.awaitAll() }
        }
        replays.forEachIndexed { index, list -> assertEquals(listOf(unsupported), list, "seed=$seed collector=$index after a terminal last reading") }
        assertEquals(0, provider.activeObservers.value, "seed=$seed")
    }
}

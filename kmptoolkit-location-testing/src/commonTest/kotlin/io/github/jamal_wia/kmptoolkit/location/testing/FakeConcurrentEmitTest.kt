package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.CurrentLocationOptions
import io.github.jamal_wia.kmptoolkit.location.LocationAccuracy
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability
import io.github.jamal_wia.kmptoolkit.location.LocationUpdatesOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The fake is documented as safe to call from any thread: eight workers on real threads, 10 000
 * operations each, every operation drawn from `Random(seed * 1000 + worker)`, never fail, never
 * strand a held call, and leave the counters and recorders exactly consistent with what was done
 * (PLAN 6.2 "Thread safety", replaces the 4.x `FakeConcurrentEmitTest`). Every failure message
 * starts with `seed=`. Group loc-jvm-fakes.
 *
 * The bounds are hang bounds only: a real deadlock fails the test instead of freezing the build.
 */
class FakeConcurrentEmitTest {

    private val workers = 8
    private val steps = 10_000

    private fun randomReading(rng: Random, worker: Int, step: Int): LocationReading = when (rng.nextInt(10)) {
        0 -> noFix
        1 -> serviceDisabled
        2 -> if (rng.nextBoolean()) permissionMissing else unsupported
        else -> available(rng.nextDouble(-90.0, 90.0), rng.nextDouble(-180.0, 180.0), timestamp = worker * 1_000_000L + step)
    }

    private class Done(val calls: Int, val prompts: Int, val settings: Int, val problems: List<String>)

    @Test
    fun `a storm of emits calls collectors prompts and setting changes leaves every counter exact`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED
        val provider = FakeLocationProvider()
        provider.currentLocationAnswer = available(1.0, 1.0)

        // Control: one long-lived collector exists for the whole storm and is released at the end.
        val resident = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { provider.observeLocation().collect { } }
        assertTrue(provider.activeObservers.value >= 1, "seed=$seed control: the resident collector is registered")

        val results: List<Done> = withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                (0 until workers).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        var calls = 0
                        var prompts = 0
                        var settings = 0
                        val problems: MutableList<String> = mutableListOf()
                        repeat(steps) { step ->
                            when (rng.nextInt(8)) {
                                0, 1 -> provider.emit(randomReading(rng, worker, step))
                                2 -> {
                                    calls++
                                    val answer: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(accuracy = LocationAccuracy.entries[rng.nextInt(3)]))
                                    if (answer != available(1.0, 1.0)) problems += "seed=$seed worker=$worker step=$step: one-shot answered $answer"
                                }
                                3 -> {
                                    // A fresh collection sees a reading at once (it never hangs: the stream always
                                    // has a latest value) and releases itself when first() stops it.
                                    provider.observeLocation(LocationUpdatesOptions(minUpdateDistanceMeters = rng.nextInt(100).toDouble())).first()
                                }
                                4 -> {
                                    prompts++
                                    provider.promptToEnableService()
                                }
                                5 -> {
                                    settings++
                                    provider.openLocationSettings()
                                }
                                6 -> provider.locationEnabled = rng.nextBoolean()
                                else -> provider.isLocationEnabled()
                            }
                        }
                        Done(calls, prompts, settings, problems)
                    }
                }.awaitAll()
            }
        }

        val problems: List<String> = results.flatMap { it.problems }
        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
        assertEquals(results.sumOf { it.calls }, provider.currentLocationCalls.size, "seed=$seed every one-shot call is recorded exactly once")
        assertEquals(results.sumOf { it.prompts }, provider.promptCount, "seed=$seed promptCount")
        assertEquals(results.sumOf { it.settings }, provider.openSettingsCount, "seed=$seed openSettingsCount")
        // The resident collector is the only one that can still be registered; a terminal reading of the storm may
        // have completed it (and released it) already, which is exactly what its job state tells.
        val residentLeft: Int = if (resident.isActive) 1 else 0
        assertEquals(residentLeft, provider.activeObservers.value, "seed=$seed only the resident collector can be left registered")
        assertEquals(residentLeft, provider.activeUpdatesOptions.size, "seed=$seed")
        assertEquals(0, provider.pendingCurrentLocationCalls.value, "seed=$seed nothing was held")

        // After the last publish every reader sees exactly that reading.
        val last: LocationReading = available(90.0, 180.0, timestamp = -1L)
        provider.emit(last)
        assertEquals(last, provider.observeLocation().first(), "seed=$seed")
        resident.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value, "seed=$seed the resident collector is released")
        assertTrue(provider.activeUpdatesOptions.isEmpty(), "seed=$seed")
    }

    @Test
    fun `a storm of holds and releases strands no call and leaves nothing pending`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 1
        val provider = FakeLocationProvider()
        val answered: List<Int> = withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                // One worker alternates hold and release and always ends with a release; the callers
                // are therefore either held and then released, or answer at once: none is stranded.
                val switcher = async {
                    val rng = Random(seed)
                    repeat(steps) { step ->
                        provider.holdCurrentLocation()
                        if (rng.nextInt(3) != 0) provider.releaseCurrentLocation(available(step.toDouble() % 90, 0.0, timestamp = step.toLong()))
                    }
                    provider.releaseCurrentLocation(available(0.0, 0.0, timestamp = -1L)) // the closing release
                }
                val callers = (0 until workers - 1).map { worker ->
                    async {
                        var done = 0
                        repeat(steps / 4) {
                            val answer: LocationReading = provider.getCurrentLocation()
                            if (answer is LocationReading.Available || answer == noFix) done++
                        }
                        done
                    }
                }
                switcher.await()
                callers.awaitAll()
            }
        }

        assertEquals(List(workers - 1) { steps / 4 }, answered, "seed=$seed every caller got an answer for every call")
        assertEquals((workers - 1) * (steps / 4), provider.currentLocationCalls.size, "seed=$seed")
        assertEquals(0, provider.pendingCurrentLocationCalls.value, "seed=$seed nothing is left pending after the closing release")
    }

    @Test
    fun `a storm of collectors cancelled mid-flight releases every registration`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 2
        val provider = FakeLocationProvider()

        withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                val publisher = async {
                    val rng = Random(seed)
                    repeat(steps * 2) { step ->
                        provider.emit(
                            when (rng.nextInt(12)) {
                                0 -> noFix
                                1 -> LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
                                else -> available(rng.nextDouble(-90.0, 90.0), rng.nextDouble(-180.0, 180.0), timestamp = step.toLong())
                            },
                        )
                    }
                }
                val collectors = (0 until workers - 1).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        repeat(500) {
                            // UNDISPATCHED half of the time: the collector is registered before the cancel
                            // arrives, so the release path really runs; the other half may be cancelled before it starts.
                            val start: CoroutineStart = if (rng.nextBoolean()) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
                            val job = launch(start = start) { provider.observeLocation(LocationUpdatesOptions(minUpdateDistanceMeters = rng.nextInt(50).toDouble())).collect { } }
                            job.cancelAndJoin()
                        }
                    }
                }
                publisher.await()
                collectors.awaitAll()
            }
        }

        assertEquals(0, provider.activeObservers.value, "seed=$seed every registration was released")
        assertTrue(provider.activeUpdatesOptions.isEmpty(), "seed=$seed")
        // Re-init after the storm: the fake still serves a collection.
        val seen: List<LocationReading> = provider.also { it.emit(unsupported) }.observeLocation().toList()
        assertEquals(listOf(unsupported), seen, "seed=$seed")
        assertEquals(0, provider.activeObservers.value, "seed=$seed")
    }
}

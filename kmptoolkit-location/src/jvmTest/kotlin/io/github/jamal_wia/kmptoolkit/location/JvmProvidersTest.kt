package io.github.jamal_wia.kmptoolkit.location

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The desktop JVM factories (PLAN D27, 3.4, 6.5): `createLocationProvider()` and
 * `createHeadingProvider()` answer the typed unsupported states, and the lifecycle of those answers
 * matches the real providers' (a stream completes after UNSUPPORTED; a state stream and a silent
 * heading stream stay open until cancelled; a bad argument throws from the call itself).
 *
 * Group loc-jvm-fakes. Time is virtual (`runTest`); the only real-time bounds are hang bounds.
 */
class JvmProvidersTest {

    private val unsupported: LocationReading = LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)

    // ---- location ------------------------------------------------------------------------------

    @Test
    fun `the one-shot call answers UNSUPPORTED for default and for non default options`() = runTest {
        val provider: LocationProvider = createLocationProvider()

        assertEquals(unsupported, provider.getCurrentLocation())
        assertEquals(
            unsupported,
            provider.getCurrentLocation(
                CurrentLocationOptions(
                    accuracy = LocationAccuracy.HIGH_ACCURACY,
                    maxAge = Duration.INFINITE,
                    timeout = Duration.INFINITE,
                ),
            ),
            "options are not applied: no source exists, whatever was asked",
        )
        assertEquals(unsupported, provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 1.milliseconds)))
    }

    @Test
    fun `the stream emits UNSUPPORTED and completes`() = runTest {
        val provider: LocationProvider = createLocationProvider()

        val readings: List<LocationReading> = withTimeout(10.seconds) { provider.observeLocation().toList() }

        assertEquals(listOf(unsupported), readings, "exactly the one terminal reason, then the end of the stream")
    }

    @Test
    fun `the stream is cold and completes again on every collection`() = runTest {
        val provider: LocationProvider = createLocationProvider()
        val stream = provider.observeLocation(
            LocationUpdatesOptions(
                accuracy = LocationAccuracy.LOW_POWER,
                minUpdateDistanceMeters = 25.0,
                minUpdateInterval = 5.seconds,
                maxSeedAge = Duration.INFINITE,
            ),
        )

        repeat(3) { round ->
            assertEquals(listOf(unsupported), withTimeout(10.seconds) { stream.toList() }, "collection $round")
        }
    }

    @Test
    fun `isLocationEnabled is false and observeLocationEnabled emits false and stays open`() = runTest {
        val provider: LocationProvider = createLocationProvider()

        assertFalse(provider.isLocationEnabled())
        assertFalse(withTimeout(10.seconds) { provider.observeLocationEnabled().first() })

        // Stays open: a collection that is not cancelled does not end by itself (a state stream, like
        // the other platforms'), and it emits exactly one value however long it is watched.
        val seen: MutableList<Boolean> = mutableListOf()
        val completedWithin: List<Boolean>? =withTimeoutOrNull(1.hours) { provider.observeLocationEnabled().toList(seen) }
        assertNull(completedWithin, "the state stream must not complete on its own")
        assertEquals(listOf(false), seen)
    }

    @Test
    fun `cancelling a collection of the state stream ends it and a new collection works`() = runTest {
        val provider: LocationProvider = createLocationProvider()
        val first: CompletableDeferred<Boolean> = CompletableDeferred()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            provider.observeLocationEnabled().collect { first.complete(it) }
        }
        assertEquals(false, first.await())
        assertTrue(job.isActive, "control: the collection is live before the cancel")
        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        assertFalse(withTimeout(10.seconds) { provider.observeLocationEnabled().first() }, "re-collection after the cancel")
    }

    @Test
    fun `the prompt answers UNSUPPORTED and openLocationSettings never throws`() = runTest {
        val provider: LocationProvider = createLocationProvider()

        assertEquals(LocationServicePrompt.UNSUPPORTED, provider.promptToEnableService())
        repeat(1_000) { provider.openLocationSettings() }
        assertEquals(LocationServicePrompt.UNSUPPORTED, provider.promptToEnableService(), "still the same after the settings calls")
    }

    @Test
    fun `two providers and many collectors do not interfere`() = runTest {
        val a: LocationProvider = createLocationProvider()
        val b: LocationProvider = createLocationProvider()

        val collections = (0 until 16).map { index ->
            async { (if (index % 2 == 0) a else b).observeLocation().toList() }
        }

        collections.awaitAll().forEach { readings -> assertEquals(listOf(unsupported), readings) }
    }

    @Test
    fun `a storm of calls from many threads always gets the same answers`() = runTest {
        val seed = 4106L
        val provider: LocationProvider = createLocationProvider()
        val heading: HeadingProvider = createHeadingProvider()

        val problems: List<String> = withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                (0 until 8).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        val found: MutableList<String> = mutableListOf()
                        repeat(2_000) { step ->
                            val tag = "seed=$seed worker=$worker step=$step"
                            when (rng.nextInt(6)) {
                                0 -> if (provider.getCurrentLocation() != unsupported) found += "$tag: one-shot"
                                1 -> if (provider.observeLocation().toList() != listOf(unsupported)) found += "$tag: stream"
                                2 -> if (provider.isLocationEnabled()) found += "$tag: enabled"
                                3 -> if (provider.promptToEnableService() != LocationServicePrompt.UNSUPPORTED) found += "$tag: prompt"
                                4 -> provider.openLocationSettings()
                                else -> if (heading.isAvailable) found += "$tag: heading available"
                            }
                        }
                        found
                    }
                }.awaitAll().flatten()
            }
        }

        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
    }

    // ---- heading -------------------------------------------------------------------------------

    @Test
    fun `heading is not available`() {
        assertFalse(createHeadingProvider().isAvailable)
    }

    @Test
    fun `the heading stream emits nothing and stays open`() = runTest {
        val provider: HeadingProvider = createHeadingProvider()
        val seen: MutableList<Heading> = mutableListOf()

        val probe: List<Heading>? =withTimeoutOrNull(1.hours) { provider.observeHeading().toList(seen) }

        assertNull(probe, "the silent stream must not complete: the probe is cut by the virtual timeout")
        assertTrue(seen.isEmpty(), "nothing is emitted: $seen")
    }

    @Test
    fun `a silent heading collection is cancellable and the provider serves another one afterwards`() = runTest {
        val provider: HeadingProvider = createHeadingProvider()

        val job = provider.observeHeading(minChangeDegrees = 5.0, declinationAt = GeoCoordinates(21.4, 39.8), screenRotation = ScreenRotation.ROTATION_90)
            .onEach { error("no heading may arrive: $it") }
            .launchIn(this)
        assertTrue(job.isActive, "control: the collection is live")
        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        val again = provider.observeHeading().onEach { error("no heading may arrive: $it") }.launchIn(this)
        assertTrue(again.isActive)
        again.cancelAndJoin()
    }

    @Test
    fun `minChangeDegrees is validated by the call itself, like the real providers`() {
        val provider: HeadingProvider = createHeadingProvider()

        for (bad in listOf(-0.001, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val thrown: IllegalArgumentException = assertFailsWith<IllegalArgumentException>(message ="minChangeDegrees=$bad must throw without being collected") {
                provider.observeHeading(minChangeDegrees = bad)
            }
            assertNotNull(thrown.message)
        }
        // Boundaries that are valid: zero (every change) and a huge finite value.
        provider.observeHeading(minChangeDegrees = 0.0)
        provider.observeHeading(minChangeDegrees = Double.MAX_VALUE)
        provider.observeHeading(minChangeDegrees = -0.0)
    }
}

package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.Heading
import io.github.jamal_wia.kmptoolkit.location.ScreenRotation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The contract of [FakeHeadingProvider] (PLAN D32, 6.2): the argument check, a recorder per live
 * collector, silence while the device has no compass, replay without a seed, conflation, release
 * proof, re-initialisation, and a concurrent storm. Group loc-jvm-fakes. Time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeHeadingProviderTest {

    private val north: Heading = heading(0.0)
    private val east: Heading = heading(90.0, trueNorth = 91.5, accuracy = 4.0, needsCalibration = true)

    private fun TestScope.collecting(
        provider: FakeHeadingProvider,
        into: MutableList<Heading>,
        minChangeDegrees: Double = 1.0,
        declinationAt: GeoCoordinates? = null,
        screenRotation: ScreenRotation = ScreenRotation.NATURAL,
    ): Job = backgroundScope.launch { provider.observeHeading(minChangeDegrees, declinationAt, screenRotation).toList(into) }

    // ---- validation ----------------------------------------------------------------------------

    @Test
    fun `a negative or non finite minChangeDegrees throws from the call itself`() {
        val provider = FakeHeadingProvider()

        for (bad in listOf(-0.0001, -1.0, -360.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("minChangeDegrees=$bad must throw before any collection") {
                provider.observeHeading(minChangeDegrees = bad)
            }
        }
        assertEquals(0, provider.activeObservers.value, "a refused call registers nothing")
    }

    @Test
    fun `zero and large finite values are accepted`() {
        val provider = FakeHeadingProvider()

        provider.observeHeading(minChangeDegrees = 0.0)
        provider.observeHeading(minChangeDegrees = -0.0)
        provider.observeHeading(minChangeDegrees = 360.0)
        provider.observeHeading(minChangeDegrees = Double.MAX_VALUE)
    }

    @Test
    fun `validation applies on a device without a compass too`() {
        assertFailsWith<IllegalArgumentException> { FakeHeadingProvider(isAvailable = false).observeHeading(minChangeDegrees = Double.NaN) }
    }

    // ---- stream --------------------------------------------------------------------------------

    @Test
    fun `there is no seed so a collector waits for the first emit`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val seen: MutableList<Heading> = mutableListOf()
        val job: Job = collecting(provider, seen)
        assertTrue(seen.isEmpty(), "nothing before the sensor reports")
        assertEquals(1, provider.activeObservers.value, "control: the collector is live and waiting")

        provider.emit(north)

        assertEquals(listOf(north), seen)
        assertTrue(job.isActive, "the stream never completes on its own")
        job.cancelAndJoin()
    }

    @Test
    fun `a late collector gets the latest heading first and every late collector gets the same`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        provider.emit(north)
        provider.emit(east)

        val a: MutableList<Heading> = mutableListOf()
        val b: MutableList<Heading> = mutableListOf()
        val jobA: Job = collecting(provider, a)
        val jobB: Job = collecting(provider, b)

        assertEquals(listOf(east), a, "the latest only, not the history")
        assertEquals(listOf(east), b)
        jobA.cancelAndJoin()
        jobB.cancelAndJoin()
    }

    @Test
    fun `a slow collector skips intermediate headings and ends on the latest`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val seen: MutableList<Heading> = mutableListOf()
        provider.emit(north)
        val job: Job = backgroundScope.launch {
            provider.observeHeading().collect { value ->
                seen += value
                if (seen.size == 1) gate.await()
            }
        }
        assertEquals(listOf(north), seen, "control: the collector is inside the first heading")

        val published: List<Heading> = (1..30).map { heading(it.toDouble()) }
        published.forEach { provider.emit(it) }
        gate.complete(Unit)

        assertEquals(listOf(north, published.last()), seen)
        job.cancelAndJoin()
    }

    @Test
    fun `an equal heading emitted twice is delivered twice and no filter is applied`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val seen: MutableList<Heading> = mutableListOf()
        val job: Job = collecting(provider, seen, minChangeDegrees = 180.0)

        provider.emit(north)
        provider.emit(north)
        provider.emit(heading(0.1))

        assertEquals(listOf(north, north, heading(0.1)), seen, "minChangeDegrees is recorded, never applied")
        job.cancelAndJoin()
    }

    @Test
    fun `concurrent collectors are independent`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val a: MutableList<Heading> = mutableListOf()
        val b: MutableList<Heading> = mutableListOf()
        val jobA: Job = collecting(provider, a, screenRotation = ScreenRotation.ROTATION_90)
        val jobB: Job = collecting(provider, b, screenRotation = ScreenRotation.ROTATION_270)

        provider.emit(north)
        jobA.cancelAndJoin()
        provider.emit(east)

        assertEquals(listOf(north), a, "a cancelled collector receives nothing more")
        assertEquals(listOf(north, east), b, "and does not stop the other one")
        jobB.cancelAndJoin()
    }

    // ---- recorders and release -----------------------------------------------------------------

    @Test
    fun `recorders list the live collectors in order and shrink as they are released`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val where = GeoCoordinates(21.4225, 39.8262)
        val a: Job = collecting(provider, mutableListOf(), 1.0, null, ScreenRotation.NATURAL)
        val b: Job = collecting(provider, mutableListOf(), 5.5, where, ScreenRotation.ROTATION_90)
        val c: Job = collecting(provider, mutableListOf(), 0.0, null, ScreenRotation.ROTATION_180)

        assertEquals(3, provider.activeObservers.value)
        assertEquals(listOf(1.0, 5.5, 0.0), provider.activeFilters)
        assertEquals(listOf(null, where, null), provider.activeDeclinationAt)
        assertEquals(listOf(ScreenRotation.NATURAL, ScreenRotation.ROTATION_90, ScreenRotation.ROTATION_180), provider.activeScreenRotations)

        b.cancelAndJoin()
        assertEquals(2, provider.activeObservers.value)
        assertEquals(listOf(1.0, 0.0), provider.activeFilters)
        assertEquals(listOf<GeoCoordinates?>(null, null), provider.activeDeclinationAt)
        assertEquals(listOf(ScreenRotation.NATURAL, ScreenRotation.ROTATION_180), provider.activeScreenRotations)

        a.cancelAndJoin()
        c.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeFilters.isEmpty())
        assertTrue(provider.activeDeclinationAt.isEmpty())
        assertTrue(provider.activeScreenRotations.isEmpty())
    }

    @Test
    fun `two collectors with identical arguments are two registrations`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        val a: Job = collecting(provider, mutableListOf())
        val b: Job = collecting(provider, mutableListOf())
        assertEquals(listOf(1.0, 1.0), provider.activeFilters)

        a.cancelAndJoin()

        assertEquals(1, provider.activeObservers.value)
        assertEquals(listOf(1.0), provider.activeFilters)
        b.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `release proof - a collector that stops early or throws leaves no registration`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        provider.emit(north)

        assertEquals(north, provider.observeHeading().first())
        assertEquals(0, provider.activeObservers.value, "first() stopped it")

        assertFailsWith<IllegalStateException> { provider.observeHeading().collect { error("collector failed") } }
        assertEquals(0, provider.activeObservers.value, "a throwing collector released it")

        provider.emit(east)
        assertEquals(east, provider.observeHeading().first(), "the fake is not wedged")
    }

    @Test
    fun `re-collecting after a release works and counts from zero again`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider()
        repeat(5) { round ->
            val seen: MutableList<Heading> = mutableListOf()
            val job: Job = collecting(provider, seen)
            assertEquals(1, provider.activeObservers.value, "round=$round control")
            provider.emit(heading(round.toDouble()))
            assertEquals(listOf(heading(round.toDouble())), seen.takeLast(1), "round=$round")
            job.cancelAndJoin()
            assertEquals(0, provider.activeObservers.value, "round=$round")
        }
    }

    // ---- a device without a compass ------------------------------------------------------------

    @Test
    fun `nothing is emitted while isAvailable is false but the collector is live and released on cancel`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeHeadingProvider(isAvailable = false)
        assertFalse(provider.isAvailable)
        val seen: MutableList<Heading> = mutableListOf()
        val job: Job = collecting(provider, seen)
        assertEquals(1, provider.activeObservers.value, "control: the collector is counted although it will never receive")

        provider.emit(north)
        provider.emit(east)

        assertTrue(seen.isEmpty(), "no compass, no heading: $seen")
        assertTrue(job.isActive, "silent, not completed")
        job.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `a collector on a device without a compass is cut by the callers virtual timeout`() = runTest {
        val provider = FakeHeadingProvider(isAvailable = false)

        val probe: List<Heading>? = withTimeoutOrNull(1.hours) { provider.observeHeading().toList() }

        assertNull(probe)
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `isAvailable reports what the fake was built with`() {
        assertTrue(FakeHeadingProvider().isAvailable)
        assertTrue(FakeHeadingProvider(isAvailable = true).isAvailable)
        assertFalse(FakeHeadingProvider(isAvailable = false).isAvailable)
    }

    // ---- storm ---------------------------------------------------------------------------------

    @Test
    fun `a storm of emits and collections from eight threads ends consistent`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 10
        val provider = FakeHeadingProvider()
        val resident = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { provider.observeHeading().collect { } }
        assertEquals(1, provider.activeObservers.value, "seed=$seed control: the resident collector is registered")

        withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                (0 until 8).map { worker ->
                    async {
                        val rng = Random(seed * 1000 + worker)
                        repeat(10_000) { step ->
                            when (rng.nextInt(5)) {
                                0, 1 -> provider.emit(heading(rng.nextDouble(0.0, 360.0), trueNorth = if (rng.nextBoolean()) rng.nextDouble(0.0, 360.0) else null, needsCalibration = rng.nextBoolean()))
                                2 -> {
                                    // Replay: some heading is already there once anyone emitted; a late collector never hangs
                                    // when it asks after this worker's own emit.
                                    provider.emit(heading(step.toDouble() % 360))
                                    provider.observeHeading(minChangeDegrees = rng.nextInt(10).toDouble()).first()
                                }
                                3 -> {
                                    val job = launch(start = if (rng.nextBoolean()) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT) {
                                        provider.observeHeading(declinationAt = GeoCoordinates(rng.nextDouble(-90.0, 90.0), 0.0)).collect { }
                                    }
                                    job.cancelAndJoin()
                                }
                                else -> provider.activeObservers.value
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        assertEquals(1, provider.activeObservers.value, "seed=$seed only the resident collector is left")
        assertEquals(1, provider.activeFilters.size, "seed=$seed")
        val last: Heading = heading(123.0)
        provider.emit(last)
        assertEquals(last, provider.observeHeading().first(), "seed=$seed")
        resident.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value, "seed=$seed the resident collector is released")
        assertTrue(provider.activeFilters.isEmpty(), "seed=$seed")
    }
}

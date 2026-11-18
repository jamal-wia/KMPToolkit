package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.CurrentLocationOptions
import io.github.jamal_wia.kmptoolkit.location.LocationAccuracy
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationServicePrompt
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability
import io.github.jamal_wia.kmptoolkit.location.LocationUpdatesOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The contract of [FakeLocationProvider] (PLAN D32, 6.2): seed and replay, terminal readings, the
 * hold, the default answer, the recorders, the service state and the prompt, plus the adversarial
 * cases (cancellation mid-call, release proof with a control assertion, re-initialisation after
 * release). Group loc-jvm-fakes. Time is virtual: nothing here waits on a clock.
 *
 * `runTest(UnconfinedTestDispatcher())`: a collector or call started in `backgroundScope` runs
 * eagerly up to its first suspension, so a test asserts right after the step it drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLocationProviderTest {

    private val here: LocationReading = available(21.4225, 39.8262)
    private val there: LocationReading = available(51.5007, -0.1246)

    private fun TestScope.collecting(
        provider: FakeLocationProvider,
        into: MutableList<LocationReading>,
        options: LocationUpdatesOptions = LocationUpdatesOptions(),
    ): Job = backgroundScope.launch { provider.observeLocation(options).toList(into) }

    // ---- seed and replay -----------------------------------------------------------------------

    @Test
    fun `a collector gets the default seed Unavailable NO_FIX at once`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val seen: MutableList<LocationReading> = mutableListOf()

        val job: Job = collecting(provider, seen)

        assertEquals(listOf(noFix), seen)
        assertTrue(job.isActive, "NO_FIX does not complete the stream")
        job.cancelAndJoin()
    }

    @Test
    fun `a collector gets the given streamSeed first and a later emit after it`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider(streamSeed = serviceDisabled)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collecting(provider, seen)

        provider.emit(here)

        assertEquals(listOf(serviceDisabled, here), seen)
        job.cancelAndJoin()
    }

    @Test
    fun `a late collector starts from the latest emit and every late collector gets the same`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.emit(here)
        provider.emit(there)

        val first: MutableList<LocationReading> = mutableListOf()
        val second: MutableList<LocationReading> = mutableListOf()
        val a: Job = collecting(provider, first)
        val b: Job = collecting(provider, second)

        assertEquals(listOf(there), first, "replay of the latest only, not of the history")
        assertEquals(listOf(there), second)
        a.cancelAndJoin()
        b.cancelAndJoin()
    }

    @Test
    fun `a slow collector skips the intermediate readings and ends on the latest`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation().collect { reading ->
                seen += reading
                if (seen.size == 1) gate.await() // busy with the seed while the rest is published
            }
        }
        assertEquals(listOf(noFix), seen, "control: the collector is inside the seed")

        val published: List<LocationReading> = (0 until 20).map { available(it.toDouble(), it.toDouble(), timestamp = it.toLong()) }
        published.forEach { provider.emit(it) }
        gate.complete(Unit)

        assertEquals(listOf(noFix, published.last()), seen, "conflated: nothing between the seed and the newest")
        job.cancelAndJoin()
    }

    @Test
    fun `an equal reading emitted twice is delivered twice`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = collecting(provider, seen)

        provider.emit(here)
        provider.emit(here)
        provider.emit(noFix)
        provider.emit(noFix)

        assertEquals(listOf(noFix, here, here, noFix, noFix), seen)
        job.cancelAndJoin()
    }

    @Test
    fun `emit does not change what getCurrentLocation answers`() = runTest {
        val provider = FakeLocationProvider()

        provider.emit(here)

        assertEquals(noFix, provider.getCurrentLocation(), "the answer is scripted by currentLocationAnswer only")
    }

    // ---- terminal readings ---------------------------------------------------------------------

    @Test
    @Suppress("REDUNDANT_ELSE_IN_WHEN") // the else is the point: a new reason must fail here until someone rules on it
    fun `only PERMISSION_MISSING and UNSUPPORTED complete the collectors`() = runTest(UnconfinedTestDispatcher()) {
        for (reason in LocationUnavailability.entries) {
            val completes: Boolean = when (reason) {
                LocationUnavailability.PERMISSION_MISSING, LocationUnavailability.UNSUPPORTED -> true
                LocationUnavailability.SERVICE_DISABLED, LocationUnavailability.NO_FIX -> false
                else -> error("a new LocationUnavailability value needs a ruling on whether it ends the stream: $reason")
            }
            val provider = FakeLocationProvider()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = collecting(provider, seen)
            val reading: LocationReading = LocationReading.Unavailable(reason)

            provider.emit(reading)

            assertEquals(completes, job.isCompleted, "reason=$reason")
            assertEquals(reading, seen.last(), "reason=$reason is delivered before the stream ends")
            assertEquals(if (completes) 0 else 1, provider.activeObservers.value, "reason=$reason releases the collector exactly when it ends")
            job.cancelAndJoin()
            assertEquals(0, provider.activeObservers.value, "reason=$reason after cancel")
        }
    }

    @Test
    fun `a terminal reading stays the latest so re-collecting completes again and an emit serves the next collection`() = runTest(UnconfinedTestDispatcher()) {
        for (terminal in listOf(permissionMissing, unsupported)) {
            val provider = FakeLocationProvider()
            provider.emit(terminal)

            repeat(3) { round ->
                assertEquals(listOf(terminal), provider.observeLocation().toList(), "terminal=$terminal collection $round completes at once")
                assertEquals(0, provider.activeObservers.value)
            }

            // Re-initialisation after release: a new emit makes the stream serve a collection again.
            provider.emit(here)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = collecting(provider, seen)
            assertEquals(listOf(here), seen, "terminal=$terminal after a new emit")
            assertTrue(job.isActive)
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a terminal streamSeed completes the first collection`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider(streamSeed = permissionMissing)

        assertEquals(listOf(permissionMissing), provider.observeLocation().toList())
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `a terminal reading completes every live collector and leaves none registered`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val seen: List<MutableList<LocationReading>> = List(5) { mutableListOf() }
        val jobs: List<Job> = seen.map { collecting(provider, it) }
        assertEquals(5, provider.activeObservers.value, "control: five live collectors")

        provider.emit(unsupported)

        assertTrue(jobs.all { it.isCompleted })
        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeUpdatesOptions.isEmpty())
        seen.forEach { assertEquals(unsupported, it.last()) }
    }

    @Test
    fun `a terminal reading is never delivered to a collection that started after the emit that followed it`() = runTest {
        val provider = FakeLocationProvider()
        val early: MutableList<LocationReading> = mutableListOf()
        val late: MutableList<LocationReading> = mutableListOf()
        val earlyJob: Job = backgroundScope.launch { provider.observeLocation().toList(early) }
        testScheduler.runCurrent()
        assertEquals(1, provider.activeObservers.value, "control: the early collector is live")

        provider.emit(permissionMissing)
        provider.emit(here) // before the early collector has read the terminal reading
        val lateJob: Job = backgroundScope.launch { provider.observeLocation().toList(late) }
        testScheduler.runCurrent()

        assertEquals(listOf(noFix, permissionMissing), early, "the live collection ends on the terminal reading")
        assertTrue(earlyJob.isCompleted)
        assertEquals(listOf(here), late, "a collection that starts later starts from the newer reading, not the old terminal one")
        assertTrue(lateJob.isActive, "and stays open")
        assertEquals(1, provider.activeObservers.value, "only the late collector is left")
        lateJob.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `a live collection ends on a terminal reading however many readings follow it before the collector runs`() = runTest {
        val random = Random(FAKE_TEST_SEED)
        val others: List<LocationReading> = listOf(noFix, serviceDisabled, here, there)
        repeat(20) { round ->
            val provider = FakeLocationProvider()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { provider.observeLocation().toList(seen) }
            testScheduler.runCurrent()
            val before: Int = random.nextInt(0, 5)
            repeat(before) { provider.emit(others[random.nextInt(others.size)]) }
            val terminal: LocationReading = if (random.nextBoolean()) permissionMissing else unsupported
            provider.emit(terminal)
            val after: Int = random.nextInt(1, 200)
            repeat(after) { provider.emit(others[random.nextInt(others.size)]) }
            testScheduler.runCurrent()

            val context = "seed=$FAKE_TEST_SEED round=$round before=$before after=$after terminal=$terminal seen=$seen"
            assertEquals(terminal, seen.last(), context)
            assertTrue(job.isCompleted, context)
            assertEquals(0, provider.activeObservers.value, context)
        }
    }

    @Test
    fun `two terminal readings in a row end a live collection on the newest one and nothing after it`() = runTest {
        val provider = FakeLocationProvider()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { provider.observeLocation().toList(seen) }
        testScheduler.runCurrent()

        provider.emit(permissionMissing)
        provider.emit(unsupported)
        provider.emit(here)
        testScheduler.runCurrent()

        assertEquals(unsupported, seen.last(), "seen=$seen")
        assertTrue(job.isCompleted)
        assertEquals(0, provider.activeObservers.value)
    }

    // ---- the hold ------------------------------------------------------------------------------

    @Test
    fun `a hold keeps every call suspended until released and then ends`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.holdCurrentLocation()

        val a = backgroundScope.async { provider.getCurrentLocation() }
        val b = backgroundScope.async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO)) }
        assertEquals(2, provider.pendingCurrentLocationCalls.value)
        assertFalse(a.isCompleted)
        assertFalse(b.isCompleted)

        provider.releaseCurrentLocation(here)

        assertEquals(here, a.await())
        assertEquals(here, b.await())
        assertEquals(0, provider.pendingCurrentLocationCalls.value)

        provider.currentLocationAnswer = there
        assertEquals(there, provider.getCurrentLocation(), "the hold ended with the release: the next call answers")
    }

    @Test
    fun `releasing with nothing held only ends the hold and a later hold still holds`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.currentLocationAnswer = here

        provider.releaseCurrentLocation(there) // nothing held: no effect on anything
        assertEquals(here, provider.getCurrentLocation())
        assertEquals(0, provider.pendingCurrentLocationCalls.value)

        provider.holdCurrentLocation()
        val held = backgroundScope.async { provider.getCurrentLocation() }
        assertEquals(1, provider.pendingCurrentLocationCalls.value)
        provider.releaseCurrentLocation(there)
        provider.releaseCurrentLocation(noFix) // a second release is a no-op, not a second answer
        assertEquals(there, held.await())
    }

    @Test
    fun `cancelling a held call drops the pending count and leaves the others held`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.holdCurrentLocation()
        val cancelled = backgroundScope.launch { provider.getCurrentLocation() }
        val kept = backgroundScope.async { provider.getCurrentLocation() }
        assertEquals(2, provider.pendingCurrentLocationCalls.value, "control: both calls are held")

        cancelled.cancelAndJoin()

        assertEquals(1, provider.pendingCurrentLocationCalls.value)
        assertTrue(kept.isActive)
        provider.releaseCurrentLocation(here)
        assertEquals(here, kept.await())
        assertEquals(0, provider.pendingCurrentLocationCalls.value)
    }

    @Test
    fun `a caller side timeout cancels a held call and releases it`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.holdCurrentLocation()

        val answer: LocationReading? = withTimeoutOrNull(5.seconds) { provider.getCurrentLocation() }

        assertNull(answer, "the fake applies no timeout of its own: the caller's cut it")
        assertEquals(0, provider.pendingCurrentLocationCalls.value)
        assertEquals(1, provider.currentLocationCalls.size, "the call was recorded before it was held")
    }

    @Test
    fun `holding twice keeps the calls that are already held under the same hold`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.holdCurrentLocation()
        val early = backgroundScope.async { provider.getCurrentLocation() }

        provider.holdCurrentLocation()
        val late = backgroundScope.async { provider.getCurrentLocation() }
        assertEquals(2, provider.pendingCurrentLocationCalls.value)

        provider.releaseCurrentLocation(here)

        assertEquals(here, early.await())
        assertEquals(here, late.await(), "one release resumes every held call")
    }

    @Test
    fun `a held call that already started when the release comes still gets the released reading`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        repeat(50) { round ->
            provider.holdCurrentLocation()
            val call = backgroundScope.async { provider.getCurrentLocation() }
            provider.releaseCurrentLocation(available(round.toDouble(), 0.0))
            assertEquals(available(round.toDouble(), 0.0), call.await(), "round=$round")
        }
        assertEquals(0, provider.pendingCurrentLocationCalls.value)
    }

    @Test
    fun `the one-shot answer is NO_FIX by default and follows currentLocationAnswer`() = runTest {
        val provider = FakeLocationProvider()
        assertEquals(noFix, provider.currentLocationAnswer)
        assertEquals(noFix, provider.getCurrentLocation())

        for (scripted in listOf(here, serviceDisabled, permissionMissing, unsupported)) {
            provider.currentLocationAnswer = scripted
            assertEquals(scripted, provider.getCurrentLocation())
        }
    }

    @Test
    fun `options apply no policy to the answer`() = runTest {
        val provider = FakeLocationProvider()
        provider.currentLocationAnswer = here

        val answers: List<LocationReading> = listOf(
            provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO, timeout = Duration.INFINITE)),
            provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.INFINITE)),
            provider.getCurrentLocation(CurrentLocationOptions(accuracy = LocationAccuracy.LOW_POWER, timeout = 1.seconds)),
        )

        assertEquals(listOf(here, here, here), answers)
    }

    // ---- recorders -----------------------------------------------------------------------------

    @Test
    fun `currentLocationCalls records every call in order with its platform options`() = runTest {
        val provider = FakeLocationProvider()
        val platform = TestCurrentOptions("first")
        val options: List<CurrentLocationOptions> = listOf(
            CurrentLocationOptions(),
            CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, platform = platform),
            CurrentLocationOptions(maxAge = Duration.INFINITE, timeout = 3.seconds),
            CurrentLocationOptions(),
        )

        options.forEach { provider.getCurrentLocation(it) }

        assertEquals(options, provider.currentLocationCalls)
        assertSame(platform, provider.currentLocationCalls[1].platform, "the platform options object itself is kept")
    }

    @Test
    fun `currentLocationCalls is a snapshot`() = runTest {
        val provider = FakeLocationProvider()
        provider.getCurrentLocation()
        val before: List<CurrentLocationOptions> = provider.currentLocationCalls

        provider.getCurrentLocation(CurrentLocationOptions(accuracy = LocationAccuracy.LOW_POWER))

        assertEquals(1, before.size, "a list read earlier does not grow")
        assertEquals(2, provider.currentLocationCalls.size)
    }

    @Test
    fun `activeUpdatesOptions lists the live collectors with their platform options and activeObservers returns to zero`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val platform = TestUpdatesOptions("a")
        val optionsA = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, minUpdateDistanceMeters = 10.0, platform = platform)
        val optionsB = LocationUpdatesOptions(minUpdateInterval = 2.seconds)
        assertEquals(0, provider.activeObservers.value)

        val a: Job = collecting(provider, mutableListOf(), optionsA)
        val b: Job = collecting(provider, mutableListOf(), optionsB)

        assertEquals(2, provider.activeObservers.value, "control: both are registered")
        assertEquals(listOf(optionsA, optionsB), provider.activeUpdatesOptions)
        assertSame(platform, provider.activeUpdatesOptions[0].platform)

        a.cancelAndJoin()
        assertEquals(1, provider.activeObservers.value)
        assertEquals(listOf(optionsB), provider.activeUpdatesOptions)
        b.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeUpdatesOptions.isEmpty())
    }

    @Test
    fun `two collectors with equal options are two registrations and releasing one leaves the other`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val same = LocationUpdatesOptions()
        val a: Job = collecting(provider, mutableListOf(), same)
        val b: Job = collecting(provider, mutableListOf(), same)
        assertEquals(2, provider.activeObservers.value)
        assertEquals(2, provider.activeUpdatesOptions.size)

        a.cancelAndJoin()

        assertEquals(1, provider.activeObservers.value)
        assertEquals(listOf(same), provider.activeUpdatesOptions)
        b.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `a collector that stops early releases its registration`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()

        assertEquals(noFix, provider.observeLocation().first())

        assertEquals(0, provider.activeObservers.value)
        assertTrue(provider.activeUpdatesOptions.isEmpty())
    }

    @Test
    fun `a collector whose body throws releases its registration and the fake keeps working`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()

        val failure: IllegalStateException = assertFailsWith<IllegalStateException> {
            provider.observeLocation().collect { error("collector failed") }
        }

        assertEquals("collector failed", failure.message)
        assertEquals(0, provider.activeObservers.value)
        provider.emit(here)
        assertEquals(here, provider.observeLocation().first(), "the fake is not wedged by a failing collector")
        assertEquals(0, provider.activeObservers.value)
    }

    @Test
    fun `collecting after a release works and counts again from zero`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        repeat(5) { round ->
            val job: Job = collecting(provider, mutableListOf())
            assertEquals(1, provider.activeObservers.value, "round=$round control")
            job.cancelAndJoin()
            assertEquals(0, provider.activeObservers.value, "round=$round")
        }
    }

    // ---- service state -------------------------------------------------------------------------

    @Test
    fun `locationEnabled drives isLocationEnabled and observeLocationEnabled and never the readings`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        assertTrue(provider.isLocationEnabled(), "defaults to on")
        val states: MutableList<Boolean> = mutableListOf()
        val readings: MutableList<LocationReading> = mutableListOf()
        val stateJob: Job = backgroundScope.launch { provider.observeLocationEnabled().toList(states) }
        val readingJob: Job = collecting(provider, readings)
        assertEquals(listOf(true), states, "emitted at once")

        provider.locationEnabled = false
        provider.locationEnabled = false // distinct: the same state is not emitted again
        provider.locationEnabled = true

        assertEquals(listOf(true, false, true), states)
        assertEquals(listOf(noFix), readings, "the service state never moves the readings")
        assertEquals(0, provider.currentLocationCalls.size)
        stateJob.cancelAndJoin()
        readingJob.cancelAndJoin()
    }

    @Test
    fun `a changed service state is visible to the next read`() = runTest {
        val provider = FakeLocationProvider(locationEnabled = false)
        assertFalse(provider.isLocationEnabled())

        provider.locationEnabled = true

        assertTrue(provider.isLocationEnabled())
        assertTrue(provider.observeLocationEnabled().first())
    }

    @Test
    fun `promptToEnableService reports ALREADY_ON when the service is on`() = runTest {
        val provider = FakeLocationProvider(locationEnabled = true)

        assertEquals(LocationServicePrompt.ALREADY_ON, provider.promptToEnableService())
    }

    @Test
    fun `promptToEnableService reports UNSUPPORTED when the service is off`() = runTest {
        val provider = FakeLocationProvider(locationEnabled = false)

        assertEquals(LocationServicePrompt.UNSUPPORTED, provider.promptToEnableService())
    }

    @Test
    fun `a scripted prompt answer is given while the service is off and not while it is on`() = runTest {
        val provider = FakeLocationProvider(locationEnabled = false)
        assertNull(provider.servicePromptAnswer)
        provider.servicePromptAnswer = LocationServicePrompt.NOT_NOW

        assertEquals(LocationServicePrompt.NOT_NOW, provider.promptToEnableService())
        provider.locationEnabled = true
        assertEquals(LocationServicePrompt.ALREADY_ON, provider.promptToEnableService(), "a scripted answer never overrides a service that is on")
        assertEquals(2, provider.promptCount, "calls that found the service on count too")
    }

    @Test
    fun `counts how many times openLocationSettings was called`() {
        val provider = FakeLocationProvider()
        assertEquals(0, provider.openSettingsCount)

        provider.openLocationSettings()
        provider.openLocationSettings()

        assertEquals(2, provider.openSettingsCount)
    }
}

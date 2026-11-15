@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.CurrentLocationOptions
import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.Heading
import io.github.jamal_wia.kmptoolkit.location.LocationProvider
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability
import io.github.jamal_wia.kmptoolkit.location.LocationUpdatesOptions
import io.github.jamal_wia.kmptoolkit.location.ScreenRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The Kotlin samples of docs/kmptoolkit-location/06-testing.md and 07-migrating-to-6.md (step 6), typed in exactly as
 * the docs print them (each block between "docs-sample:" and "end docs-sample" is the text of one fenced block)
 * and run against the real fakes, so a sample that stops compiling against the public API, or behaves differently
 * from the sentence beside it, fails here.
 */

// docs-sample: testing-presenter
sealed interface MapState {
    data object Searching : MapState
    data class Centered(val coordinates: GeoCoordinates) : MapState
    data object NeedsPermission : MapState
}

fun LocationReading.toMapState(): MapState = when (this) {
    is LocationReading.Available -> MapState.Centered(fix.coordinates)
    is LocationReading.Unavailable ->
        if (reason == LocationUnavailability.PERMISSION_MISSING) MapState.NeedsPermission else MapState.Searching
}

class MapPresenter(private val location: LocationProvider, scope: CoroutineScope) {
    val state: StateFlow<MapState> = location.observeLocation()
        .map { reading: LocationReading -> reading.toMapState() }
        .stateIn(scope, SharingStarted.Eagerly, MapState.Searching)

    suspend fun centerOnMe(): MapState =
        withTimeoutOrNull(10.seconds) { location.getCurrentLocation().toMapState() } ?: MapState.Searching
}
// end docs-sample

// docs-sample: testing-tests
class MapPresenterTest {

    @Test
    fun `a position replaces the searching state`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()
        val presenter = MapPresenter(location, backgroundScope)
        assertEquals(MapState.Searching, presenter.state.value) // the stream seed is Unavailable(NO_FIX)

        location.emit(LocationReading.Available(fakeFix(21.4225, 39.8262)))

        assertEquals(MapState.Centered(GeoCoordinates(21.4225, 39.8262)), presenter.state.value)
    }

    @Test
    fun `a missing permission ends the stream and is released`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()
        val presenter = MapPresenter(location, backgroundScope)
        assertEquals(1, location.activeObservers.value)

        location.emit(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING))

        assertEquals(MapState.NeedsPermission, presenter.state.value)
        assertEquals(0, location.activeObservers.value) // a terminal reading completes every collector
    }

    @Test
    fun `a one-shot call that is cancelled mid-flight leaves nothing pending`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()
        location.holdCurrentLocation()

        val call = launch { MapPresenter(location, backgroundScope).centerOnMe() }
        assertEquals(1, location.pendingCurrentLocationCalls.value)
        call.cancelAndJoin()

        assertEquals(0, location.pendingCurrentLocationCalls.value)
    }

    @Test
    fun `a hold that outlasts the timeout of the caller falls back to searching`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()
        location.holdCurrentLocation()

        assertEquals(MapState.Searching, MapPresenter(location, backgroundScope).centerOnMe())
        assertEquals(0, location.pendingCurrentLocationCalls.value)
    }

    @Test
    fun `an unscripted one-shot call fails visibly instead of passing on a position nobody chose`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()

        assertEquals(MapState.Searching, MapPresenter(location, backgroundScope).centerOnMe())

        location.currentLocationAnswer = LocationReading.Available(fakeFix(21.4, 39.8))
        assertEquals(MapState.Centered(GeoCoordinates(21.4, 39.8)), MapPresenter(location, backgroundScope).centerOnMe())
    }

    @Test
    fun `a new collection after a terminal reading starts from the next reading`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()
        location.emit(LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED))
        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)), location.observeLocation().toList())

        location.emit(LocationReading.Available(fakeFix(1.0, 2.0)))

        val seen: LocationReading = location.observeLocation().first()
        assertEquals(LocationReading.Available(fakeFix(1.0, 2.0)), seen)
        assertEquals(0, location.activeObservers.value)
    }

    @Test
    fun `the options of every call are recorded and never applied`() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocationProvider()
        val updates = LocationUpdatesOptions(minUpdateDistanceMeters = 25.0)
        val job = location.observeLocation(updates).onEach { }.launchIn(backgroundScope)

        location.getCurrentLocation(CurrentLocationOptions(timeout = 3.seconds))

        assertEquals(listOf(CurrentLocationOptions(timeout = 3.seconds)), location.currentLocationCalls)
        assertEquals(listOf(updates), location.activeUpdatesOptions)
        job.cancelAndJoin()
        assertEquals(emptyList(), location.activeUpdatesOptions)
    }

    @Test
    fun `fakeFix rejects invalid coordinates and a stored value is checked with orNull`() {
        assertFailsWith<IllegalArgumentException> { fakeFix(latitude = 91.0, longitude = 0.0) }
        assertNull(GeoCoordinates.orNull(91.0, 0.0))
    }
}
// end docs-sample

// docs-sample: testing-heading
class CompassTest {

    @Test
    fun `the heading fake replays the latest heading and records what the collector asked for`() = runTest(UnconfinedTestDispatcher()) {
        val headings = FakeHeadingProvider()
        val first = Heading(magneticDegrees = 10.0, trueDegrees = 12.0, accuracyDegrees = 5.0, needsCalibration = false)
        headings.emit(first)
        val london = GeoCoordinates(51.5, -0.1)
        val seen: MutableList<Heading> = mutableListOf()

        val job = headings.observeHeading(minChangeDegrees = 2.0, declinationAt = london, screenRotation = ScreenRotation.ROTATION_90)
            .onEach { heading: Heading -> seen += heading }
            .launchIn(backgroundScope)

        assertEquals(listOf(first), seen) // a collector that starts after an emit gets it first
        assertEquals(listOf(2.0), headings.activeFilters)
        assertEquals(listOf<GeoCoordinates?>(london), headings.activeDeclinationAt)
        assertEquals(listOf(ScreenRotation.ROTATION_90), headings.activeScreenRotations)
        job.cancelAndJoin()
        assertEquals(0, headings.activeObservers.value)
    }

    @Test
    fun `a device without a compass stays silent and releases on cancellation`() = runTest(UnconfinedTestDispatcher()) {
        val headings = FakeHeadingProvider(isAvailable = false)
        val seen: MutableList<Heading> = mutableListOf()
        val job = headings.observeHeading().onEach { heading: Heading -> seen += heading }.launchIn(backgroundScope)

        headings.emit(Heading(10.0, null, null, false))

        assertEquals(emptyList(), seen)
        assertEquals(listOf(1.0), headings.activeFilters)
        job.cancelAndJoin()
        assertEquals(0, headings.activeObservers.value)
    }
}
// end docs-sample

// docs-sample: migration-fake
fun migratedFake(): FakeLocationProvider {
    val fake = FakeLocationProvider()
    fake.currentLocationAnswer = LocationReading.Available(fakeFix(21.4, 39.8))
    fake.emit(LocationReading.Available(fakeFix(21.5, 39.9)))
    return fake
}
// end docs-sample

class LocationTestingDocSamplesTest {

    @Test
    fun `the migrated fake answers the one shot and the stream separately`() = runTest(UnconfinedTestDispatcher()) {
        val fake: FakeLocationProvider = migratedFake()

        assertEquals(LocationReading.Available(fakeFix(21.4, 39.8)), fake.getCurrentLocation())
        assertEquals(LocationReading.Available(fakeFix(21.5, 39.9)), fake.observeLocation().first())
    }
}

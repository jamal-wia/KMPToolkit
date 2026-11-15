@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * The Kotlin samples of docs/kmptoolkit-location (02-getting-started, 03-guide, 07-migration), typed in
 * exactly as the docs print them (each block between "docs-sample:" and "end docs-sample" is the text of one
 * fenced block) and run against the real geo math and a scripted provider, so a sample that stops compiling
 * against the public API, or behaves differently from the sentence beside it, fails here. The platform
 * samples live in the androidHostTest, iosTest and jvmTest source sets; the samples of 06-testing live in
 * kmptoolkit-location-testing.
 */

// The presenters and views the samples talk to are the app's own; these are the stand-ins that record calls.
interface MapUi {
    fun show(coordinates: GeoCoordinates, accuracyMeters: Double?)
    fun askPermission()
    fun offerSettings()
    fun showNoFix()
    fun showUnsupported()
}

internal class RecordingMapUi : MapUi {
    val calls: MutableList<String> = mutableListOf()
    override fun show(coordinates: GeoCoordinates, accuracyMeters: Double?) {
        calls += "show(${coordinates.latitude}, ${coordinates.longitude}, $accuracyMeters)"
    }
    override fun askPermission() { calls += "askPermission" }
    override fun offerSettings() { calls += "offerSettings" }
    override fun showNoFix() { calls += "showNoFix" }
    override fun showUnsupported() { calls += "showUnsupported" }
}

interface CompassUi {
    fun showAtTheKaaba()
    fun showTrueNorthUnavailable()
    fun showNeedle(rotation: Double, calibrate: Boolean)
}

internal class RecordingCompassUi : CompassUi {
    val calls: MutableList<String> = mutableListOf()
    override fun showAtTheKaaba() { calls += "atTheKaaba" }
    override fun showTrueNorthUnavailable() { calls += "trueNorthUnavailable" }
    override fun showNeedle(rotation: Double, calibrate: Boolean) { calls += "needle($rotation, $calibrate)" }
}

interface EnableUi {
    fun proceedWithLocation()
    fun waitForTheDialog()
    fun tryAgainLater()
    fun showEnableLocationPrompt(onConfirm: () -> Unit)
}

internal class RecordingEnableUi : EnableUi {
    val calls: MutableList<String> = mutableListOf()
    override fun proceedWithLocation() { calls += "proceed" }
    override fun waitForTheDialog() { calls += "wait" }
    override fun tryAgainLater() { calls += "later" }
    override fun showEnableLocationPrompt(onConfirm: () -> Unit) {
        calls += "explain"
        onConfirm()
    }
}

/** A provider an app could have written: it answers what the test scripted and records what it was asked. */
internal class ScriptedProvider(
    var current: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX),
    var stream: List<LocationReading> = emptyList(),
    var enabled: Boolean = true,
    var prompt: LocationServicePrompt? = null,
) : LocationProvider {
    val currentCalls: MutableList<CurrentLocationOptions> = mutableListOf()
    val streamCalls: MutableList<LocationUpdatesOptions> = mutableListOf()
    var settingsOpened: Int = 0

    override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading {
        currentCalls += options
        return current
    }

    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> {
        streamCalls += options
        return stream.asFlow()
    }

    override suspend fun isLocationEnabled(): Boolean = enabled

    override fun observeLocationEnabled(): Flow<Boolean> = flowOf(enabled)

    override fun openLocationSettings() {
        settingsOpened++
    }

    override suspend fun promptToEnableService(): LocationServicePrompt =
        prompt ?: if (enabled) LocationServicePrompt.ALREADY_ON else LocationServicePrompt.UNSUPPORTED
}

private fun <T> List<T>.asFlow(): Flow<T> = flow { forEach { value: T -> emit(value) } }

internal fun docFix(latitude: Double, longitude: Double, accuracyMeters: Double? = 5.0): LocationFix =
    LocationFix(GeoCoordinates(latitude, longitude), timestampEpochMillis = 0L, horizontalAccuracyMeters = accuracyMeters)

// docs-sample: first-call
suspend fun showWhereIAm(provider: LocationProvider, ui: MapUi) {
    when (val reading: LocationReading = provider.getCurrentLocation()) {
        is LocationReading.Available -> ui.show(reading.fix.coordinates, reading.fix.horizontalAccuracyMeters)
        is LocationReading.Unavailable -> when (reading.reason) {
            LocationUnavailability.PERMISSION_MISSING -> ui.askPermission()
            LocationUnavailability.SERVICE_DISABLED -> ui.offerSettings()
            else -> ui.showNoFix()
        }
    }
}
// end docs-sample

// docs-sample: stream
fun CoroutineScope.followTheUser(provider: LocationProvider, ui: MapUi): Job = launch {
    val options = LocationUpdatesOptions(minUpdateDistanceMeters = 25.0, minUpdateInterval = 5.seconds)
    provider.observeLocation(options).collect { reading: LocationReading ->
        when (reading) {
            is LocationReading.Available -> ui.show(reading.fix.coordinates, reading.fix.horizontalAccuracyMeters)
            is LocationReading.Unavailable -> when (reading.reason) {
                LocationUnavailability.PERMISSION_MISSING -> ui.askPermission()
                LocationUnavailability.SERVICE_DISABLED -> ui.offerSettings()
                LocationUnavailability.UNSUPPORTED -> ui.showUnsupported()
                else -> ui.showNoFix()
            }
        }
    }
}
// end docs-sample

// docs-sample: untrusted-coordinates
fun parseStoredPlace(latitude: Double, longitude: Double): GeoCoordinates? =
    GeoCoordinates.orNull(latitude, longitude)
// end docs-sample

// docs-sample: nearest-place
data class Place(val name: String, val coordinates: GeoCoordinates)

fun nearestPlace(here: GeoCoordinates, places: List<Place>, maxDistanceMeters: Double = 5_000.0): Place? =
    places
        .minByOrNull { place: Place -> here.distanceInMetersTo(place.coordinates) }
        ?.takeIf { place: Place -> here.distanceInMetersTo(place.coordinates) <= maxDistanceMeters }
// end docs-sample

// docs-sample: radius-check
enum class Containment { INSIDE, OUTSIDE, UNCERTAIN }

fun containment(center: GeoCoordinates, radiusMeters: Double, fix: LocationFix, accuracyScale: Double = 1.0): Containment {
    val accuracy: Double = fix.horizontalAccuracyMeters?.times(accuracyScale) ?: return Containment.UNCERTAIN
    val distance: Double = fix.coordinates.distanceInMetersTo(center)
    return when {
        distance + accuracy <= radiusMeters -> Containment.INSIDE
        distance - accuracy > radiusMeters -> Containment.OUTSIDE
        else -> Containment.UNCERTAIN
    }
}
// end docs-sample

// docs-sample: sql-prefilter
class Prefilter(val sql: String, val arguments: List<Double>)

fun nearbyPlacesQuery(here: GeoCoordinates, radiusMeters: Double): Prefilter {
    val box: GeoBounds = here.boundingBox(radiusMeters)
    val longitude: String = box.longitudeRanges.joinToString(separator = " OR ", prefix = "(", postfix = ")") {
        "lng BETWEEN ? AND ?"
    }
    val arguments: List<Double> =
        listOf(box.south, box.north) + box.longitudeRanges.flatMap { range -> listOf(range.start, range.endInclusive) }
    return Prefilter("SELECT * FROM places WHERE lat BETWEEN ? AND ? AND $longitude", arguments)
}
// end docs-sample

// docs-sample: qibla
val KAABA: GeoCoordinates = GeoCoordinates(21.422487, 39.826206)

fun qiblaBearing(here: GeoCoordinates): Double? = here.initialBearingTo(KAABA)
// end docs-sample

// docs-sample: compass-needle
suspend fun runQiblaCompass(headings: HeadingProvider, here: GeoCoordinates, rotation: ScreenRotation, ui: CompassUi) {
    val qibla: Double = here.initialBearingTo(KAABA) ?: return ui.showAtTheKaaba()
    headings.observeHeading(declinationAt = here, screenRotation = rotation).collect { heading: Heading ->
        val trueNorth: Double? = heading.trueDegrees
        if (trueNorth == null) {
            ui.showTrueNorthUnavailable() // offer location authorization (iOS) or pass a position (Android)
            return@collect
        }
        ui.showNeedle(
            rotation = angleDifference(fromDegrees = trueNorth, toDegrees = qibla),
            calibrate = heading.needsCalibration,
        )
    }
}
// end docs-sample

// docs-sample: compass-rotation
@OptIn(ExperimentalCoroutinesApi::class)
fun qiblaHeadings(headings: HeadingProvider, here: GeoCoordinates, rotations: Flow<ScreenRotation>): Flow<Heading> =
    rotations.distinctUntilChanged().flatMapLatest { rotation: ScreenRotation ->
        headings.observeHeading(declinationAt = here, screenRotation = rotation)
    }
// end docs-sample

// docs-sample: bearing-recipes
fun finalBearing(from: GeoCoordinates, to: GeoCoordinates): Double? =
    to.initialBearingTo(from)?.let { backwards: Double -> (backwards + 180.0) % 360.0 }

fun pointAlong(from: GeoCoordinates, to: GeoCoordinates, fraction: Double): GeoCoordinates? {
    val distance: Double = from.distanceInMetersTo(to)
    val bearing: Double = from.initialBearingTo(to) ?: return if (distance < 1.0) from else null
    return from.destinationPoint(distance * fraction, bearing)
}

fun midpoint(a: GeoCoordinates, b: GeoCoordinates): GeoCoordinates? = pointAlong(a, b, 0.5)
// end docs-sample

// docs-sample: next-step
enum class NextStep { ASK_PERMISSION, TURN_LOCATION_ON, KEEP_WAITING, GIVE_UP }

fun nextStep(reason: LocationUnavailability): NextStep = when (reason) {
    LocationUnavailability.PERMISSION_MISSING -> NextStep.ASK_PERMISSION
    LocationUnavailability.SERVICE_DISABLED -> NextStep.TURN_LOCATION_ON
    LocationUnavailability.NO_FIX -> NextStep.KEEP_WAITING
    else -> NextStep.GIVE_UP
}
// end docs-sample

// docs-sample: prompt
suspend fun turnLocationOn(location: LocationProvider, ui: EnableUi) {
    when (location.promptToEnableService()) {
        LocationServicePrompt.ALREADY_ON -> ui.proceedWithLocation()
        LocationServicePrompt.PROMPTED -> ui.waitForTheDialog() // the OS shows its own dialog; re-check on resume
        LocationServicePrompt.NOT_NOW -> ui.tryAgainLater() // no window to raise the dialog over; try again
        LocationServicePrompt.UNSUPPORTED -> ui.showEnableLocationPrompt(onConfirm = { location.openLocationSettings() })
    }
}
// end docs-sample

// docs-sample: platform-options
class TrackerSession(private val provider: LocationProvider, private val platform: PlatformLocationUpdatesOptions?) {
    fun readings(): Flow<LocationReading> = provider.observeLocation(
        LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, platform = platform),
    )
}
// end docs-sample

// docs-sample: migration-one-shot
suspend fun migratedOneShot(provider: LocationProvider, ui: MapUi) {
    when (val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(timeout = 10.seconds))) {
        is LocationReading.Available -> ui.show(reading.fix.coordinates, reading.fix.horizontalAccuracyMeters)
        is LocationReading.Unavailable -> when (reading.reason) {
            LocationUnavailability.PERMISSION_MISSING -> ui.askPermission()
            LocationUnavailability.SERVICE_DISABLED -> provider.openLocationSettings()
            else -> ui.showNoFix()
        }
    }
}

suspend fun migratedCoordinatesOnly(provider: LocationProvider): GeoCoordinates? =
    provider.getCurrentLocation().fixOrNull()?.coordinates
// end docs-sample

// docs-sample: migration-stream
suspend fun migratedStream(provider: LocationProvider, update: (GeoCoordinates) -> Unit) {
    provider.observeLocation(LocationUpdatesOptions(minUpdateDistanceMeters = 50.0, minUpdateInterval = 10.seconds))
        .collect { reading: LocationReading -> reading.fixOrNull()?.let { fix: LocationFix -> update(fix.coordinates) } }
}
// end docs-sample

class LocationDocSamplesTest {

    private val london = GeoCoordinates(51.5074, -0.1278)

    @Test
    fun `first call maps a fix and every reason to one reaction`() = runTest(timeout = 60.seconds) {
        val ui = RecordingMapUi()
        val provider = ScriptedProvider()

        provider.current = LocationReading.Available(docFix(21.4, 39.8, accuracyMeters = 12.0))
        showWhereIAm(provider, ui)
        provider.current = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
        showWhereIAm(provider, ui)
        provider.current = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
        showWhereIAm(provider, ui)
        provider.current = LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)
        showWhereIAm(provider, ui)

        assertEquals(listOf("show(21.4, 39.8, 12.0)", "askPermission", "offerSettings", "showNoFix"), ui.calls)
        assertEquals(CurrentLocationOptions(), provider.currentCalls.first(), "the call without options asks for the defaults")
    }

    @Test
    fun `the stream sample passes its options and reacts to every reading`() = runTest(timeout = 60.seconds) {
        val ui = RecordingMapUi()
        val provider = ScriptedProvider(
            stream = listOf(
                LocationReading.Unavailable(LocationUnavailability.NO_FIX),
                LocationReading.Available(docFix(1.0, 2.0)),
                LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
                LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING),
            ),
        )

        followTheUser(provider, ui).join()

        assertEquals(listOf("showNoFix", "show(1.0, 2.0, 5.0)", "offerSettings", "askPermission"), ui.calls)
        val options: LocationUpdatesOptions = provider.streamCalls.single()
        assertEquals(25.0, options.minUpdateDistanceMeters)
        assertEquals(5.seconds, options.minUpdateInterval)
        assertEquals(30.seconds, options.maxSeedAge, "the seed age keeps its default")
    }

    @Test
    fun `untrusted coordinates are rejected without throwing and the constructor throws`() {
        assertEquals(GeoCoordinates(21.4, 39.8), parseStoredPlace(21.4, 39.8))
        assertNull(parseStoredPlace(91.0, 0.0))
        assertNull(parseStoredPlace(0.0, 190.0))
        assertNull(parseStoredPlace(Double.NaN, 0.0))
        assertFailsWith<IllegalArgumentException> { GeoCoordinates(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoCoordinates(21.4, 39.8).copy(latitude = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `nearest place picks the closest and the radius cap rejects a far one`() {
        val kaaba = Place("Kaaba", KAABA)
        val cairo = Place("Cairo", GeoCoordinates(30.0444, 31.2357))
        val here = GeoCoordinates(21.4225, 39.8262)

        assertEquals(kaaba, nearestPlace(here, listOf(cairo, kaaba)))
        assertNull(nearestPlace(here, listOf(cairo)), "Cairo is 1 287 km away: beyond the default cap")
        assertEquals(cairo, nearestPlace(here, listOf(cairo), maxDistanceMeters = 2_000_000.0))
        assertNull(nearestPlace(here, emptyList()))
    }

    @Test
    fun `radius check is inside only when the whole accuracy circle is inside and outside only when none of it is`() {
        val center = GeoCoordinates(0.0, 0.0)
        val oneKilometerEast: GeoCoordinates = center.destinationPoint(1_000.0, 90.0)

        assertEquals(Containment.INSIDE, containment(center, 1_100.0, docFix(oneKilometerEast.latitude, oneKilometerEast.longitude, 50.0)))
        assertEquals(Containment.UNCERTAIN, containment(center, 1_020.0, docFix(oneKilometerEast.latitude, oneKilometerEast.longitude, 50.0)))
        assertEquals(Containment.OUTSIDE, containment(center, 900.0, docFix(oneKilometerEast.latitude, oneKilometerEast.longitude, 50.0)))
        assertEquals(
            Containment.INSIDE,
            containment(center, 1_100.0, docFix(oneKilometerEast.latitude, oneKilometerEast.longitude, 50.0), accuracyScale = 1.62),
            "the 95 % radius of a 50 m accuracy is 81 m: 1000 + 81 is inside 1100",
        )
        assertEquals(
            Containment.UNCERTAIN,
            containment(center, 1_050.0, docFix(oneKilometerEast.latitude, oneKilometerEast.longitude, 50.0), accuracyScale = 1.62),
            "1000 + 81 is not inside 1050, although 1000 + 50 is",
        )
        assertEquals(
            Containment.UNCERTAIN,
            containment(center, 1_100.0, docFix(oneKilometerEast.latitude, oneKilometerEast.longitude, accuracyMeters = null)),
            "no accuracy proves neither side",
        )
    }

    @Test
    fun `the sql prefilter wraps the longitude ranges in parentheses and keeps a point across the antimeridian`() {
        val nearAntimeridian = GeoCoordinates(0.0, 179.95)
        val query: Prefilter = nearbyPlacesQuery(nearAntimeridian, radiusMeters = 20_000.0)

        assertEquals(
            "SELECT * FROM places WHERE lat BETWEEN ? AND ? AND (lng BETWEEN ? AND ? OR lng BETWEEN ? AND ?)",
            query.sql,
        )
        assertEquals(6, query.arguments.size)
        // Mirror the clause in Kotlin: a row just across the antimeridian matches, one at the same longitude but far north does not.
        fun matches(latitude: Double, longitude: Double): Boolean {
            val a: List<Double> = query.arguments
            return latitude in a[0]..a[1] && (longitude in a[2]..a[3] || longitude in a[4]..a[5])
        }
        assertTrue(matches(0.0, -179.97), "just across the antimeridian")
        assertTrue(matches(0.0, 179.99), "on this side")
        assertTrue(!matches(5.0, -179.97), "inside the second longitude range but outside the latitude band")
        // The naive west..east box misses the row across the antimeridian: that is the bug the clause avoids.
        val box: GeoBounds = nearAntimeridian.boundingBox(20_000.0)
        assertTrue(box.crossesAntimeridian)
        assertTrue(-179.97 !in box.west..box.east)
    }

    @Test
    fun `a box that does not cross the antimeridian gives one range`() {
        val query: Prefilter = nearbyPlacesQuery(GeoCoordinates(48.85, 2.35), radiusMeters = 1_000.0)

        assertEquals("SELECT * FROM places WHERE lat BETWEEN ? AND ? AND (lng BETWEEN ? AND ?)", query.sql)
        assertEquals(4, query.arguments.size)
    }

    @Test
    fun `qibla bearing from London and none at the Kaaba itself`() {
        val bearing: Double = assertNotNull(qiblaBearing(london))

        assertTrue(bearing in 118.5..119.5, "London to the Kaaba is about 119 degrees, got $bearing")
        assertNull(qiblaBearing(KAABA))
        assertEquals(176.356246, assertNotNull(qiblaBearing(GeoCoordinates(55.7558, 37.6173))), 1e-5, "Moscow row of the reference table")
        assertEquals(58.481706, assertNotNull(qiblaBearing(GeoCoordinates(40.7128, -74.0060))), 1e-5, "New York row")
    }

    @Test
    fun `a coarse position in Mecca can point the arrow 27 degrees off at 10 km`() {
        val here: GeoCoordinates = KAABA.destinationPoint(10_000.0, 0.0)
        val actual: Double = assertNotNull(qiblaBearing(here))
        val coarse: GeoCoordinates = here.destinationPoint(5_000.0, 90.0)
        val shown: Double = assertNotNull(qiblaBearing(coarse))

        assertEquals(180.0, actual, 1e-6)
        assertEquals(26.6, kotlin.math.abs(angleDifference(actual, shown)), 0.3, "a 5 km error at 10 km is about 27 degrees")
    }

    private class OneShotHeadings(private val headings: List<Heading>) : HeadingProvider {
        val calls: MutableList<Pair<GeoCoordinates?, ScreenRotation>> = mutableListOf()
        override val isAvailable: Boolean = true
        override fun observeHeading(minChangeDegrees: Double, declinationAt: GeoCoordinates?, screenRotation: ScreenRotation): Flow<Heading> {
            checkMinChangeDegreesForTest(minChangeDegrees)
            calls += declinationAt to screenRotation
            return headings.asFlow()
        }
    }

    @Test
    fun `the compass needle never falls back to the magnetic heading and shows the kaaba case`() = runTest(timeout = 60.seconds) {
        val ui = RecordingCompassUi()
        val headings = OneShotHeadings(
            listOf(
                Heading(magneticDegrees = 100.0, trueDegrees = null, accuracyDegrees = null, needsCalibration = false),
                Heading(magneticDegrees = 100.0, trueDegrees = 101.0, accuracyDegrees = 5.0, needsCalibration = true),
            ),
        )
        val qibla: Double = assertNotNull(qiblaBearing(london))

        runQiblaCompass(headings, london, ScreenRotation.ROTATION_90, ui)

        assertEquals(
            listOf("trueNorthUnavailable", "needle(${angleDifference(101.0, qibla)}, true)"),
            ui.calls,
        )
        assertEquals(listOf<Pair<GeoCoordinates?, ScreenRotation>>(london to ScreenRotation.ROTATION_90), headings.calls)

        val atTheKaaba = RecordingCompassUi()
        runQiblaCompass(headings, KAABA, ScreenRotation.NATURAL, atTheKaaba)
        assertEquals(listOf("atTheKaaba"), atTheKaaba.calls, "no bearing at the Kaaba itself: no stream is started")
        assertEquals(1, headings.calls.size)
    }

    @Test
    fun `re-collecting on rotation switches to the newest rotation`() = runTest(timeout = 60.seconds) {
        val headings = OneShotHeadings(listOf(Heading(10.0, 12.0, null, false)))

        val seen: List<Heading> = qiblaHeadings(
            headings,
            london,
            flowOf(ScreenRotation.NATURAL, ScreenRotation.NATURAL, ScreenRotation.ROTATION_90),
        ).toList()
        advanceUntilIdle()

        assertTrue(seen.isNotEmpty())
        assertEquals(ScreenRotation.ROTATION_90, headings.calls.last().second, "the last rotation is the live collection")
        assertTrue(headings.calls.map { it.second }.distinct().size == 2, "an unchanged rotation did not restart the stream")
    }

    @Test
    fun `final bearing midpoint and interpolation`() {
        val a = GeoCoordinates(0.0, 0.0)
        val b = GeoCoordinates(0.0, 90.0)

        assertEquals(90.0, assertNotNull(finalBearing(a, b)), 1e-6, "along the equator the heading never changes")
        val mid: GeoCoordinates = assertNotNull(midpoint(a, b))
        assertEquals(0.0, mid.latitude, 1e-9)
        assertEquals(45.0, mid.longitude, 1e-6)
        val quarter: GeoCoordinates = assertNotNull(pointAlong(a, b, 0.25))
        assertEquals(22.5, quarter.longitude, 1e-6)
        assertEquals(a, midpoint(a, a), "coincident points: the point itself")
        assertNull(midpoint(GeoCoordinates(30.0, 10.0), GeoCoordinates(-30.0, -170.0)), "exact antipodes: no direction, no answer")
        // Final bearing of a path that crosses a meridian slant: LAX -> JFK arrives at a different heading than it leaves.
        val lax = GeoCoordinates(33.95, -118.4)
        val jfk = GeoCoordinates(40.6333, -73.7833)
        assertEquals(65.892, assertNotNull(lax.initialBearingTo(jfk)), 0.01, "the Aviation Formulary row")
        assertTrue(assertNotNull(finalBearing(lax, jfk)) > 65.892 + 10.0, "the arrival heading differs from the departure heading")
    }

    @Test
    fun `next step covers every reason the library has today and gives up on one it may add`() {
        assertEquals(NextStep.ASK_PERMISSION, nextStep(LocationUnavailability.PERMISSION_MISSING))
        assertEquals(NextStep.TURN_LOCATION_ON, nextStep(LocationUnavailability.SERVICE_DISABLED))
        assertEquals(NextStep.KEEP_WAITING, nextStep(LocationUnavailability.NO_FIX))
        assertEquals(NextStep.GIVE_UP, nextStep(LocationUnavailability.UNSUPPORTED))
    }

    @Test
    fun `the prompt sample handles all four answers and opens the settings only when unsupported`() = runTest(timeout = 60.seconds) {
        for (answer: LocationServicePrompt in LocationServicePrompt.entries) {
            val ui = RecordingEnableUi()
            val provider = ScriptedProvider(enabled = false, prompt = answer)

            turnLocationOn(provider, ui)

            val expected: List<String> = when (answer) {
                LocationServicePrompt.ALREADY_ON -> listOf("proceed")
                LocationServicePrompt.PROMPTED -> listOf("wait")
                LocationServicePrompt.NOT_NOW -> listOf("later")
                LocationServicePrompt.UNSUPPORTED -> listOf("explain")
            }
            assertEquals(expected, ui.calls, "answer=$answer")
            assertEquals(if (answer == LocationServicePrompt.UNSUPPORTED) 1 else 0, provider.settingsOpened, "answer=$answer")
        }
    }

    @Test
    fun `platform options built in platform code travel through shared code untouched`() = runTest(timeout = 60.seconds) {
        val provider = ScriptedProvider(stream = listOf(LocationReading.Unavailable(LocationUnavailability.NO_FIX)))
        val platform: PlatformLocationUpdatesOptions? = null

        TrackerSession(provider, platform).readings().toList()

        val options: LocationUpdatesOptions = provider.streamCalls.single()
        assertEquals(LocationAccuracy.HIGH_ACCURACY, options.accuracy)
        assertNull(options.platform)
    }

    @Test
    fun `the migrated one shot and the coordinates only form`() = runTest(timeout = 60.seconds) {
        val ui = RecordingMapUi()
        val provider = ScriptedProvider(current = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED))

        migratedOneShot(provider, ui)
        assertEquals(1, provider.settingsOpened)
        assertEquals(10.seconds, provider.currentCalls.single().timeout)
        assertNull(migratedCoordinatesOnly(provider))

        provider.current = LocationReading.Available(docFix(21.4, 39.8))
        assertEquals(GeoCoordinates(21.4, 39.8), migratedCoordinatesOnly(provider))
    }

    @Test
    fun `the migrated stream hands only fixes to the update callback`() = runTest(timeout = 60.seconds) {
        val updates: MutableList<GeoCoordinates> = mutableListOf()
        val provider = ScriptedProvider(
            stream = listOf(
                LocationReading.Unavailable(LocationUnavailability.NO_FIX),
                LocationReading.Available(docFix(1.0, 2.0)),
                LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
                LocationReading.Available(docFix(3.0, 4.0)),
            ),
        )

        migratedStream(provider) { coordinates: GeoCoordinates -> updates += coordinates }

        assertEquals(listOf(GeoCoordinates(1.0, 2.0), GeoCoordinates(3.0, 4.0)), updates)
        val options: LocationUpdatesOptions = provider.streamCalls.single()
        assertEquals(50.0, options.minUpdateDistanceMeters)
        assertEquals(10.seconds, options.minUpdateInterval)
    }

    /**
     * The contract of an app-written provider (the Play Services decorator of 03-guide): invalid platform values
     * are dropped through GeoCoordinates.orNull, options are forwarded, a terminal reading ends the stream and the
     * service state is observable. The decorator itself needs Play Services, which no module here depends on, so this
     * is the same shape over a scripted source.
     */
    private class DecoratorOverASource(private val source: List<Pair<Double, Double>>) : LocationProvider {
        override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading {
            val first: GeoCoordinates? = source.firstNotNullOfOrNull { (lat: Double, lng: Double) -> GeoCoordinates.orNull(lat, lng) }
            return if (first == null) {
                LocationReading.Unavailable(LocationUnavailability.NO_FIX)
            } else {
                LocationReading.Available(LocationFix(first, timestampEpochMillis = 0L))
            }
        }

        override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = flow {
            for ((lat: Double, lng: Double) in source) {
                val coordinates: GeoCoordinates = GeoCoordinates.orNull(lat, lng) ?: continue
                emit(LocationReading.Available(LocationFix(coordinates, timestampEpochMillis = 0L)))
            }
            emit(LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED))
        }

        override suspend fun isLocationEnabled(): Boolean = true

        override fun observeLocationEnabled(): Flow<Boolean> = flowOf(true)

        override fun openLocationSettings() = Unit
    }

    @Test
    fun `an app decorator that maps platform values through orNull drops the invalid ones and ends on a terminal reading`() = runTest(timeout = 60.seconds) {
        val provider: LocationProvider = DecoratorOverASource(listOf(Double.NaN to 0.0, 91.0 to 0.0, 21.4 to 39.8))

        val readings: List<LocationReading> = provider.observeLocation().toList()

        assertEquals(
            listOf(
                LocationReading.Available(LocationFix(GeoCoordinates(21.4, 39.8), 0L)),
                LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED),
            ),
            readings,
        )
        assertEquals(LocationServicePrompt.ALREADY_ON, provider.promptToEnableService(), "the interface default answers ALREADY_ON while the service is on")
        assertEquals(
            LocationReading.Available(LocationFix(GeoCoordinates(21.4, 39.8), 0L)),
            provider.getCurrentLocation(),
        )
    }
}

/** The shared check, reached through the opt-in the library's own providers use. */
@OptIn(io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi::class)
private fun checkMinChangeDegreesForTest(value: Double) = checkMinChangeDegrees(value)

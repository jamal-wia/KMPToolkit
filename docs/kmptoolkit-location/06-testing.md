# kmptoolkit-location — Testing

`kmptoolkit-location-testing` ships the doubles for code that depends on `LocationProvider` and
`HeadingProvider`: `FakeLocationProvider`, `FakeHeadingProvider` and the `fakeFix(...)` builder. They run on
every target of the module (Android, iOS and `jvm`), need no device, no permission and no
`CLLocationManager`, and are safe to drive from any thread.

```kotlin
// build.gradle.kts of the module under test
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-location-testing:<version>")
        }
    }
}
```

The samples of this page are run against the real fakes by `LocationTestingDocSamplesTest`
(`MapPresenterTest` and `CompassTest` below are the classes it prints).

## `fakeFix`

```kotlin
public fun fakeFix(
    latitude: Double,
    longitude: Double,
    timestampEpochMillis: Long = 0L,
    horizontalAccuracyMeters: Double? = 5.0,
    ellipsoidalAltitudeMeters: Double? = null,
    mslAltitudeMeters: Double? = null,
    verticalAccuracyMeters: Double? = null,
    speedMetersPerSecond: Double? = null,
    speedAccuracyMetersPerSecond: Double? = null,
    courseDegrees: Double? = null,
    courseAccuracyDegrees: Double? = null,
    isSimulated: Boolean? = false,
    isApproximate: Boolean? = false,
    platformDetails: PlatformFixDetails? = null,
): LocationFix
```

Builds a `LocationFix` so a test names only what it cares about: `fakeFix(21.4225, 39.8262)` is a precise
(5 m), non-simulated, non-approximate fix at that point. Every parameter maps one to one onto the field of
the same name; only the defaults differ from the data class (which defaults every optional fact to `null`,
"the platform reported none"). It throws `IllegalArgumentException` for coordinates `GeoCoordinates` rejects;
a test that needs an invalid platform fix builds its own input, because no provider ever emits one.

## `FakeLocationProvider`

```kotlin
public class FakeLocationProvider(
    locationEnabled: Boolean = true,
    streamSeed: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX),
) : LocationProvider
```

A `LocationProvider` a test drives by hand. It plays any outcome a real provider can produce and applies **no
policy**: it records the options of every call and ignores them (no maximum age, no timeout, no throttling, no
accuracy), so a test states the answer it wants and the code under test is the only thing tested.

| Member | What it does |
|---|---|
| `emit(reading)` | makes `reading` the latest stream value: every live collector receives it (conflated) and every later collector starts from it |
| `currentLocationAnswer` | what a `getCurrentLocation` call that is not held answers. Defaults to `Unavailable(NO_FIX)`, so a test that forgot to script it fails visibly instead of passing on a position it never chose |
| `holdCurrentLocation()` | from now on every `getCurrentLocation` call suspends until `releaseCurrentLocation` (for spinner and cancellation tests). A timeout is the caller's own `withTimeout`: the fake applies none. Holding twice changes nothing |
| `releaseCurrentLocation(reading)` | resumes every held call with `reading` and ends the hold; with nothing held it only ends the hold |
| `pendingCurrentLocationCalls: StateFlow<Int>` | calls currently suspended by the hold; drops when one is released or cancelled: the one-shot release proof |
| `currentLocationCalls` | the `CurrentLocationOptions` of every call so far, in order (platform options included) |
| `activeUpdatesOptions` | the `LocationUpdatesOptions` of every live stream collector (platform options included) |
| `activeObservers: StateFlow<Int>` | live collectors, counted from the start of a collection until it ends for any reason; `0` proves release |
| `locationEnabled` | drives `isLocationEnabled()` and `observeLocationEnabled()` **only**, never the readings (no hidden coupling): a test that wants `SERVICE_DISABLED` on the stream emits it |
| `servicePromptAnswer`, `openSettingsCount`, `promptCount` | what `promptToEnableService` answers while the service is off (`null` keeps the interface default, `UNSUPPORTED`; while the service is on it is always `ALREADY_ON`), and how many times `openLocationSettings` and `promptToEnableService` were called |

### The stream

`observeLocation` is cold, but every collector reads one shared, conflated source that replays its latest
reading:

- A new collector starts from the latest reading: `streamSeed` until the first `emit` (a real provider also
  seeds at once, so a `combine()` downstream does not hang).
- A busy collector skips intermediate readings and ends on the newest. An equal reading emitted twice is
  delivered twice, as a real provider delivers every update.
- **The stream ends exactly like the real providers'.** A terminal reading
  (`Unavailable(PERMISSION_MISSING)` or `Unavailable(UNSUPPORTED)`) completes the collectors; every other
  reading keeps them open. A live collection always ends on a terminal reading even if another `emit` follows
  at once (the readings in between are skipped and the later ones are never delivered to that collection); with
  several terminal readings in a row it ends on the newest.
- A terminal reading stays the latest one until the next `emit`, so collecting again after it completes again;
  an `emit` of anything else serves the next collection (re-initialisation after release). A collection started
  after a later `emit` starts from that reading.
- `emit` does **not** change what `getCurrentLocation` answers: set `currentLocationAnswer` for that.

### Threads and snapshots

Every counter, recorder and setting is safe to use from any thread, with no lock held across a suspension
point. The recorder lists are **snapshots** (copies), exact once the threads that drove the fake have
finished. A fake that stays unreleased holds no thread: a held call is a suspended coroutine.

## `FakeHeadingProvider`

```kotlin
public class FakeHeadingProvider(isAvailable: Boolean = true) : HeadingProvider
```

A `HeadingProvider` a test drives by hand. Like the real providers it validates `minChangeDegrees` (a negative
or non-finite value throws `IllegalArgumentException` from `observeHeading` itself, before any collection), and
like `FakeLocationProvider` it applies **no policy**: the filter, the declination and the rotation are only
recorded (`activeFilters`, `activeDeclinationAt`, `activeScreenRotations`), never applied, so a test emits
exactly the headings it wants delivered.

- Every collector reads one shared, conflated source that replays its latest heading. There is **no seed**: a
  real heading provider emits only when the sensor reports, so collectors wait until the first `emit(heading)`.
  The stream never completes on its own.
- With `isAvailable = false`, `emit` does nothing and `observeHeading` stays silent, as a real device does: the
  collector is live (counted in `activeObservers`, released on cancellation) and receives nothing.
- `activeObservers: StateFlow<Int>` is `0` after every collector is released.

## Example

<!-- docs-sample: testing-presenter -->
```kotlin
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
```

Each edge case the repository asks of a module is a few lines against the fakes:

<!-- docs-sample: testing-tests -->
```kotlin
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
```

- *Empty or null input:* `fakeFix` and `GeoCoordinates` reject invalid values, `GeoCoordinates.orNull` returns
  `null` (last test).
- *Permission denied:* emit the terminal reading; the stream completes and `activeObservers` is `0`.
- *No network, no fix:* the default one-shot answer is `Unavailable(NO_FIX)`; a hold with the caller's own
  timeout covers "nothing arrives".
- *Cancellation mid-operation:* a held call that is cancelled leaves `pendingCurrentLocationCalls` at `0`.
- *Re-initialisation after release:* a new collection after a terminal reading starts from the next `emit`.
- *Explicit release:* `activeObservers` and `pendingCurrentLocationCalls` are the release proofs; assert them
  with a control assertion (the live count is `1` first) so a counter that never moved cannot pass.

The heading fake, the same way:

<!-- docs-sample: testing-heading -->
```kotlin
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
```

## Testing code that decorates a provider

An app-written `LocationProvider` (the Play Services decorator of [`03-guide.md`](03-guide.md#8-decorator-over-play-services-android))
is checked against the same promises the fake keeps: invalid platform values are dropped through
`GeoCoordinates.orNull`, a terminal reading ends the stream, a cancelled call or collection leaves nothing
registered, and `observeLocationEnabled()` is implemented. `LocationDocSamplesTest` runs that contract over a
scripted source in the module's own tests.

## What the fakes do not do

- They do not model the platform's policy: no maximum age, timeout, throttle, accuracy, source choice,
  seed age, or approximate grant. Test those against the real providers' own behaviour
  ([`05-platform-notes.md`](05-platform-notes.md)), not against a fake.
- `locationEnabled` never changes the stream, and the stream never changes `locationEnabled`.

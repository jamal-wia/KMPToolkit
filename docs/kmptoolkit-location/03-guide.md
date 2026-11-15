# kmptoolkit-location — Guide

## One-shot versus continuous

`getCurrentLocation()` and `observeLocation()` answer different questions and cost different things.

| | `getCurrentLocation(options)` | `observeLocation(options)` |
|---|---|---|
| Shape | `suspend fun … : LocationReading` | `fun … : Flow<LocationReading>` (cold, conflated) |
| Returns | one reading: a fix, or a typed reason | the seed, then every update, until cancelled |
| Cached fix | accepted when no older than `maxAge` (default 30 s); `Duration.ZERO` forces a fresh one | only as the seed, when no older than `maxSeedAge` (default 30 s) |
| Fresh fix | the available sources are asked at once and the first valid fix wins, within `timeout` (default 30 s) | live updates, never awaited |
| Ends | when it answers, or at `timeout` with `Unavailable(NO_FIX)` | when you cancel; or after `PERMISSION_MISSING` / `UNSUPPORTED` |
| Underlying request | removed as soon as it answers, times out or is cancelled | removed when the flow is cancelled or completes |
| Use for | "where is the user right now": centering a map on open, tagging one action | a live map camera, a feature that must react as the user moves |

Reach for `getCurrentLocation()` first. It is cheaper (a cached fix answers instantly) and leaves no request
running after you stop caring. Use `observeLocation()` only when the feature is genuinely continuous.

### The options

| Option | Default | What it does |
|---|---|---|
| `accuracy` | `BALANCED` | `HIGH_ACCURACY` (GNSS-grade, works offline), `BALANCED` (about 100 m, may need data), `LOW_POWER` (city level). A request, not a guarantee: an approximate grant caps every level |
| `maxAge` (one-shot) | 30 s | oldest cached fix accepted, judged on the platform's monotonic clock; `ZERO` forces a fresh fix, `Duration.INFINITE` accepts any cache |
| `timeout` (one-shot) | 30 s | how long to wait for a fresh fix before `Unavailable(NO_FIX)`; `Duration.INFINITE` waits until cancelled; must be positive |
| `minUpdateDistanceMeters` (stream) | `0.0` | smallest movement between two emitted fixes, measured from the last emitted fix |
| `minUpdateInterval` (stream) | `Duration.ZERO` | shortest time between two emitted fixes, on every platform |
| `maxSeedAge` (stream) | 30 s | oldest cached fix the stream may emit first; with none that young the first value is `Unavailable(NO_FIX)` |
| `platform` | `null` | options only one platform has, see [recipe 7](#7-platform-options-from-shared-code) |

`minUpdateDistanceMeters` and `minUpdateInterval` form a trailing-edge throttle: a fix that is far enough
but arrives inside the interval is not lost, it is delivered when the interval ends, and a newer such fix
replaces it. A reading that carries a reason is **never** delayed or dropped by it. A cached first fix (the
seed, and every reseed after the service returned) does not start the interval, so the first live fix that is
far enough passes at once.

### What a stream promises

- It **seeds at once** (a cached fix within `maxSeedAge`, else a reason), so a `combine()` downstream never
  waits for a first value.
- It is **conflated**: a collector that is busy skips intermediate readings and ends on the newest. Two
  consequences: a busy collector can see two fixes closer together than `minUpdateDistanceMeters` or
  `minUpdateInterval` (the skipped fix was the throttle's reference), and it can miss an intermediate
  `SERVICE_DISABLED` that a reseed already replaced. A terminal reading is never skipped, and throttling
  never hides a reason.
- It **stays open** through `SERVICE_DISABLED` and `NO_FIX` (and reseeds when the service returns) and
  **completes** after `PERMISSION_MISSING` or `UNSUPPORTED`, identically on Android, iOS and the desktop.
- It is **cold**: nothing is registered with the platform before collection starts, and cancelling releases
  everything. Collect it again for a new run; there is no state between collections.

```kotlin
class LiveMapPresenter(private val location: LocationProvider, scope: CoroutineScope) {

    val camera: StateFlow<LocationReading?> = location.observeLocation()
        .stateIn(scope, SharingStarted.WhileSubscribed(), initialValue = null)
}
```

`SharingStarted.WhileSubscribed()` releases the platform request when the last subscriber goes away. A
stream is a foreground feature unless you opt in to more: on iOS it stops while the app is suspended (see
[`05-platform-notes.md`](05-platform-notes.md#ios-background-and-auto-pause)), and Android background
tracking is your own foreground service.

## Checking whether location is on

A reason says what is missing: `PERMISSION_MISSING`, `SERVICE_DISABLED` and `NO_FIX` are different
messages to the user ("allow location", "turn on Location", "still searching"). The device-wide service
state is also a call of its own, independent of the app's permission:

- `isLocationEnabled()` reads it once. On Android it is device-wide only: it can read `true` while a
  source you named (`GPS`, `NETWORK`, `FUSED`) is switched off; the one-shot call and the stream report
  that as `SERVICE_DISABLED`.
- `observeLocationEnabled()` emits the state at once and on every change (distinct), **without starting
  location updates**. On the desktop it emits `false` once and stays open.

```kotlin
location.observeLocationEnabled().collect { on: Boolean -> showLocationBanner(visible = !on) }
```

Do not poll `isLocationEnabled()` in a loop: collect `observeLocationEnabled()`.

## Sending the user to settings

```kotlin
if (!location.isLocationEnabled()) {
    showEnableLocationPrompt(onConfirm = { location.openLocationSettings() })
}
```

Show your own explanation first. `openLocationSettings()` leaves your app immediately and reports nothing
about what the user did there. Observe `observeLocationEnabled()` or re-check when your screen resumes. On
iOS it opens the app's own settings page (iOS exposes no deep link to the Location Services toggle); on the
desktop it does nothing. It never throws.

### Which task the settings screen opens in (Android)

*Since 1.7.0.* On Android, `openLocationSettings()` hands the screen to a `SystemScreenLauncher` from
`kmptoolkit-activity`, and which one depends on the factory you called:

| Factory | Launcher | Where the settings screen goes |
|---|---|---|
| `createLocationProvider(context, logger)` | `SystemScreenLauncher.SeparateTask` | a task of its own; Back leaves Settings |
| `createLocationProviderWithLauncher(context, systemScreenLauncher, logger)` | the one you pass | whatever that launcher decides |

**For an ordinary app, this is the recipe**: pass `callerTask` on the `ActivityAccess` you create in
`Application.onCreate`:

<!-- docs-sample: android-launcher -->
```kotlin
fun androidLocation(context: Context, activityAccess: ActivityAccess): LocationProvider =
    createLocationProviderWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))
```

The screen is pushed on top of your resumed activity: Back returns to your screen, and a two-pane Settings
on a tablet or foldable shows the page on its own instead of handing it to its homepage. `callerTask` does
that only when `openLocationSettings()` is called **on the main thread** with an activity resumed; called off
the main thread, or with no activity resumed, it opens the screen in a separate task, exactly like
`SeparateTask`. `SeparateTask` works from any thread. The provider never releases `activityAccess`.

The Context-only `createLocationProvider` keeps `SeparateTask`, and it stays a single function, so an
untyped reference such as `singleOf(::createLocationProvider)` still resolves.

**Logging each request** without changing behaviour: wrap the default this module would use,
`SeparateTask` (or `callerTask(activityAccess)` if that is what you pass):

```kotlin
val location: LocationProvider = createLocationProviderWithLauncher(
    context,
    SystemScreenLauncher { request ->
        logger.d { "Opening ${request.kind}" }
        SystemScreenLauncher.SeparateTask.launch(request)
    },
)
```

**A lock-task (kiosk) app** keeps a separate task (a screen on your task is inside the locked task) and opens
its lock-task allowlist window only for the launch. While the window is open the allowlist covers the whole
Settings package, not just this screen. Close it again when the launch answers `false` or throws, and
otherwise when your app resumes (`startActivity` returns before the screen is shown, so there is no earlier
point to close it at):

```kotlin
val location: LocationProvider = createLocationProviderWithLauncher(
    context,
    SystemScreenLauncher { request ->
        kioskWindow.open()                      // your code: adds the Settings package to the allowlist
        val opened: Boolean = try {
            SystemScreenLauncher.SeparateTask.launch(request)
        } catch (failure: Exception) {
            kioskWindow.close()
            throw failure
        }
        if (!opened) kioskWindow.close()
        opened                                  // on true, close the window in your activity's onResume
    },
)
```

Your launcher is called once per `openLocationSettings()` call, on the caller's thread, with one or more
candidates, most specific first (currently one, `Settings.ACTION_LOCATION_SOURCE_SETTINGS`), without launch
flags, and the kind `LocationSettingsScreen`. If it answers `false` or throws, the provider logs "Could not
open the location settings screen" at `WARN` through its `logger` (with the exception as the cause, when
there is one), the same as a device without the screen, and `openLocationSettings()` returns normally.
`true` means the start was handed to the system, not that the screen is visible: since Android 10 (API 29) a
start from the background is blocked silently, and a lock-task violation is also answered `true` with nothing
on screen. The full comparison of the presets and the per-factory defaults of every module are in
[`kmptoolkit-activity`'s guide](../kmptoolkit-activity/03-guide.md#opening-system-screens).

## Prompting to re-enable the service

`promptToEnableService()` exists for a platform that *can* raise an in-place "turn location back on" dialog
without sending the user out to Settings. Write your code against all four `LocationServicePrompt` cases and
it does the right thing today and keeps doing it if an implementation adds a real dialog:

<!-- docs-sample: prompt -->
```kotlin
suspend fun turnLocationOn(location: LocationProvider, ui: EnableUi) {
    when (location.promptToEnableService()) {
        LocationServicePrompt.ALREADY_ON -> ui.proceedWithLocation()
        LocationServicePrompt.PROMPTED -> ui.waitForTheDialog() // the OS shows its own dialog; re-check on resume
        LocationServicePrompt.NOT_NOW -> ui.tryAgainLater() // no window to raise the dialog over; try again
        LocationServicePrompt.UNSUPPORTED -> ui.showEnableLocationPrompt(onConfirm = { location.openLocationSettings() })
    }
}
```

Both factory-built providers, as they come, answer only `ALREADY_ON` or `UNSUPPORTED`. The desktop provider
answers `UNSUPPORTED`. Two ways to get a real prompt:

- **iOS:** `createLocationProvider().withSystemServicesPrompt()` asks the system to show its own "Turn On
  Location Services" alert and answers `PROMPTED`. See
  [`05-platform-notes.md`](05-platform-notes.md#the-in-place-prompt-on-each-platform) for when iOS shows it.
- **Android:** the in-place dialog exists only in Google Play Services' `SettingsClient`, which this module
  deliberately does not depend on. If your app has Play Services, write a decorator, see
  [recipe 8](#8-decorator-over-play-services-android), including the `NOT_NOW` case.

## Recipes

The numbered recipes are compilable Kotlin: their code is run by the `LocationDocSamplesTest` classes of
this module (the platform recipes by their Android, iOS and JVM siblings), except the two blocks that say
they are not compiled here (recipes 6 and 8). Each tested block is printed here exactly as the test source
holds it, and `LocationDocsSamplesInSyncTest` fails when a page and its test drift apart. The short fragments
before the recipes call functions of your own and are not compiled. `MapUi`, `CompassUi` and `EnableUi` are
your own presenters.

### 1. Nearest place

<!-- docs-sample: nearest-place -->
```kotlin
data class Place(val name: String, val coordinates: GeoCoordinates)

fun nearestPlace(here: GeoCoordinates, places: List<Place>, maxDistanceMeters: Double = 5_000.0): Place? =
    places
        .minByOrNull { place: Place -> here.distanceInMetersTo(place.coordinates) }
        ?.takeIf { place: Place -> here.distanceInMetersTo(place.coordinates) <= maxDistanceMeters }
```

`distanceInMetersTo` is a great-circle distance on a sphere (mean radius 6 371 008.8 m), within 0.6 % of the
WGS-84 geodesic. For a list of a few thousand places that is a plain linear scan; for a large table, filter
with a bounding box first ([recipe 3](#3-sql-prefilter)). The cap keeps "nearest" from meaning "nearest in
the world".

### 2. Radius check with accuracy

A fix is a circle of uncertainty, not a point. It is inside the radius only when the whole circle is, outside
only when none of it is, and otherwise uncertain:

<!-- docs-sample: radius-check -->
```kotlin
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
```

`horizontalAccuracyMeters` is the 68 % radius on Android; Apple states no confidence level. For an alert
that must be right 95 % of the time pass `accuracyScale = 1.62` on Android. A fix with no accuracy proves
neither side, so it is `UNCERTAIN`.

### 3. SQL prefilter

`boundingBox(radius)` is the smallest box holding every point within the radius, a prefilter before the exact
`distanceInMetersTo` test. **Never filter with `west..east`**: across the antimeridian the box has two
longitude ranges, and `longitudeRanges` gives them ready for `BETWEEN`.

<!-- docs-sample: sql-prefilter -->
```kotlin
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
```

The parentheses around the longitude ranges matter: `AND` binds tighter than `OR`, so without them the second
range would match rows at any latitude, exactly in the antimeridian case. Then apply the exact
`distanceInMetersTo` filter to the rows that come back. Each edge of the box is rounded outward by 5e-10
degrees (about 0.05 mm), so a point exactly on the circle is never outside it. A row stored at exactly `-180`
is not matched by a range that ends at `180`, and the other way round, although `GeoBounds.contains` treats
both as the same meridian: store the antimeridian as one value.

### 4. Qibla

<!-- docs-sample: qibla -->
```kotlin
val KAABA: GeoCoordinates = GeoCoordinates(21.422487, 39.826206)

fun qiblaBearing(here: GeoCoordinates): Double? = here.initialBearingTo(KAABA)
```

`initialBearingTo` is the initial great-circle bearing in degrees clockwise from **true** north, in
`[0, 360)`, and `null` when it is undefined: the two points coincide or are exact antipodes, or the origin is
a pole (so `null` at the Kaaba itself). From London it is about 119°; the reference rows (Cairo 136.137297°,
Moscow 176.356246°, New York 58.481706°, Jakarta 295.151725°) are pinned by `GeoMathReferenceTest`. Inside
Mecca a coarse fix makes the arrow meaningless: a 5 km error at 10 km from the Kaaba turns it by about 27°.

### 5. Compass needle

The needle compiles with the nullable bearing and never falls back silently:

<!-- docs-sample: compass-needle -->
```kotlin
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
```

**Never fall back to `magneticDegrees` against a true bearing:** the error equals the local magnetic
declination, about 12° in Moscow, 13° in New York, 15° in Seattle (the table is in
[`05-platform-notes.md`](05-platform-notes.md#magnetic-declination)).

- On Android pass `declinationAt = here`: true north is computed there **without any permission**, and a
  coarse city fix is enough, because declination varies slowly over about 100 km.
- On iOS `declinationAt` is ignored: true north comes from location updates on the heading manager while the
  app is authorized, so offer the location authorization when `trueDegrees` is `null`.
- `screenRotation` is fixed per collection. Re-collect when the screen rotates:

<!-- docs-sample: compass-rotation -->
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
fun qiblaHeadings(headings: HeadingProvider, here: GeoCoordinates, rotations: Flow<ScreenRotation>): Flow<Heading> =
    rotations.distinctUntilChanged().flatMapLatest { rotation: ScreenRotation ->
        headings.observeHeading(declinationAt = here, screenRotation = rotation)
    }
```

`needsCalibration` is `true` while the platform reports the compass as needing calibration or disturbed; show
your own hint. A reading is for the top edge of the screen as drawn in the requested rotation.

### 6. Widen to precise

A `Limited` grant (`PermissionStatus.Limited`: Android coarse without fine, iOS reduced accuracy) delivers
fixes with `isApproximate == true`. Offering the user the precise grant is `kmptoolkit-permission`'s job
(this snippet needs that module and is not compiled here):

```kotlin
suspend fun widenToPrecise(controller: PermissionController): PermissionStatus {
    val status: PermissionStatus = controller.check(CommonPermission.Location)
    return if (status is PermissionStatus.Limited) controller.request(CommonPermission.Location) else status
}
```

- **Android:** `request(CommonPermission.Location)` from `Limited` offers the precise upgrade.
- **iOS:** the same call asks for temporary full accuracy when `IosPermissionOptions.temporaryFullAccuracyPurposeKey`
  is set (a key of `NSLocationTemporaryUsageDescriptionDictionary`); iOS platform code can also call
  `requestTemporaryFullAccuracy(purposeKey)`. See
  [`docs/kmptoolkit-permission`](../kmptoolkit-permission/03-guide.md#temporary-full-accuracy-ios).
- A running stream flips `isApproximate` by itself when the grant changes: no re-collection needed.

### 7. Platform options from shared code

The `platform` slot of the options carries a type only one platform has. Build it in `androidMain` /
`iosMain` and hand it to shared code as `PlatformLocationUpdatesOptions` (or
`PlatformCurrentLocationOptions`), the pattern `kmptoolkit-permission` uses:

<!-- docs-sample: platform-options -->
```kotlin
class TrackerSession(private val provider: LocationProvider, private val platform: PlatformLocationUpdatesOptions?) {
    fun readings(): Flow<LocationReading> = provider.observeLocation(
        LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, platform = platform),
    )
}
```

<!-- docs-sample: android-wiring -->
```kotlin
fun androidTracker(context: Context): TrackerSession = TrackerSession(
    provider = createLocationProvider(context),
    platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS, maxUpdateDelay = 1.minutes),
)

fun androidCompass(context: Context): HeadingProvider =
    createHeadingProvider(context, AndroidHeadingOptions(sensor = AndroidHeadingSensor.ROTATION_VECTOR))

fun providerOfFix(fix: LocationFix): String? = fix.androidDetails?.provider
```

<!-- docs-sample: android-one-shot -->
```kotlin
suspend fun gpsOnlyFix(provider: LocationProvider): LocationReading = provider.getCurrentLocation(
    CurrentLocationOptions(
        accuracy = LocationAccuracy.HIGH_ACCURACY,
        maxAge = Duration.ZERO,
        timeout = 20.seconds,
        platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS),
    ),
)
```

<!-- docs-sample: ios-wiring -->
```kotlin
fun iosTracker(): TrackerSession = TrackerSession(
    provider = createLocationProvider().withSystemServicesPrompt(),
    platform = IosLocationUpdatesOptions(
        allowsBackgroundLocationUpdates = true,
        showsBackgroundLocationIndicator = true,
        activityType = IosActivityType.FITNESS,
    ),
)

fun iosCompass(): HeadingProvider = createHeadingProvider(showsSystemCalibration = true)

fun floorOfFix(fix: LocationFix): Int? = fix.iosDetails?.floorLevel
```

<!-- docs-sample: ios-one-shot -->
```kotlin
fun reducedOneShot(): CurrentLocationOptions = CurrentLocationOptions(
    timeout = 15.seconds,
    platform = IosCurrentLocationOptions(desiredAccuracyOverride = IosDesiredAccuracy.REDUCED),
)
```

The slot interfaces carry `@SubclassOptInRequired(ToolkitInternalApi::class)`: holding or passing one needs
no opt-in, implementing one is for this library only. A platform option handed to another platform's
provider (or to the desktop) is ignored, not an error. `AndroidLocationSource` and the batching delay are
documented in [`05-platform-notes.md`](05-platform-notes.md#android-sources), the iOS options in
[`05-platform-notes.md`](05-platform-notes.md#ios-options).

### 8. Decorator over Play Services (Android)

An app that already depends on Play Services can implement `LocationProvider` over
`FusedLocationProviderClient` in its own `androidMain` and keep shared code, and every test written against
`FakeLocationProvider`, unchanged. The block below uses Play Services types, which no module of this
repository depends on, so **it is not compiled here**. The rules it follows are the `LocationProvider`
contract listed below the block; `GeoCoordinates.orNull`, the one library call it relies on to drop an invalid
platform value, is pinned by `LocationDocSamplesTest`.

```kotlin
class FusedLocationProvider(
    private val context: Context,
    private val activityAccess: ActivityAccess,       // from kmptoolkit-activity
    private val fallback: LocationProvider = createLocationProvider(context),
) : LocationProvider {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading {
        if (!hasLocationPermission()) return unavailable(LocationUnavailability.PERMISSION_MISSING)
        if (!fallback.isLocationEnabled()) return unavailable(LocationUnavailability.SERVICE_DISABLED)
        val request: CurrentLocationRequest = CurrentLocationRequest.Builder()
            .setPriority(options.accuracy.toPriority())
            .setMaxUpdateAgeMillis(options.maxAge.inWholeMilliseconds)   // a cached fix older than maxAge is not returned
            .setDurationMillis(options.timeout.inWholeMilliseconds)
            .build()
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(options.timeout) {
                client.getCurrentLocation(request, cancellation.token).await()?.toReading()
            } ?: unavailable(LocationUnavailability.NO_FIX)
        } catch (_: SecurityException) {
            unavailable(LocationUnavailability.PERMISSION_MISSING)
        } catch (_: ApiException) {
            unavailable(LocationUnavailability.UNSUPPORTED)   // Play Services missing, outdated or unavailable
        } finally {
            cancellation.cancel()                             // a no-op once the fix arrived; stops the request on cancellation
        }
    }

    @SuppressLint("MissingPermission")
    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = callbackFlow {
        if (!hasLocationPermission()) {
            trySend(unavailable(LocationUnavailability.PERMISSION_MISSING))
            close()
            return@callbackFlow
        }
        // Seed first, register second. A live fix arrives on the main looper, so a seed sent after the registration
        // could land behind a newer fix and leave a conflated collector on an older position. The seed also carries
        // the service state, so a stream started with location off says so at once.
        val enabledAtStart: Boolean = fallback.isLocationEnabled()
        val cached: Location? = try {
            client.lastLocation.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null                                              // no cache is "no fix yet", never an error
        }
        val seed: LocationReading = when {
            !enabledAtStart -> unavailable(LocationUnavailability.SERVICE_DISABLED)
            else -> cached?.takeIf { it.isYoungerThan(options.maxSeedAge) }?.toReading()
                ?: unavailable(LocationUnavailability.NO_FIX)
        }
        trySend(seed)

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.toReading()?.let { trySend(it) }     // an invalid platform value is dropped
            }
        }
        val request: LocationRequest = LocationRequest.Builder(options.accuracy.toPriority(), 5_000L)
            .setMinUpdateDistanceMeters(options.minUpdateDistanceMeters.toFloat())
            .setMinUpdateIntervalMillis(options.minUpdateInterval.inWholeMilliseconds)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            trySend(unavailable(LocationUnavailability.PERMISSION_MISSING))
            close()
            return@callbackFlow
        }
        // The service state is the fallback's. Its first state is compared with the one the seed showed, not
        // dropped, so a switch between the seed and this collection is still reported; a return reseeds "no fix yet".
        val watch: Job = launch {
            var shown: Boolean = enabledAtStart
            fallback.observeLocationEnabled().collect { on: Boolean ->
                if (on != shown) {
                    shown = on
                    trySend(unavailable(if (on) LocationUnavailability.NO_FIX else LocationUnavailability.SERVICE_DISABLED))
                }
            }
        }
        awaitClose {
            watch.cancel()
            client.removeLocationUpdates(callback)
        }
    }.conflate()

    override suspend fun isLocationEnabled(): Boolean = fallback.isLocationEnabled()

    override fun observeLocationEnabled(): Flow<Boolean> = fallback.observeLocationEnabled()

    override fun openLocationSettings() = fallback.openLocationSettings()

    override suspend fun promptToEnableService(): LocationServicePrompt {
        if (isLocationEnabled()) return LocationServicePrompt.ALREADY_ON
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L).build())
            .build()
        return try {
            // The check needs no Activity: the application context, so no Activity is held across this suspension.
            LocationServices.getSettingsClient(context).checkLocationSettings(request).await()
            LocationServicePrompt.ALREADY_ON
        } catch (resolvable: ResolvableApiException) {
            // The Activity is touched only inside the block, on the main thread, and never stored. No resumed
            // activity: nothing to raise the dialog over right now. Not UNSUPPORTED: the device could ask properly
            // once the user is back.
            withContext(Dispatchers.Main) {
                activityAccess.withActivity { activity: Activity ->
                    try {
                        resolvable.startResolutionForResult(activity, ENABLE_LOCATION_REQUEST_CODE)
                        LocationServicePrompt.PROMPTED
                    } catch (_: IntentSender.SendIntentException) {
                        LocationServicePrompt.UNSUPPORTED
                    }
                } ?: LocationServicePrompt.NOT_NOW
            }
        } catch (_: ApiException) {
            LocationServicePrompt.UNSUPPORTED
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun LocationAccuracy.toPriority(): Int = when (this) {
        LocationAccuracy.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
        LocationAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationAccuracy.LOW_POWER -> Priority.PRIORITY_LOW_POWER
    }

    private fun Location.isYoungerThan(maxAge: Duration): Boolean =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).nanoseconds <= maxAge

    // GeoCoordinates.orNull drops a NaN or out-of-range platform value instead of throwing.
    private fun Location.toReading(): LocationReading? {
        val coordinates: GeoCoordinates = GeoCoordinates.orNull(latitude, longitude) ?: return null
        return LocationReading.Available(
            LocationFix(
                coordinates = coordinates,
                timestampEpochMillis = time,
                horizontalAccuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
            ),
        )
    }

    private companion object {
        const val ENABLE_LOCATION_REQUEST_CODE = 4711
    }
}
```

(`await()` is `kotlinx-coroutines-play-services`, `Dispatchers.Main` is `kotlinx-coroutines-android`.)
The rules the recipe encodes are part of the `LocationProvider` contract, not a matter of taste:

- **Nothing throws.** A missing permission surfaces from Fused as `SecurityException`, and a device without
  a usable Play Services as a failed task (`ApiException`); turn both into typed readings, as this module's
  own providers do.
- **Map every platform value through `GeoCoordinates.orNull`** and drop a null: an invalid fix is dropped,
  never thrown and never emitted.
- **Forward the options** you can honour: accuracy, maximum age and timeout of the one-shot, minimum distance
  and interval and the seed age of the stream. Emit `LocationReading`, and follow the stream lifecycle: stay
  open through `SERVICE_DISABLED` and `NO_FIX`, complete after `PERMISSION_MISSING` and `UNSUPPORTED`.
- **Seed first, register second.** Send the seed before the update callback is registered, so a live fix
  always arrives after it and a conflated collector never ends on an older position. The seed carries the
  service state: `SERVICE_DISABLED` when location is off at the start, else a cached fix young enough, else
  `NO_FIX`. After that, report only a change of the state (compare it with the last one shown), not its first
  value.
- **Cancellation stops the platform work:** pass a `CancellationTokenSource` to the one-shot request and
  cancel it, remove the callback in `awaitClose`, and cancel the child collection.
- **Implement `observeLocationEnabled()`**, and delegate what Fused does not improve
  (`isLocationEnabled()`, `openLocationSettings()`) to the factory-built provider, so the settings intent,
  the task it opens in and the service check stay identical. Build the fallback with
  `createLocationProviderWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))` if you want
  the screen on your own task.
- **`promptToEnableService()` never waits for the dialog's answer.** It arrives in the activity's
  `onActivityResult`, which a provider has no seam into. Return `PROMPTED` once it is up and re-check when
  the screen resumes. `NOT_NOW` when there is no resumed activity (this is what `ActivityAccess` is for:
  never hold an `Activity` in the provider). `withActivity` has no getter and runs its block on the calling
  thread, so the recipe checks the settings on the application context, switches to the main thread, and
  touches the `Activity` only inside the block.

Bind it where the rest of your app is assembled, and keep the factory-built provider for iOS:

```kotlin
// androidMain
single<LocationProvider> { FusedLocationProvider(androidContext(), activityAccess = get()) }
// iosMain
single<LocationProvider> { createLocationProvider().withSystemServicesPrompt() }
```

### 9. Desktop wiring

`createLocationProvider()` on the JVM answers `UNSUPPORTED` honestly, so shared code runs on the desktop
unchanged and shows the typed branch instead of a spinner:

<!-- docs-sample: desktop-wiring -->
```kotlin
fun desktopTracker(): TrackerSession = TrackerSession(provider = createLocationProvider(), platform = null)

suspend fun desktopFixOrNull(provider: LocationProvider): GeoCoordinates? =
    when (val reading: LocationReading = provider.getCurrentLocation()) {
        is LocationReading.Available -> reading.fix.coordinates
        is LocationReading.Unavailable -> null // UNSUPPORTED on a desktop: show the typed branch, never a spinner
    }
```

The one-shot call answers `Unavailable(UNSUPPORTED)`; the stream emits it and completes;
`isLocationEnabled()` is `false`; `openLocationSettings()` does nothing.

### 10. Final bearing, midpoint and interpolation

One-liners on top of `initialBearingTo`, `distanceInMetersTo` and `destinationPoint`:

<!-- docs-sample: bearing-recipes -->
```kotlin
fun finalBearing(from: GeoCoordinates, to: GeoCoordinates): Double? =
    to.initialBearingTo(from)?.let { backwards: Double -> (backwards + 180.0) % 360.0 }

fun pointAlong(from: GeoCoordinates, to: GeoCoordinates, fraction: Double): GeoCoordinates? {
    val distance: Double = from.distanceInMetersTo(to)
    val bearing: Double = from.initialBearingTo(to) ?: return if (distance < 1.0) from else null
    return from.destinationPoint(distance * fraction, bearing)
}

fun midpoint(a: GeoCoordinates, b: GeoCoordinates): GeoCoordinates? = pointAlong(a, b, 0.5)
```

The final bearing is the heading at the destination (a great circle changes heading along the way), so it
is the reverse of the initial bearing back, turned by 180°. All three return `null` where the direction is
undefined, for example the midpoint of two exact antipodes.

### 11. The error table

Each `LocationUnavailability` per platform, with what the user can do, is the table in
[`04-api-reference.md`](04-api-reference.md#the-error-table). The reaction in code is one `when`:

<!-- docs-sample: next-step -->
```kotlin
enum class NextStep { ASK_PERMISSION, TURN_LOCATION_ON, KEEP_WAITING, GIVE_UP }

fun nextStep(reason: LocationUnavailability): NextStep = when (reason) {
    LocationUnavailability.PERMISSION_MISSING -> NextStep.ASK_PERMISSION
    LocationUnavailability.SERVICE_DISABLED -> NextStep.TURN_LOCATION_ON
    LocationUnavailability.NO_FIX -> NextStep.KEEP_WAITING
    else -> NextStep.GIVE_UP
}
```

## Common mistakes

- **Recreating the provider per screen.** `createLocationProvider(...)` is cheap but the object it returns is
  meant to be built once and shared: create it where you assemble your object graph.
- **Treating `Unavailable` as an error.** It is the documented answer for a missing permission, a switched-off
  service or no signal. Nothing throws. Branch on the reason.
- **A `when` over `LocationUnavailability` without an `else`.** The set can grow in a minor version.
- **Constructing `GeoCoordinates` from data you do not control.** It throws for a non-finite or out-of-range
  value. Use `GeoCoordinates.orNull`.
- **Using `west..east` of a `GeoBounds` in SQL or a range check.** Use `longitudeRanges` or `contains`.
- **Falling back to the magnetic heading against a true bearing.** The error is the local declination.
- **Reading a distance decision off `isApproximate` alone.** Use `horizontalAccuracyMeters`
  ([recipe 2](#2-radius-check-with-accuracy)).
- **Assuming `observeLocation()` is silent until a real fix arrives.** It seeds at once, with the freshest
  cached fix within `maxSeedAge` or a reason, precisely so a `combine()` downstream never hangs.
- **Expecting a stream to survive a revoked permission.** It ends after `PERMISSION_MISSING`; on Android a
  revoke also kills the process.

## Next

- [`04-api-reference.md`](04-api-reference.md) — every public declaration and the error table.
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, the Android and iOS rules, the Play Services
  trade-off.

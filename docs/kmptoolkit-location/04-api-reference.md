# kmptoolkit-location — API reference

Package `io.github.jamal_wia.kmptoolkit.location` (the fakes are in `...location.testing`, see
[`06-testing.md`](06-testing.md)). Every declaration is `public` unless stated; `explicitApi()` is strict.
"Since" is the release the declaration appeared in: everything marked 6.0.0 is new or changed in that
release ([`07-migration.md`](07-migration.md)).

- [Conventions](#conventions)
- [commonMain](#commonmain) — geo math, readings, options, providers, heading
- [androidMain](#androidmain)
- [iosMain](#iosmain)
- [jvmMain](#jvmmain)
- [The error table](#the-error-table)

## Conventions

- **Typed state, never display text.** Nothing here returns a string meant for the user.
- **A `null` field is never a guess.** Every optional field of a `LocationFix` is `null` when the platform
  reported none or cannot tell.
- **Slot interfaces.** `PlatformFixDetails`, `PlatformCurrentLocationOptions` and
  `PlatformLocationUpdatesOptions` carry `@SubclassOptInRequired(ToolkitInternalApi::class)`: you can hold,
  pass and read them freely, implementing one is for this library only (the marker lives in
  `kmptoolkit-core`, an `api` dependency of this module).
- **`@ToolkitInternalApi`.** `checkMinChangeDegrees` is public only because the fakes live in another
  module. It is **not** part of the public contract and can change in any release.
- **Thread safety.** Provider methods are safe to call from any thread; collection and cancellation follow
  coroutine rules. The geo functions are pure.
- **Durations are `kotlin.time.Duration`.** Because it is an inline class, a public member that takes one
  has a mangled JVM name (`getMaxAge-UwyO8pc`): Java callers use the mangled name, Kotlin callers do not
  see it.

## commonMain

### `GeoCoordinates`

```kotlin
public data class GeoCoordinates(val latitude: Double, val longitude: Double) {
    public companion object {
        public fun orNull(latitude: Double, longitude: Double): GeoCoordinates?
    }
}
```

A WGS-84 position in decimal degrees. The constructor, and `copy`, throw `IllegalArgumentException` unless
both values are finite, `latitude` is in `[-90, 90]` and `longitude` in `[-180, 180]`. There is no silent
normalisation: a longitude of 190 is rejected, not turned into -170. **This is a runtime throw the compiler
cannot show.** `orNull` applies the same rule and returns `null`; use it for data you do not control.
`-180` and `180` are both accepted and name the same meridian (the distance between them is zero).
Equality is by value of the doubles, so `0.0` and `-0.0` differ: compare positions with `distanceInMetersTo`.

### Geo math (`GeoMath.kt`)

Pure Kotlin on a sphere of radius 6 371 008.8 m.

| Function | Contract |
|---|---|
| `GeoCoordinates.distanceInMetersTo(other): Double` | Great-circle distance in meters, atan2 form, within 0.6 % of the WGS-84 geodesic. May differ from Android's `Location.distanceBetween` by up to about 0.56 %, and more near antipodes, where Android's iteration can stop early. About 0 (below 1e-8 m) for the same ground point written differently (a pole at any longitude, 180 against -180); exactly `0.0` for equal values. Throws nothing |
| `GeoCoordinates.initialBearingTo(other): Double?` | Initial bearing in degrees clockwise from true north, in `[0, 360)` and never `360.0`, within 0.19° of the geodesic below 10 000 km. `null` when undefined: coincident points (including 180 / -180), exact antipodes, an origin at a pole |
| `GeoCoordinates.destinationPoint(distanceMeters, bearingDegrees): GeoCoordinates` | The point along the great circle leaving at the bearing; a negative distance goes backwards. Longitude of the result in `[-180, 180)`, never NaN at or across a pole. Accepts every finite bearing (taken modulo 360). Throws `IllegalArgumentException` for a non-finite argument |
| `GeoCoordinates.boundingBox(radiusMeters): GeoBounds` | The smallest box holding every point within the radius, a prefilter before `distanceInMetersTo`; each edge is rounded outward by 5e-10° (about 0.05 mm). A pole inside the circle gives the full longitude span, and so does a circle that passes within a few meters of a pole. Throws `IllegalArgumentException` for a negative or non-finite radius |
| `angleDifference(fromDegrees, toDegrees): Double` | Signed shortest turn in `(-180, 180]`, positive clockwise; a half turn is `180.0` both ways; inputs of any magnitude. Throws `IllegalArgumentException` for non-finite input |

### `GeoBounds`

```kotlin
public data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    public val crossesAntimeridian: Boolean
    public val longitudeRanges: List<ClosedFloatingPointRange<Double>>
    public operator fun contains(point: GeoCoordinates): Boolean
    public companion object {
        public fun orNull(south: Double, west: Double, north: Double, east: Double): GeoBounds?
    }
}
```

A latitude/longitude box. `west > east` means it crosses the antimeridian; a polar cap spans `west = -180`,
`east = 180`. The constructor throws `IllegalArgumentException` unless all four values are finite,
`-90 <= south <= north <= 90`, and `west` and `east` are in `[-180, 180]`; `orNull` returns `null` for the same
inputs. `crossesAntimeridian` is `west > east`; an east edge exactly at 180 is not a crossing.
`longitudeRanges` is one range, or two when the box crosses (`west..180` and `-180..east`), each with
`start <= endInclusive`, ready for SQL `BETWEEN`; a row stored at exactly `-180` is not matched by a range
that ends at `180` and the other way round, so store the antimeridian as one value. `contains` is
antimeridian-aware, edges included, and treats 180 and -180 as one meridian. **In SQL use `longitudeRanges`,
never `west..east`.**

### `LocationFix`

```kotlin
public data class LocationFix(
    val coordinates: GeoCoordinates,
    val timestampEpochMillis: Long,
    val horizontalAccuracyMeters: Double? = null,
    val ellipsoidalAltitudeMeters: Double? = null,
    val mslAltitudeMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val courseDegrees: Double? = null,
    val courseAccuracyDegrees: Double? = null,
    val isSimulated: Boolean? = null,
    val isApproximate: Boolean? = null,
    val platformDetails: PlatformFixDetails? = null,
)
```

One position the platform reported. The per-platform source of every field is the table in
[`05-platform-notes.md`](05-platform-notes.md#fix-fields-per-platform).

| Field | Meaning |
|---|---|
| `coordinates` | Where |
| `timestampEpochMillis` | Wall-clock time the platform took the fix, Unix epoch milliseconds. For display and storage only, never for age decisions: providers judge age on the platform's monotonic clock, because a wall clock jumps when it is corrected |
| `horizontalAccuracyMeters` | Horizontal uncertainty radius. Android: 68 % confidence, multiply by 1.62 for about 95 %. Apple states no confidence level |
| `ellipsoidalAltitudeMeters` | Height above the WGS-84 ellipsoid: Android when reported, iOS 15 and later |
| `mslAltitudeMeters` | Height above mean sea level: iOS always, Android API 34 and later when the platform reports it. The two altitudes differ by the geoid height, up to about 100 m, which is why the datum is in the name |
| `verticalAccuracyMeters` | Uncertainty of both altitudes (Android API 26 and later, iOS). A negative platform value nulls all three altitude fields |
| `speedMetersPerSecond`, `speedAccuracyMetersPerSecond` | Ground speed and its uncertainty (accuracy: Android API 26+, iOS 10+) |
| `courseDegrees`, `courseAccuracyDegrees` | Direction of travel in `[0, 360)` from true north (not the device heading; unreliable when nearly stationary) and its uncertainty (Android API 26+, iOS 13.4+) |
| `isSimulated` | Whether the OS marks the fix as mocked or simulated by software; `null` where it cannot tell |
| `isApproximate` | `true` when delivered approximate: approximate-only grant, or an iOS `REDUCED` request; `false` when precise; `null` where unknown. For any distance decision use `horizontalAccuracyMeters` |
| `platformDetails` | Facts only one platform reports: read through `androidDetails` / `iosDetails`. `null` from app-written providers unless they set it |

`PlatformFixDetails` is the slot interface behind `platformDetails`.

### `LocationReading`, `LocationUnavailability`

```kotlin
public sealed interface LocationReading {
    public data class Available(val fix: LocationFix) : LocationReading
    public data class Unavailable(val reason: LocationUnavailability) : LocationReading
}
public enum class LocationUnavailability { PERMISSION_MISSING, SERVICE_DISABLED, NO_FIX, UNSUPPORTED }
public fun LocationReading.fixOrNull(): LocationFix?
```

What a request produced: a fix that met the request, or the typed reason there is none. The set of
`LocationReading` subtypes is closed; the set of reasons **can grow, so give a `when` over it an `else`
branch**. The meaning of each reason on each platform is the [error table](#the-error-table).
`fixOrNull()` is the fix of an `Available`, or `null`.

### Options

```kotlin
public enum class LocationAccuracy { HIGH_ACCURACY, BALANCED, LOW_POWER }

public data class CurrentLocationOptions(
    val accuracy: LocationAccuracy = LocationAccuracy.BALANCED,
    val maxAge: Duration = 30.seconds,
    val timeout: Duration = 30.seconds,
    val platform: PlatformCurrentLocationOptions? = null,
)

public data class LocationUpdatesOptions(
    val accuracy: LocationAccuracy = LocationAccuracy.BALANCED,
    val minUpdateDistanceMeters: Double = 0.0,
    val minUpdateInterval: Duration = Duration.ZERO,
    val maxSeedAge: Duration = 30.seconds,
    val platform: PlatformLocationUpdatesOptions? = null,
)
```

- `LocationAccuracy` is how hard the platform should work: a request, not a guarantee (an approximate grant
  caps every level). `HIGH_ACCURACY` is GNSS-grade and works offline; `BALANCED` is about 100 m and may need a
  data connection; `LOW_POWER` is city-level. The per-platform mapping is in
  [`05-platform-notes.md`](05-platform-notes.md#accuracy-mapping).
- `CurrentLocationOptions` throws `IllegalArgumentException` for a negative `maxAge` or a non-positive
  `timeout`. `maxAge`: the oldest cached fix acceptable, judged on the platform's monotonic clock;
  `Duration.ZERO` forces a fresh fix, `Duration.INFINITE` accepts any cache. `timeout`: how long to wait
  for a fresh fix before `Unavailable(NO_FIX)`; `Duration.INFINITE` waits until cancelled.
- `LocationUpdatesOptions` throws `IllegalArgumentException` for a negative or non-finite
  `minUpdateDistanceMeters`, a negative or infinite `minUpdateInterval`, or a negative `maxSeedAge`.
  `minUpdateDistanceMeters`: the smallest movement between two emitted fixes, measured from the last emitted
  fix; `0.0` means every update. `minUpdateInterval`: the shortest time between two emitted fixes, honoured on
  every platform (iOS throttled in common code). Both are a trailing-edge throttle that never delays or drops
  an `Unavailable`. `maxSeedAge`: the oldest cached fix the stream may emit first, `ZERO` to
  `Duration.INFINITE`; with none that young the first value is `Unavailable(NO_FIX)`.
- `PlatformCurrentLocationOptions` and `PlatformLocationUpdatesOptions` are the slot interfaces: only a type
  of the matching call can be passed (an iOS stream-only option cannot be handed to the one-shot call).

### `LocationProvider`, `LocationServicePrompt`

```kotlin
public interface LocationProvider {
    public suspend fun getCurrentLocation(options: CurrentLocationOptions = CurrentLocationOptions()): LocationReading
    public fun observeLocation(options: LocationUpdatesOptions = LocationUpdatesOptions()): Flow<LocationReading>
    public suspend fun isLocationEnabled(): Boolean
    public fun observeLocationEnabled(): Flow<Boolean>
    public fun openLocationSettings()
    public suspend fun promptToEnableService(): LocationServicePrompt =
        if (isLocationEnabled()) LocationServicePrompt.ALREADY_ON else LocationServicePrompt.UNSUPPORTED
}
public enum class LocationServicePrompt { ALREADY_ON, PROMPTED, UNSUPPORTED, NOT_NOW }
```

Device location: one-shot reads, a stream and the service state. Requests no permission. An invalid
platform fix (NaN or out-of-range coordinates; on iOS also a negative horizontal accuracy) is dropped, never
thrown and never emitted.

- **`getCurrentLocation`**: a cached fix within `maxAge`, else a fresh fix (the available sources raced)
  within `timeout`, else a typed reason. Never throws for a missing permission, a disabled service, a
  platform error or a timeout. Cancellable.
- **`observeLocation`**: a cold, conflated stream until cancelled. Seeds at once (a cached fix within
  `maxSeedAge`, else a reason), completes after `PERMISSION_MISSING` or `UNSUPPORTED`, stays open through
  `SERVICE_DISABLED` and `NO_FIX`, identically on every platform. A busy collector skips intermediate
  readings and ends on the newest; the underlying request stops when the flow is cancelled.
- **`isLocationEnabled`**: whether the device-wide service is on now; independent of the app's permission.
  `suspend` so the check can run off the main thread (on iOS `locationServicesEnabled()` warns when called
  there). On Android it is device-wide only.
- **`observeLocationEnabled`** *(new in 6.0.0; custom implementations must implement it)*: the device-wide
  state, emitted at once and on every change (distinct), without starting location updates.
- **`openLocationSettings`**: opens the system location settings (iOS: the app's own settings page; desktop:
  does nothing). Never throws.
- **`promptToEnableService`**: asks for an in-place "turn location on" prompt where one exists. The default
  body answers `ALREADY_ON` when the service is on, else `UNSUPPORTED`. `LocationServicePrompt`:
  `ALREADY_ON` (nothing asked), `PROMPTED` (the system's dialog was raised; read the state again when the
  screen returns), `UNSUPPORTED` (no in-place prompt; the only route is `openLocationSettings`), `NOT_NOW`
  (a prompt exists but there is no window to raise it over; nothing was asked).

### Heading

```kotlin
public data class Heading(
    val magneticDegrees: Double,
    val trueDegrees: Double?,
    val accuracyDegrees: Double?,
    val needsCalibration: Boolean,
)
public enum class ScreenRotation { NATURAL, ROTATION_90, ROTATION_180, ROTATION_270 }
public interface HeadingProvider {
    public val isAvailable: Boolean
    public fun observeHeading(
        minChangeDegrees: Double = 1.0,
        declinationAt: GeoCoordinates? = null,
        screenRotation: ScreenRotation = ScreenRotation.NATURAL,
    ): Flow<Heading>
}
@ToolkitInternalApi
public fun checkMinChangeDegrees(minChangeDegrees: Double)
```

- `Heading`: one compass reading for the top edge of the screen as drawn in the requested rotation, angles in
  `[0, 360)`. `magneticDegrees` is always present; `trueDegrees` is `null` when no declination could be
  applied (iOS without location authorization; Android without `declinationAt` and without a permitted fix);
  `accuracyDegrees` is the platform's error estimate (iOS `headingAccuracy`, the Android rotation-vector
  estimate converted from radians), `null` when none, never invented; `needsCalibration` is `true` while the
  platform reports the compass as needing calibration or disturbed.
- `ScreenRotation` is the device's physical rotation from its natural orientation, counter-clockwise (the
  drawn UI's rotation clockwise), exactly Android's `Surface.ROTATION_*`; iOS maps it to `headingOrientation`.
- `HeadingProvider` is separate from `LocationProvider` so location-only implementations stay untouched.
  `isAvailable` says whether the device can report a heading at all; when `false`, `observeHeading` never
  emits (the collection stays open and silent).
- `observeHeading` is cold: it registers on collection and releases on cancellation; concurrent collectors
  are independent, each with its own filter and rotation. It emits the first reading at once, and afterwards
  when the heading moved at least `minChangeDegrees` from the **last emitted** heading, measured with
  `angleDifference` (so 359.5 to 0.5 is a change of 1). A change of `needsCalibration`, or of `trueDegrees`
  between `null` and non-null, is always emitted, which means "never filtered out", not "never skipped by a
  slow collector" (the stream is conflated). `minChangeDegrees` throws `IllegalArgumentException` when negative
  or non-finite, **from the call itself**, before any collection (`0.0` is accepted: every update).
  `declinationAt` is used on Android only; `screenRotation` is fixed per collection, so re-collect on rotation.
- `checkMinChangeDegrees` is the shared argument check of the providers and fakes; it is marked
  `@ToolkitInternalApi` and is not public contract.

## androidMain

### Factories

```kotlin
public fun createLocationProvider(context: Context, logger: Logger = NoopLogger): LocationProvider
public fun createLocationProviderWithLauncher(
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    logger: Logger = NoopLogger,
): LocationProvider
public object LocationSettingsScreen : SystemScreenKind
```

The provider is over `LocationManager`: the framework `FUSED_PROVIDER` on API 31 and later when the device has
it, else the available providers raced; never Play Services. It retains the application context. The app
declares `ACCESS_FINE_LOCATION` and/or `ACCESS_COARSE_LOCATION`; without either every call answers
`PERMISSION_MISSING` at once. With coarse only, fixes read `isApproximate == true`. The second factory opens
the settings screen through `systemScreenLauncher` (`SystemScreenLauncher.callerTask(activityAccess)` in an
ordinary app); `createLocationProvider` uses `SystemScreenLauncher.SeparateTask`. Each
`openLocationSettings()` makes exactly one `launch` call, with the kind `LocationSettingsScreen`. A launcher
that answers `false` or throws is reported through `logger` as "could not open", and `openLocationSettings`
never throws. `LocationSettingsScreen` is that kind (unchanged). The `config` parameter of 4.x is gone.

### Options and details

```kotlin
public data class AndroidCurrentLocationOptions(val source: AndroidLocationSource = AndroidLocationSource.AUTO)
public data class AndroidLocationUpdatesOptions(
    val source: AndroidLocationSource = AndroidLocationSource.AUTO,
    val maxUpdateDelay: Duration = Duration.ZERO,
)
public enum class AndroidLocationSource { AUTO, FUSED, GPS, NETWORK, PASSIVE }
public data class AndroidFixDetails(val provider: String, val elapsedRealtimeNanos: Long)
public val LocationFix.androidDetails: AndroidFixDetails?
```

- `AndroidCurrentLocationOptions` / `AndroidLocationUpdatesOptions` are the `platform` slot types; build them in
  Android code. `AndroidLocationUpdatesOptions` throws `IllegalArgumentException` for a negative or infinite
  `maxUpdateDelay`, which lets the platform batch fixes up to that delay to save power (API 31 and later;
  no effect below; `ZERO` = no batching).
- `AndroidLocationSource`: `AUTO` is the library policy (the framework `FUSED_PROVIDER` when present and on,
  else the providers by accuracy, see [`05-platform-notes.md`](05-platform-notes.md#android-sources));
  `FUSED` is `LocationManager.FUSED_PROVIDER` only (API 31+); `GPS` raw GNSS, offline; `NETWORK` Wi-Fi and
  cell positioning, usually needs data; `PASSIVE` only fixes other apps already requested, no added power.
  A source absent on the device answers `Unavailable(UNSUPPORTED)`; one that exists but is switched off
  answers `SERVICE_DISABLED` at once.
- `AndroidFixDetails`: the provider that produced the fix (`"gps"`, `"network"`, `"fused"`, `"passive"`, an
  OEM name; `""` when the platform reported none) and `Location.getElapsedRealtimeNanos` (boot-clock stamp,
  meaningless after a reboot). `androidDetails` is the fix's Android details, or `null` when it did not come
  from the Android provider.

### Heading

```kotlin
public fun createHeadingProvider(
    context: Context,
    options: AndroidHeadingOptions = AndroidHeadingOptions(),
    logger: Logger = NoopLogger,
): HeadingProvider
public data class AndroidHeadingOptions(val sensor: AndroidHeadingSensor = AndroidHeadingSensor.AUTO)
public enum class AndroidHeadingSensor { AUTO, ROTATION_VECTOR, GEOMAGNETIC_ROTATION_VECTOR, ACCELEROMETER_MAGNETOMETER }
```

`AUTO` takes the rotation vector, else the geomagnetic rotation vector, else the accelerometer together with
the magnetometer. An explicitly chosen sensor that is absent makes `isAvailable` `false`: the provider never
substitutes another sensor. `ROTATION_VECTOR`: gyroscope-fused, the smoothest, the most power.
`GEOMAGNETIC_ROTATION_VECTOR`: no gyroscope, lower power, noisier. `ACCELEROMETER_MAGNETOMETER`: the raw pair
through `SensorManager.getRotationMatrix`, without smoothing. The rules of the heading are in
[`05-platform-notes.md`](05-platform-notes.md#heading-android).

## iosMain

```kotlin
public fun createLocationProvider(logger: Logger = NoopLogger): LocationProvider
public fun LocationProvider.withSystemServicesPrompt(): LocationProvider
public data class IosCurrentLocationOptions(val desiredAccuracyOverride: IosDesiredAccuracy? = null)
public data class IosLocationUpdatesOptions(
    val allowsBackgroundLocationUpdates: Boolean = false,
    val showsBackgroundLocationIndicator: Boolean = false,
    val pausesLocationUpdatesAutomatically: Boolean = false,
    val activityType: IosActivityType = IosActivityType.OTHER,
    val desiredAccuracyOverride: IosDesiredAccuracy? = null,
)
public enum class IosActivityType { OTHER, AUTOMOTIVE_NAVIGATION, FITNESS, OTHER_NAVIGATION, AIRBORNE }
public enum class IosDesiredAccuracy { BEST_FOR_NAVIGATION, BEST, NEAREST_TEN_METERS, HUNDRED_METERS, KILOMETER, THREE_KILOMETERS, REDUCED }
public data class IosFixDetails(val floorLevel: Int?, val isProducedByAccessory: Boolean?)
public val LocationFix.iosDetails: IosFixDetails?
public fun createHeadingProvider(showsSystemCalibration: Boolean = false, logger: Logger = NoopLogger): HeadingProvider
```

- `createLocationProvider(logger)`: the provider over `CLLocationManager`. It ignores the transient
  `kCLErrorLocationUnknown` error, drops invalid fixes (a negative `horizontalAccuracy`), maps the accuracy per
  request, and answers `PERMISSION_MISSING` at once when the app is not authorized. No permission request
  happens here. Every manager is created, started and released on the main queue. The minimum is **iOS 15**.
- `withSystemServicesPrompt()` *(unchanged)*: raises iOS's own "Turn On Location Services" alert from
  `promptToEnableService`: opt-in, `PROMPTED` means asked, not shown.
- `IosCurrentLocationOptions` / `IosLocationUpdatesOptions` are the `platform` slot types. `allowsBackgroundLocationUpdates`
  needs `location` in `UIBackgroundModes`, otherwise the stream fails with `IllegalStateException` naming the
  key; the check runs when collection starts, **before any authorization read**, so it fails in every
  authorization state. `showsBackgroundLocationIndicator` is the blue status-bar indicator.
  `pausesLocationUpdatesAutomatically` defaults to `false`, unlike Apple's `true`: while paused the stream
  emits `Unavailable(NO_FIX)` and the provider restarts updates on the next `didBecomeActive`.
  `desiredAccuracyOverride` replaces the mapping of the common `LocationAccuracy` with an exact CoreLocation
  accuracy; `REDUCED` gives reduced-grade fixes (about 1-20 km) even under a full grant, and such fixes read
  `isApproximate == true`.
- `IosFixDetails`: `floorLevel` is the building floor (`CLFloor.level`) when the venue provides one;
  `isProducedByAccessory` says whether an external accessory such as a GNSS receiver produced the fix (iOS 15+).
  `iosDetails` is `null` when the fix did not come from the iOS provider.
- `createHeadingProvider(showsSystemCalibration, logger)`: the compass over `CLLocationManager` heading
  updates; while authorized it also runs location updates on its own manager at three kilometres so that
  `trueHeading` is valid, and stops both on cancellation. `showsSystemCalibration` lets iOS show its calibration
  overlay while a collection runs. See [`05-platform-notes.md`](05-platform-notes.md#heading-ios).

## jvmMain

```kotlin
public fun createLocationProvider(): LocationProvider
public fun createHeadingProvider(): HeadingProvider
```

There is no desktop location source, so `createLocationProvider()` answers honestly: `getCurrentLocation`
answers `Unavailable(UNSUPPORTED)`; `observeLocation` emits it and completes; `isLocationEnabled()` is `false`
and `observeLocationEnabled()` emits `false` once and stays open; `promptToEnableService()` answers
`UNSUPPORTED`; `openLocationSettings()` does nothing. `createHeadingProvider()` has no sensor: `isAvailable` is
`false` and `observeHeading` never emits (the collection stays open until cancelled), but it still validates
`minChangeDegrees`. Neither factory takes a logger or options; both are stateless, so there is nothing to
release. There are no JVM platform-layer types: the slot interfaces have no JVM implementations.

## The error table

Each `LocationUnavailability` per platform, what the stream does after it, and what the user can do. The
rules apply to the one-shot call and the stream alike (for the same device state both give the same reason).

| Reason | Android | iOS | Desktop JVM | The stream | What the user can do |
|---|---|---|---|---|---|
| `PERMISSION_MISSING` | neither `ACCESS_FINE_LOCATION` nor `ACCESS_COARSE_LOCATION` is held; also a `SecurityException` from a provider once the source is on (every candidate refused, or the source you named refused). While the service is off, `SERVICE_DISABLED` wins | authorization `notDetermined`, `restricted`, or `denied` with the services on. Answered at once, never after the timeout | never | **completes** after it (also when the grant is revoked while it runs: iOS reports it on `locationManagerDidChangeAuthorization`; on Android a revoke kills the process) | grant location: request `CommonPermission.Location` (`kmptoolkit-permission`) or, once denied for good, open the app's settings. `restricted` (parental controls, device management) cannot be changed in the app |
| `SERVICE_DISABLED` | the device-wide location switch is off (`isLocationEnabled()` on API 28+, else no gps or network provider on); or a source you named (`FUSED`, `GPS`, `NETWORK`) exists but is off; `AUTO` reads it only when none of its candidates is on. Answered at once, never after a timeout. Also while every candidate refuses the app and the service is off | `locationServicesEnabled() == false` (`denied` with the services off is `SERVICE_DISABLED`, not `PERMISSION_MISSING`) | never | **stays open** and reseeds when the service returns (device-wide or the named source) | turn Location on: `openLocationSettings()`, or `promptToEnableService()` where a prompt exists (iOS `withSystemServicesPrompt()`; Android with a Play Services decorator) |
| `NO_FIX` | everything is on, but there is no fix within the timeout (one-shot) or yet (stream); also an unexpected platform failure inside `getCurrentLocation`, logged | everything is on, no fix; `kCLErrorLocationUnknown`; the stream paused (opt-in auto-pause) or holding after `kCLErrorDenied` under a granted status | never | **stays open** | nothing to change: wait, move to open sky, or lower the accuracy |
| `UNSUPPORTED` | no `LocationManager`, or the `AndroidLocationSource` you asked for is absent on the device (only an absent provider; `FUSED` below API 31 or on a device without it) | never, for the default policy | always | **completes** after it | nothing: offer a flow without location |

## See also

- [`05-platform-notes.md`](05-platform-notes.md) for the behaviour behind every row.
- [`07-migration.md`](07-migration.md) for the 4.x / 5.x differences.

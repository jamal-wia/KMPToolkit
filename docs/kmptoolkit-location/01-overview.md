# kmptoolkit-location — Overview

The device's geographic position for shared code, with the questions an app asks about it answered in
types instead of nulls. One interface, `LocationProvider`, with a one-shot suspend function, a `Flow` of
readings and the device-wide service state; a compass, `HeadingProvider`; and pure geo math
(distance, bearing, destination point, an antimeridian-safe bounding box) on validated coordinates.

```kotlin
class MapPresenter(private val location: LocationProvider) {

    suspend fun centerOnMe(): LocationReading = location.getCurrentLocation()

    fun trackMe(): Flow<LocationReading> = location.observeLocation()
}
```

Every `LocationReading` is either `Available(fix)` — the coordinates, the accuracy and whatever else the
platform reported — or `Unavailable(reason)`, one of `PERMISSION_MISSING`, `SERVICE_DISABLED`, `NO_FIX` and
`UNSUPPORTED`. Nothing at that call site knows about `LocationManager`, `CLLocationManager`, a delegate
protocol, or which thread a callback arrives on.

## What the module is

- **Geo math.** `GeoCoordinates` validates itself, and `distanceInMetersTo`, `initialBearingTo`,
  `destinationPoint`, `boundingBox` / `GeoBounds` and `angleDifference` are plain Kotlin, identical on every
  target, with the same answer on the phone and in a desktop unit test.
- **Readings with reasons.** `getCurrentLocation()` and `observeLocation()` return `LocationReading`: a fix
  with its horizontal accuracy, altitudes with their datum, speed, course, and the flags `isSimulated` and
  `isApproximate`, or a typed reason there is none.
- **A platform layer.** Per-call `CurrentLocationOptions` / `LocationUpdatesOptions` (accuracy, maximum age
  of a cached fix, timeout, minimum distance and interval) plus a slot for options only one platform has:
  the Android source and batching, the iOS background mode, indicator, auto-pause and activity type.
- **Heading.** `HeadingProvider` reports the compass heading for the screen as drawn, with true north,
  accuracy and a calibration flag. It is a separate interface, so a location-only implementation of
  `LocationProvider` stays untouched.
- **Desktop.** The `jvm` target publishes the same types and math. Its providers answer the typed
  `UNSUPPORTED` state, because no desktop location source exists in this library.

## The three platforms

| | Android | iOS | Desktop JVM |
|---|---|---|---|
| Position source | `android.location.LocationManager`: the framework `FUSED_PROVIDER` on API 31 and later when the device has it, else the available providers raced. Never Google Play Services | `CLLocationManager` | none |
| Location factory | `createLocationProvider(context, logger)`, `createLocationProviderWithLauncher(context, launcher, logger)` | `createLocationProvider(logger)` | `createLocationProvider()` |
| Heading factory | `createHeadingProvider(context, options, logger)` over `SensorManager` | `createHeadingProvider(showsSystemCalibration, logger)` over `CLLocationManager` heading updates | `createHeadingProvider()`: `isAvailable == false` |
| Platform options | `AndroidCurrentLocationOptions`, `AndroidLocationUpdatesOptions`, `AndroidLocationSource` | `IosCurrentLocationOptions`, `IosLocationUpdatesOptions`, `IosActivityType`, `IosDesiredAccuracy` | none |
| Platform facts on a fix | `LocationFix.androidDetails` | `LocationFix.iosDetails` | none |
| Permission your app declares | `ACCESS_FINE_LOCATION` and/or `ACCESS_COARSE_LOCATION` | `NSLocationWhenInUseUsageDescription` (and the others, see [`02-getting-started.md`](02-getting-started.md)) | none |
| Minimum | Android API 24 | iOS 15 | JVM 21 |

The behaviour behind each cell is in [`05-platform-notes.md`](05-platform-notes.md).

## Two signals about precision

A grant and a fix both say something about precision, and they are not the same fact.

- **`PermissionStatus.Limited`** (from `kmptoolkit-permission`) describes **what the app was granted**:
  approximate location only (Android coarse without fine, iOS reduced accuracy). It is the signal to
  offer the user a way to widen the grant.
- **`LocationFix.isApproximate`** describes **what one fix was delivered under**: `true` under an
  approximate-only grant, and also on iOS for a request of `IosDesiredAccuracy.REDUCED` made under a full
  grant; `false` when it was delivered precise; `null` where the platform cannot tell. A running stream
  re-reads it when the grant changes, so the same stream flips from `true` to `false` by itself once the
  user widens the grant.

Neither one is a distance. Use `horizontalAccuracyMeters` for any decision about how far away something
is: [`03-guide.md`](03-guide.md#2-radius-check-with-accuracy) shows the check.

## What this is **not**

**Not in the API, documented as recipes** (every one is in [`03-guide.md`](03-guide.md)):

- the final bearing, the midpoint and interpolation along a route;
- the nearest of a list, a within-radius check, the 95 % radius;
- Qibla.

**Not in this module:**

- **Not polygons or routes.** No point-in-polygon, no routing, no geocoding or reverse geocoding.
- **Not geofencing.** No region monitoring, no significant-change or visit monitoring.
- **Not an ellipsoidal solver.** The math is on a sphere (mean radius 6 371 008.8 m), within 0.6 % of the
  WGS-84 geodesic for distance. A Karney solver is the upgrade path, not part of this release.
- **Not Google Play Services.** The Android side is plain `LocationManager`, a deliberate departure from
  the donor codebase; an app that wants Play Services writes a decorator
  ([`03-guide.md`](03-guide.md#8-decorator-over-play-services-android)).
- **Not a desktop location source.** No IP lookup and no OS API on the desktop: the providers answer
  `UNSUPPORTED`. A future engine artifact can implement `LocationProvider`.
- **Not a permission library.** It declares no Android permission and requests no iOS authorization;
  `kmptoolkit-permission` owns the requests, including iOS temporary full accuracy.
- **Not a cache.** No fix is stored or persisted by this module. A platform's own cached fix may answer a
  call or seed a stream, within an age you choose.
- **Not user-facing text.** Reasons are typed state; the copy is your app's.
- **Not Compose, not a DI module.** Plain Kotlin; one interface plus a factory per platform.

**Not now:**

- Android background location as a library feature: it is the app's own foreground service.
- Filling `mslAltitudeMeters` on Android below API 34: the library reads only the platform's own value and
  does not run an on-device geoid model.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — dependency, permissions, the factories, a first call.
- [`03-guide.md`](03-guide.md) — one-shot versus stream, the recipes (nearest place, radius check, SQL
  prefilter, Qibla, compass arrow, desktop wiring, a Play Services decorator).
- [`04-api-reference.md`](04-api-reference.md) — every public declaration and the error table.
- [`05-platform-notes.md`](05-platform-notes.md) — **required reading**: what each platform guarantees,
  permissions, the Android source rules, the iOS rules, heading and the datum notes.
- [`06-testing.md`](06-testing.md) — `FakeLocationProvider`, `FakeHeadingProvider` and `fakeFix`, in
  `kmptoolkit-location-testing`.
- [`07-migration.md`](07-migration.md) — moving from 4.x / 5.x: every breaking change and what to do.

# kmptoolkit-location — Overview

Platform-agnostic access to the device's geographic position. One interface, `LocationProvider`,
with a one-shot suspend fun for a single fix, a `Flow` for continuous updates, and a suspend check
for whether the device-wide location service is even on.

```kotlin
class MapPresenter(private val location: LocationProvider) {

    suspend fun centerOnMe(): GeoCoordinates? = location.getCurrentLocation()

    fun trackMe(): Flow<GeoCoordinates?> = location.observeLocation()
}
```

Nothing at that call site knows about `LocationManager`, `Criteria`, `CLLocationManager`, or a
delegate protocol.

## The problem it solves

Getting a location fix is a platform-specific dance with a shared failure mode: the fix might never
arrive, and neither platform tells you *why* in a shape common code can act on.

- **Android** has two unrelated APIs — `getLastKnownLocation` for a cheap, possibly stale cached fix,
  and `requestLocationUpdates` for a fresh one, which never resolves if the app forgot the
  permission or the location service is off.
- **iOS** delivers everything through a delegate protocol, asynchronously, on whatever queue
  `CLLocationManager` chooses, and the manager must be kept alive with a strong reference for the
  duration of a request or CoreLocation silently drops the callback.
- **Both** platforms happily suspend forever indoors or with no signal unless the caller adds its
  own timeout.

`LocationProvider` collapses all of that into three calls that either return a value or `null` —
never an exception, never a hang past `LocationProviderConfig.singleFixTimeoutMillis`.

## What this is **not**

- **Not a permission library.** It declares no Android permission and requests no iOS
  authorization; see [`05-platform-notes.md`](05-platform-notes.md). Implementations assume the
  permission is already granted and degrade to `null` when it is not.
- **Not a cache.** `getCurrentLocation()` prefers a cached fix the platform already has purely as an
  optimization; this module stores nothing itself and has no opinion about how stale a fix your app
  should tolerate.
- **Not a geocoder.** No address lookup, no reverse geocoding, no place search — `GeoCoordinates` is
  a bare latitude/longitude pair.
- **Not Google Play Services.** The Android side is plain `android.location.LocationManager`, not
  `FusedLocationProviderClient` — a deliberate departure from the donor codebase this module was
  ported from; see [`05-platform-notes.md`](05-platform-notes.md#android-vs-play-services) for the
  trade-off.
- **Not Compose, and not tied to any UI framework.** Plain Kotlin, no Compose dependency.
- **Not a DI module.** One interface plus a factory function per platform; wire it however you like.
- **Not JVM or desktop.** Android and iOS targets only.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — dependency, constructing the provider, first
  fix.
- [`03-guide.md`](03-guide.md) — one-shot vs. continuous, the enabled check, common mistakes.
- [`04-api-reference.md`](04-api-reference.md) — every public declaration.
- [`05-platform-notes.md`](05-platform-notes.md) — **required reading**: permissions your app must
  declare, and why the Android side is `LocationManager` rather than Play Services.
- [`06-testing.md`](06-testing.md) — `FakeLocationProvider`, in `kmptoolkit-location-testing`.

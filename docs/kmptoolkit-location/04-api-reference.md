# kmptoolkit-location — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.location`, and the contract it holds to.

## Factories

The two platforms need different inputs, so there is no common factory — construct the provider in
platform code and pass the `LocationProvider` interface around.

### `createLocationProvider` (Android)

```kotlin
public fun createLocationProvider(
    context: Context,
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider
```

Creates the `LocationManager`-backed provider. Only `context.applicationContext` is retained, so
passing an `Activity` here is harmless.

### `createLocationProvider` (iOS)

```kotlin
public fun createLocationProvider(
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider
```

Creates the `CLLocationManager`-backed provider. A new `CLLocationManager` and delegate are created
per request internally; nothing here needs releasing.

## `LocationProvider`

```kotlin
public interface LocationProvider {
    public suspend fun getCurrentLocation(): GeoCoordinates?
    public fun observeLocation(): Flow<GeoCoordinates?>
    public suspend fun isLocationEnabled(): Boolean
    public fun openLocationSettings()
}
```

| Member | Contract |
|---|---|
| `getCurrentLocation` | Returns one fix, or `null`. Prefers a cached fix; falls back to a fresh single-shot request, capped by `LocationProviderConfig.singleFixTimeoutMillis`. Never throws for a missing permission, a disabled service, or no signal. |
| `observeLocation` | Hot `Flow`, `null` while no fix is available. Registers the underlying platform request on first collection and stops it when the flow is cancelled — see [`03-guide.md`](03-guide.md). Always seeds its first value (a cached fix, or `null`) so a collector is never left waiting. |
| `isLocationEnabled` | Whether the device-wide location service is on, independent of the app's permission. `suspend` because iOS's equivalent check warns off the main thread. |
| `openLocationSettings` | Sends the user to the system location settings screen (Android) or the app's own settings page (iOS — there is no deep link to the toggle). Fire-and-forget: no result, no callback. |

Implementations are safe to call from any thread. Nothing here holds a resource that must be closed
— there is no `close()` on this interface, because there is no persistent platform registration
outside of an active `observeLocation` collector.

## `GeoCoordinates`

```kotlin
public data class GeoCoordinates(
    val latitude: Double,
    val longitude: Double,
)
```

A WGS-84 latitude/longitude pair, in decimal degrees. No accuracy, altitude, bearing, speed, or
timestamp — reach for the platform location APIs directly if you need those.

## `LocationProviderConfig`

```kotlin
public data class LocationProviderConfig(
    val minUpdateDistanceMeters: Float = 0f,
    val minUpdateIntervalMillis: Long = 0L,
    val singleFixTimeoutMillis: Long = 30_000L,
)
```

| Parameter | Contract |
|---|---|
| `minUpdateDistanceMeters` | Smallest movement, in meters, that produces a new `observeLocation` emission. `0` means every update the platform reports. Maps directly to Android's `requestLocationUpdates` `minDistance` and iOS's `CLLocationManager.distanceFilter`. |
| `minUpdateIntervalMillis` | Shortest time, in milliseconds, between two `observeLocation` emissions. `0` means no throttling. Maps to Android's `requestLocationUpdates` `minTime`. **Ignored on iOS** — CoreLocation has no time-based throttle; see [`05-platform-notes.md`](05-platform-notes.md). |
| `singleFixTimeoutMillis` | How long `getCurrentLocation` waits for a fresh fix before giving up and returning `null`, when no cached fix is available. Must be positive. |

Throws `IllegalArgumentException` at construction if `minUpdateDistanceMeters` or
`minUpdateIntervalMillis` is negative, or `singleFixTimeoutMillis` is not positive.

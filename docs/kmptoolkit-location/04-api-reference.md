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
passing an `Activity` here is harmless. `openLocationSettings()` opens the screen with
`SystemScreenLauncher.SeparateTask` (a task of its own, `FLAG_ACTIVITY_NEW_TASK |
FLAG_ACTIVITY_NEW_DOCUMENT`) — this factory has no activity to launch from.

```kotlin
public fun createLocationProvider(
    context: Context,
    activityAccess: ActivityAccess,
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider
```

*Since 1.7.0.* The same provider, whose `openLocationSettings()` uses
`SystemScreenLauncher.callerTask(activityAccess)`: the screen is started from the resumed activity
with no task flags, on your task, and falls back to a separate task when no activity is resumed. The
provider does not release `activityAccess`.

```kotlin
public fun createLocationProvider(
    context: Context,
    config: LocationProviderConfig,
    logger: Logger,
    systemScreenLauncher: SystemScreenLauncher,
): LocationProvider
```

*Since 1.7.0.* The same provider, whose `openLocationSettings()` calls `systemScreenLauncher` exactly
once per call, on the caller's thread, with a `SystemScreenRequest` holding one candidate
(`Settings.ACTION_LOCATION_SOURCE_SETTINGS`, no launch flags) and the kind `LocationSettingsScreen`.
`false`, or a launcher that throws, is logged as "could not open" at `WARN`; `openLocationSettings()`
never throws. Every parameter is required, so no call written for the other two overloads can
resolve to this one.

### `LocationSettingsScreen` (Android)

```kotlin
public object LocationSettingsScreen : SystemScreenKind
```

*Since 1.7.0.* The `SystemScreenKind` of the location settings screen, for a launcher that treats it
differently from other screens. See [`03-guide.md`](03-guide.md#which-task-the-settings-screen-opens-in-android).

### `createLocationProvider` (iOS)

```kotlin
public fun createLocationProvider(
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider
```

Creates the `CLLocationManager`-backed provider. A new `CLLocationManager` and delegate are created
per request internally; nothing here needs releasing.

### `withSystemServicesPrompt` (iOS)

```kotlin
public fun LocationProvider.withSystemServicesPrompt(): LocationProvider
```

*Since 1.5.0.* A decorator whose `promptToEnableService()` answers `ALREADY_ON` when the service is
on, and otherwise starts a one-shot `CLLocationManager` request — which makes iOS raise its own "Turn
On Location Services" alert for an authorized app — and answers `PROMPTED`. Every other member is
forwarded unchanged. Requests no authorization. `PROMPTED` means the request was made; whether iOS
showed the alert is not observable.

## `LocationProvider`

```kotlin
public interface LocationProvider {
    public suspend fun getCurrentLocation(): GeoCoordinates?
    public fun observeLocation(): Flow<GeoCoordinates?>
    public suspend fun isLocationEnabled(): Boolean
    public fun openLocationSettings()
    public suspend fun promptToEnableService(): LocationServicePrompt   // default implementation
}
```

| Member | Contract |
|---|---|
| `getCurrentLocation` | Returns one fix, or `null`. Prefers a cached fix; falls back to a fresh single-shot request, capped by `LocationProviderConfig.singleFixTimeoutMillis`. Never throws for a missing permission, a disabled service, or no signal. |
| `observeLocation` | Hot `Flow`, `null` while no fix is available. Registers the underlying platform request on first collection and stops it when the flow is cancelled — see [`03-guide.md`](03-guide.md). Always seeds its first value (a cached fix, or `null`) so a collector is never left waiting. |
| `isLocationEnabled` | Whether the device-wide location service is on, independent of the app's permission. `suspend` because iOS's equivalent check warns off the main thread. |
| `openLocationSettings` | Sends the user to the system location settings screen (Android) or the app's own settings page (iOS — there is no deep link to the toggle). Fire-and-forget: no result, no callback, never throws; a screen that could not be opened is logged. On Android the task it lands in depends on the factory — see [Factories](#factories). |
| `promptToEnableService` | Asks for an in-place "turn location back on" prompt where the platform offers one. Has a **default implementation** — `ALREADY_ON` / `UNSUPPORTED` from `isLocationEnabled()` — so it needs no override on either factory-built provider. See [`03-guide.md`](03-guide.md#prompting-to-re-enable-the-service) and [`LocationServicePrompt`](#locationserviceprompt) below. |

Implementations are safe to call from any thread. Nothing here holds a resource that must be closed
— there is no `close()` on this interface, because there is no persistent platform registration
outside of an active `observeLocation` collector.

## `LocationServicePrompt`

```kotlin
public enum class LocationServicePrompt { ALREADY_ON, PROMPTED, UNSUPPORTED, NOT_NOW }
```

| Value | Meaning |
|---|---|
| `ALREADY_ON` | The service was already on — nothing was asked. |
| `PROMPTED` | The system's own in-place dialog was raised. Not returned by either factory-built provider — see [`03-guide.md`](03-guide.md#prompting-to-re-enable-the-service). |
| `UNSUPPORTED` | No in-place prompt is available; the only route is `openLocationSettings()`. What both factory-built providers return when the service is off. |
| `NOT_NOW` | There is a prompt, but no window to raise it over right now. Not returned by either factory-built provider. |

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

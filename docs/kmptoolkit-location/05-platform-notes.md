# kmptoolkit-location — Platform notes

What differs behind `LocationProvider`, which permissions your app must declare, and exactly how it
behaves when one is missing.

## Permissions

**This library declares no Android permission and requests no iOS authorization.** Requesting the
permission is your app's job — or `kmptoolkit-permission`'s, see below.

| | Android | iOS |
|---|---|---|
| Permission/authorization | `ACCESS_FINE_LOCATION` and/or `ACCESS_COARSE_LOCATION` | `NSLocationWhenInUseUsageDescription` (and/or `NSLocationAlwaysAndWhenInUseUsageDescription`) in `Info.plist` |
| Declared by | **you** | **you** |
| Without it | `getCurrentLocation()` returns `null`, `observeLocation()` emits `null` — no throw | same |

### Android — you must declare the permission yourself

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

**Behavior when it is missing:** `LocationManager.getLastKnownLocation` and
`requestLocationUpdates` both throw `SecurityException`. `AndroidLocationProvider` catches it at
every call site, logs a warning to the `Logger` you passed (nothing, by default), and returns
`null` / emits `null` instead of letting the exception escape.

### iOS — you must request authorization yourself

Add the usage-description string to `Info.plist` and call
`CLLocationManager().requestWhenInUseAuthorization()` (or the "always" variant) from your own code,
before you expect a fix. This module never calls either method.

**Behavior when authorization is denied or not yet determined:** `CLLocationManager` calls the
delegate's `didFailWithError` instead of `didUpdateLocations`. `IosLocationProvider` treats that the
same as any other CoreLocation failure: log the error and resolve to `null` / emit `null`.

### Relationship to `kmptoolkit-permission`

`kmptoolkit-permission`'s `Permission` enum deliberately does **not** include location — see
[`docs/kmptoolkit-permission/01-overview.md`](../kmptoolkit-permission/01-overview.md#why-the-catalog-is-closed):
iOS's location authorization has no honest mapping onto that module's four-case `PermissionStatus`
(it distinguishes "while in use" from "always", and answers arrive through a delegate callback that
can land long after the request). There is no code dependency between the two modules and none is
needed — if your app already uses `kmptoolkit-permission` for microphone or notifications, request
location permission through your own platform code exactly as you would for any permission that
module does not model, and hand the resulting `LocationProvider` to shared code once you have it.

## Android vs. Play Services

This module's Android implementation is plain `android.location.LocationManager`, **not**
`com.google.android.gms:play-services-location`'s `FusedLocationProviderClient`, and it will stay
that way. Understand the trade-off before you pick this module for a feature that leans on fast,
high-accuracy positioning.

| | `LocationManager` (this module) | `FusedLocationProviderClient` |
|---|---|---|
| Dependency | none beyond the Android SDK | `play-services-location`, roughly a megabyte, plus a Google Play Services runtime dependency |
| Works without Play Services / Google Play | yes | no |
| Fix quality | GPS or network provider, whichever `getBestProvider` picks | blends GPS, Wi-Fi and cell signal through Google's positioning service |
| Cached last fix | per provider, often empty after a reboot | a fused cache that is usually warm |
| Typical time-to-fix | slower for a cold GPS fix with no network assistance | usually faster, especially indoors or with poor GPS visibility |
| In-place "turn on location" dialog | none | `SettingsClient` + `ResolvableApiException` |
| Power tuning | manual (`minTime`/`minDistance` via `LocationProviderConfig`) | handled by the fused engine |

**Why the library does not use it.** A library module that depended on Play Services would force that
dependency, and a Google Play runtime requirement, on every consumer — including apps built for
devices without Google Play, where Fused simply returns nothing. This repository does not take on a
heavy optional transitive dependency the consumer did not choose; the same reasoning keeps
`kmptoolkit-storage` on `AndroidKeyStore` and `Cipher` directly instead of Tink.

**If your app already depends on Play Services, use Fused through a decorator.** `LocationProvider`
is small enough to implement over `FusedLocationProviderClient` in your own app, and doing so keeps
shared code — and every test written against `FakeLocationProvider` — unchanged. The next section is
the recipe.

## Writing a Play Services decorator (Android)

Implement `LocationProvider` in your app's `androidMain`, backed by Fused, and delegate to a
factory-built provider for what Fused adds nothing to:

```kotlin
class FusedLocationProvider(
    context: Context,
    private val activityAccess: ActivityAccess,       // from kmptoolkit-activity
    private val fallback: LocationProvider = createLocationProvider(context),
) : LocationProvider {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): GeoCoordinates? = try {
        client.lastLocation.await()?.toCoordinates() ?: freshFix()
    } catch (_: SecurityException) {
        null                           // no location permission
    } catch (_: ApiException) {
        null                           // Play Services missing, outdated or unavailable on this device
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(): GeoCoordinates? {
        val cancellation = CancellationTokenSource()
        return try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token).await()?.toCoordinates()
        } finally {
            cancellation.cancel()      // a no-op once the fix arrived; stops the request if we were cancelled
        }
    }

    @SuppressLint("MissingPermission")
    override fun observeLocation(): Flow<GeoCoordinates?> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                trySend(result.lastLocation?.toCoordinates())
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15.minutes.inWholeMilliseconds)
            .setMinUpdateIntervalMillis(5.minutes.inWholeMilliseconds)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            trySend(null)
            close()
            return@callbackFlow
        }
        // Seed at once, with null when there is no cached fix: while the service is off the callback
        // never fires, and a flow that emits nothing hangs every combine downstream of it.
        client.lastLocation
            .addOnSuccessListener { location: Location? -> trySend(location?.toCoordinates()) }
            .addOnFailureListener { trySend(null) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    override suspend fun isLocationEnabled(): Boolean = fallback.isLocationEnabled()

    override fun openLocationSettings() = fallback.openLocationSettings()

    override suspend fun promptToEnableService(): LocationServicePrompt {
        if (isLocationEnabled()) return LocationServicePrompt.ALREADY_ON
        // No resumed activity: nothing to raise the dialog over right now. Not UNSUPPORTED — the
        // device could ask properly once the user is back.
        val activity: Activity = activityAccess.withActivity { it } ?: return LocationServicePrompt.NOT_NOW
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L).build())
            .build()
        return try {
            LocationServices.getSettingsClient(activity).checkLocationSettings(request).await()
            LocationServicePrompt.ALREADY_ON
        } catch (resolvable: ResolvableApiException) {
            try {
                resolvable.startResolutionForResult(activity, ENABLE_LOCATION_REQUEST_CODE)
                LocationServicePrompt.PROMPTED
            } catch (_: IntentSender.SendIntentException) {
                LocationServicePrompt.UNSUPPORTED
            }
        } catch (_: ApiException) {
            LocationServicePrompt.UNSUPPORTED
        }
    }

    private fun Location.toCoordinates() = GeoCoordinates(latitude = latitude, longitude = longitude)

    private companion object {
        const val ENABLE_LOCATION_REQUEST_CODE = 4711
    }
}
```

(`await()` is `kotlinx-coroutines-play-services`; with plain listeners, as the observe flow does, the
same rules apply.)

The rules the recipe encodes, each of which is part of the `LocationProvider` contract and not a
matter of taste:

- **Nothing throws.** A missing permission surfaces from Fused as `SecurityException`, and a device
  without a usable Play Services as a failed task (`ApiException`, thrown by `await()`); turn both
  into `null`, exactly as this module's own providers do. `SettingsClient` failures that are not
  resolvable are `UNSUPPORTED`, not exceptions.
- **Cancellation stops the platform work.** `getCurrentLocation(priority, null)` keeps a request
  running after the caller is gone; pass a `CancellationTokenSource` and cancel it.
- **`observeLocation()` emits at once** — the cached fix or `null` — and removes its callback in
  `awaitClose`.
- **Callbacks on the main looper**, which is where this module's own Android provider delivers them
  too, so collectors behave the same whichever provider is injected.
- **`promptToEnableService()` never waits for the dialog's answer.** The answer arrives in the
  activity's `onActivityResult`, which a provider has no seam into. Return `PROMPTED` once it is up
  and re-check `isLocationEnabled()` when the screen resumes. `NOT_NOW` when there is no resumed
  activity (this is what `kmptoolkit-activity`'s `ActivityAccess` is for — never hold an `Activity`
  in the provider).
- **Delegate what Fused does not improve.** `isLocationEnabled()` and `openLocationSettings()` come
  from the factory-built provider, so the settings intent, the task it opens in and the service check
  stay identical — build the fallback with `createLocationProvider(context, activityAccess)` if you
  want the screen on your own task.

Bind it where the rest of your app is assembled, and keep the factory-built provider for iOS:

```kotlin
// androidMain
single<LocationProvider> { FusedLocationProvider(androidContext(), activityAccess = get()) }
// iosMain
single<LocationProvider> { createLocationProvider().withSystemServicesPrompt() }
```

## The in-place prompt on each platform

`promptToEnableService()` returns `LocationServicePrompt.PROMPTED` from neither factory-built
provider as it comes:

- **Android** raises its in-place "turn on Location?" dialog only through Play Services'
  `SettingsClient` — see the decorator above. Without it, the default `promptToEnableService()`
  answers `UNSUPPORTED` while the service is off, and the route left is `openLocationSettings()`.
- **iOS** has no public API to switch the service on or to open its settings page. What it has is its
  own "Turn On Location Services" alert, which it raises when an authorized app asks for a location
  while the service is off. `createLocationProvider().withSystemServicesPrompt()` makes exactly that
  ask from `promptToEnableService()` and answers `PROMPTED`. It is opt-in because the system, not the
  app, decides whether the alert appears: never for an app without location authorization (the
  decorator does not request it — that stays your call), and not again after the user dismissed it.
  `PROMPTED` means asked, not shown; re-check `isLocationEnabled()` when the app becomes active.

## The location settings screen and tasks (Android)

`openLocationSettings()` goes through a `SystemScreenLauncher`
([guide](03-guide.md#which-task-the-settings-screen-opens-in-android)), and the task the screen lands
in is what decides where the user ends up when they leave it.

- **Up to 1.6.0** the screen was started with `FLAG_ACTIVITY_NEW_TASK` alone. That joins any
  background task with the Settings affinity — a Settings page opened earlier from a quick-settings
  long-press or a notification — so Back could land on that stale page instead of in your app.
- **The `Context`-only factory** now uses `SystemScreenLauncher.SeparateTask`:
  `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, from the application context.
  `NEW_DOCUMENT` skips the affinity lookup, so the screen always gets a fresh task (unless a task is
  already rooted at that same screen). Two limits remain, both platform behaviour: on a two-pane
  Settings (large screens, AOSP 12L and later) a page started in a new task hands itself to the
  Settings homepage, whose task may be a stale one; and a Settings build that declares the screen
  `singleTask` or `singleInstance` strips `NEW_DOCUMENT`.
- **The `ActivityAccess` factory** uses `SystemScreenLauncher.callerTask`: no task flags, from your
  resumed activity, so Back returns to it and a two-pane Settings shows the page on its own. With no
  resumed activity it falls back to a separate task.
- **Lock-task (kiosk) apps** keep the separate task — a Settings screen on your task is inside the
  locked task, where the allowlist no longer confines it — and put the Settings package on the
  lock-task allowlist, or open an allowlist window around the launch with a launcher of their own.

Why each flag, the two-pane hand-off and lock-task mode in full:
[`kmptoolkit-activity` — System screens and tasks](../kmptoolkit-activity/05-platform-notes.md#system-screens-and-tasks).

## Provider selection and fallback (Android)

`getCurrentLocation()` and `observeLocation()` both track `LocationManager.GPS_PROVIDER` and
`LocationManager.NETWORK_PROVIDER`. `getBestProvider(Criteria.ACCURACY_FINE, enabledOnly = true)`
picks the single-fix provider — biased towards GPS so a fix can still be obtained with no internet,
at the cost of a slower cold fix than network-based positioning. `observeLocation()` requests
updates from **both** tracked providers at once and forwards whichever reports first, so losing one
(GPS indoors, say) does not silence the flow as long as the other is still enabled.

## Update throttling and the single-fix timeout

`LocationProviderConfig.minUpdateDistanceMeters` and `minUpdateIntervalMillis` map directly onto
`LocationManager.requestLocationUpdates`'s `minDistance` and `minTime` parameters on Android, and
onto `CLLocationManager.distanceFilter` on iOS — **`minUpdateIntervalMillis` has no iOS
equivalent and is ignored there**; CoreLocation has no time-based throttle, only a distance one.

`singleFixTimeoutMillis` bounds `getCurrentLocation()` on both platforms. Without it, a device with
no GPS/network signal indoors could suspend the call forever — the donor iOS implementation this
module was ported from had exactly that gap; this module closes it with `withTimeoutOrNull` on both
platforms.

## Thread safety

- `LocationProvider` methods are safe to call from any thread. On iOS that includes
  `openLocationSettings()`, which hops to the main thread for `UIApplication` when called from
  elsewhere. On Android, `openLocationSettings()` calls its `SystemScreenLauncher` on the caller's
  thread; both presets are safe from any thread, and a launcher of your own that touches UI switches
  threads itself.
- Android: the underlying `LocationListener` callbacks are delivered on `Looper.getMainLooper()`.
- iOS: every `CLLocationManager` used by this module is created and started on the **main queue**,
  regardless of which thread `getCurrentLocation()` / `observeLocation()` is called from. This
  matters because CoreLocation delivers delegate callbacks on the run loop of the thread that
  *created* the manager — a manager created on a Kotlin/Native worker thread (no run loop, which is
  what a bare `Dispatchers.Default` caller would produce) would never deliver a callback at all, and
  the call would suspend forever rather than time out or fail. `trySend`/`resume` calls back into
  the coroutine are safe from the main queue regardless of which thread is awaiting them.
- `isLocationEnabled()` on iOS runs on `Dispatchers.Default`, off the caller's thread, because
  `CLLocationManager.locationServicesEnabled()` logs a runtime warning when called on the main
  thread while authorization is still being determined.

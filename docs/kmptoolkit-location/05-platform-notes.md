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
`com.google.android.gms:play-services-location`'s `FusedLocationProviderClient` — a deliberate
departure from the donor codebase this module was ported from, and worth understanding before you
pick this module for a feature that leans on high-frequency, high-accuracy positioning.

| | `LocationManager` (this module) | `FusedLocationProviderClient` |
|---|---|---|
| Dependency | none beyond the Android SDK | `play-services-location`, roughly a megabyte, plus a Google Play Services runtime dependency |
| Works without Play Services / Google Play | yes | no |
| Fix quality | GPS or network provider, whichever `getBestProvider` picks | blends GPS, Wi-Fi and cell signal through Google's positioning service |
| Typical time-to-fix | slower for a cold GPS fix with no network assistance | usually faster, especially indoors or with poor GPS visibility |
| Power tuning | manual (`minTime`/`minDistance` via `LocationProviderConfig`) | handled by the fused engine |

The trade-off is real: Fused generally produces a fix faster and more reliably in weak-signal
conditions, at the cost of a dependency no other module in this repository takes on. This
repository's whole ethos is avoiding a heavy, optional transitive dependency a consumer did not
choose — the same reasoning that keeps `kmptoolkit-storage` on `AndroidKeyStore` and `Cipher`
directly instead of Tink. If your app already depends on Play Services and needs Fused's fix
quality, wrap `FusedLocationProviderClient` behind this module's `LocationProvider` interface
yourself; the interface is small enough that doing so is a few dozen lines.

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

- `LocationProvider` methods are safe to call from any thread.
- Android: the underlying `LocationListener` callbacks are delivered on `Looper.getMainLooper()`.
- iOS: `CLLocationManager` delegate callbacks are delivered on whatever queue CoreLocation chooses
  for the manager (the main run loop, by default, since no custom queue is configured); `emit`/
  `trySend` calls into the `Flow` are safe from that thread regardless.
- `isLocationEnabled()` on iOS runs on `Dispatchers.Default`, off the caller's thread, because
  `CLLocationManager.locationServicesEnabled()` logs a runtime warning when called on the main
  thread while authorization is still being determined.

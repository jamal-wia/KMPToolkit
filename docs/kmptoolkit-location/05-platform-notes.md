# kmptoolkit-location — Platform notes

What differs behind `LocationProvider` and `HeadingProvider`, which permissions your app declares, and
exactly how each platform behaves in the cases that matter: a missing permission, a switched-off service, a
cached fix, an approximate grant, a platform failure.

- [Permissions](#permissions)
- [Accuracy mapping](#accuracy-mapping)
- [Fix fields per platform](#fix-fields-per-platform)
- [Android](#android) — [why not Play Services](#android-vs-play-services), [sources](#android-sources),
  [one-shot](#android-one-shot-call), [stream](#android-stream), [service state](#android-service-state),
  [permission rules](#android-permission-and-approximate-grant), [failures](#android-failures-of-the-platform),
  [settings screen](#the-location-settings-screen-and-tasks-android)
- [iOS](#ios) — [options](#ios-options), [authorization](#ios-authorization-and-reasons),
  [one-shot](#ios-one-shot-call), [stream](#ios-stream), [background](#ios-background-and-auto-pause),
  [age](#ios-age-of-a-fix)
- [Heading](#heading-android) — [Android](#heading-android), [iOS](#heading-ios),
  [declination table](#magnetic-declination)
- [Datum and distance notes](#datum-and-distance-notes)
- [Desktop JVM](#desktop-jvm)
- [The in-place prompt](#the-in-place-prompt-on-each-platform)
- [Threads](#thread-safety)

## Permissions

**This library declares no Android permission and requests no iOS authorization.** Requesting it is your
app's job, or `kmptoolkit-permission`'s (below).

| | Android | iOS |
|---|---|---|
| Permission / authorization | `ACCESS_FINE_LOCATION` and/or `ACCESS_COARSE_LOCATION` | `NSLocationWhenInUseUsageDescription` (and/or `NSLocationAlwaysAndWhenInUseUsageDescription`) in `Info.plist` |
| Declared by | **you** | **you** |
| Without it | the call answers `Unavailable(PERMISSION_MISSING)` at once; the stream emits it and completes. Nothing throws | the same |

### Android: declare the permission yourself

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- Or, if a city-level fix is enough for your feature: -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

Either permission is enough to proceed. A fix delivered when only the coarse permission is held reads
`isApproximate == true`. The library's merged manifest contains neither permission
(`LibraryManifestTest`). It registers one broadcast receiver in code, while a collection or call runs, and
removes it afterwards; the manifest gets no entry for it.

### iOS: request authorization yourself

Add the usage-description string to `Info.plist` and request authorization before you expect a fix (for
example `CommonPermission.Location` of `kmptoolkit-permission`); this module never requests it. The keys:

| Key | When |
|---|---|
| `NSLocationWhenInUseUsageDescription` | always |
| `NSLocationAlwaysAndWhenInUseUsageDescription` | when you request "always" |
| `UIBackgroundModes` with `location` | when you pass `allowsBackgroundLocationUpdates = true` |
| `NSLocationTemporaryUsageDescriptionDictionary` | for temporary full accuracy, through `kmptoolkit-permission` |

### Relationship to `kmptoolkit-permission`

This module never asks for location permission; `kmptoolkit-permission` does, from shared code:
`CommonPermission.Location` for "while in use" and `CommonPermission.LocationBackground` for "always"; see
[`docs/kmptoolkit-permission/05-platform-notes.md`](../kmptoolkit-permission/05-platform-notes.md). An
approximate-only grant reads `PermissionStatus.Limited` there, and this module still delivers fixes with it, at
reduced accuracy (on Android 10 and lower from the network provider only): check `isUsable`, not `isGranted`,
before calling in. There is no code dependency between the two modules.

To widen an approximate grant, request `CommonPermission.Location` again from shared code: Android offers the
precise upgrade, and iOS asks for temporary full accuracy when
`IosPermissionOptions.temporaryFullAccuracyPurposeKey` is set (a key of
`NSLocationTemporaryUsageDescriptionDictionary`); iOS platform code can also call
`requestTemporaryFullAccuracy(purposeKey)`. A running stream flips `isApproximate` by itself.

On iOS, with `temporaryFullAccuracyPurposeKey` set, a batch `request(listOf(LocationBackground, Location))`
whose authorization prompt ends under an approximate grant shows the temporary full-accuracy prompt right
after it: `Location`, listed second, is `Limited` by then, and a `request(Location)` from `Limited` with the
key set shows that prompt.

## Accuracy mapping

| `LocationAccuracy` | Android | iOS |
|---|---|---|
| `HIGH_ACCURACY` | the framework fused provider with `QUALITY_HIGH_ACCURACY`; without it gps and network | `kCLLocationAccuracyBest` |
| `BALANCED` | fused with `QUALITY_BALANCED_POWER_ACCURACY`; without fused: a one-shot call races gps and network, a stream uses network, else gps | `kCLLocationAccuracyHundredMeters` |
| `LOW_POWER` | fused with `QUALITY_LOW_POWER`; without fused: network, else passive | `kCLLocationAccuracyKilometer` |

The quality is passed with the request on API 31 and later; below that the platform request has no quality.
An approximate grant caps every level: on Android the platform coarsens the fix and, on API 31 and later,
limits how often updates arrive (about one per ten minutes); on iOS a reduced-accuracy grant delivers
regions of about 1-20 km, about four updates per hour. On iOS `desiredAccuracyOverride` replaces the mapping
with an exact `kCLLocationAccuracy*` constant, on both calls.

The defaults changed from 4.x. The Android default accuracy is `BALANCED` (4.x asked for the most accurate
provider, `Criteria.ACCURACY_FINE`): pass `accuracy = LocationAccuracy.HIGH_ACCURACY` to keep GNSS-grade
fixes. iOS is unchanged.

## Fix fields per platform

| Field | Android | iOS |
|---|---|---|
| `horizontalAccuracyMeters` | `getAccuracy()` when `hasAccuracy()` and finite and non-negative; 68 % radius | `horizontalAccuracy`; a negative or non-finite value means an invalid fix, which is dropped |
| `ellipsoidalAltitudeMeters` | `getAltitude()` when `hasAltitude()` | `ellipsoidalAltitude`, iOS 15+; else `null` |
| `mslAltitudeMeters` | `getMslAltitudeMeters()` when `hasMslAltitude()` (API 34+); else `null`. The library never runs an on-device geoid model | `altitude` |
| `verticalAccuracyMeters` | `getVerticalAccuracyMeters()` when present (API 26+); a negative or non-finite value nulls this and both altitudes | `verticalAccuracy`; negative: this and both altitudes `null` |
| `speedMetersPerSecond` | `getSpeed()` when `hasSpeed()` | `speed`; negative: `null` |
| `speedAccuracyMetersPerSecond` | API 26+ when present | `speedAccuracy` (iOS 10+); negative: `null` |
| `courseDegrees` | `getBearing()` when `hasBearing()` and in `[0, 360)` | `course`; negative: `null` |
| `courseAccuracyDegrees` | API 26+ when present | `courseAccuracy` (iOS 13.4+); negative or older: `null` |
| `isSimulated` | `isMock` (API 31+) / `isFromMockProvider` (below); never `null` | `sourceInformation.isSimulatedBySoftware` (iOS 15+); `null` when the system supplies none |
| `isApproximate` | `true` when only `ACCESS_COARSE_LOCATION` is held at delivery, else `false` | `true` when `accuracyAuthorization` is reduced **or** the effective `desiredAccuracy` is `kCLLocationAccuracyReduced` (a `REDUCED` override) at delivery, else `false`; a running stream re-reads it on `locationManagerDidChangeAuthorization` |
| `platformDetails` | `AndroidFixDetails(provider, elapsedRealtimeNanos)` | `IosFixDetails(floor?.level, sourceInformation?.isProducedByAccessory)` |
| `timestampEpochMillis` | `getTime()` | `timestamp` as epoch milliseconds |

Both platform providers drop an invalid fix (NaN or out-of-range coordinates through `GeoCoordinates.orNull`,
and on iOS a negative `horizontalAccuracy`): it is never thrown and never emitted. An app-written provider
follows the same rule.

## Android

### Android vs Play Services

This module's Android implementation is plain `android.location.LocationManager`, **not**
`com.google.android.gms:play-services-location`'s `FusedLocationProviderClient`, and it will stay that way.
On API 31 and later the **framework's own** `FUSED_PROVIDER` is used when the device has it; that is part of
the platform, not Play Services.

| | `LocationManager` (this module) | `FusedLocationProviderClient` |
|---|---|---|
| Dependency | none beyond the Android SDK | `play-services-location`, roughly a megabyte, plus a Google Play Services runtime dependency |
| Works without Play Services / Google Play | yes | no |
| Fix quality | the framework fused provider on API 31+, else gps and network raced | blends GPS, Wi-Fi and cell signal through Google's positioning service |
| Cached last fix | per provider, often empty after a reboot | a fused cache that is usually warm |
| Typical time-to-fix | slower for a cold GPS fix with no network assistance | usually faster, especially indoors |
| In-place "turn on location" dialog | none | `SettingsClient` + `ResolvableApiException` |
| Power tuning | `accuracy`, `minUpdateInterval`, `minUpdateDistanceMeters`, `maxUpdateDelay` | handled by the fused engine |

**Why the library does not use it.** A library module that depended on Play Services would force that
dependency, and a Google Play runtime requirement, on every consumer, including apps built for devices without
Google Play, where Fused returns nothing. This repository does not take on a heavy optional transitive
dependency the consumer did not choose; the same reasoning keeps `kmptoolkit-storage` on `AndroidKeyStore`
and `Cipher` directly instead of Tink. **If your app already depends on Play Services, use Fused through a
decorator**: [`03-guide.md`](03-guide.md#8-decorator-over-play-services-android) has the recipe.

### Android sources

`AndroidLocationSource` (`source` of the Android options, one-shot and stream):

| Source | Uses |
|---|---|
| `AUTO` (default) | the framework `FUSED_PROVIDER` when the device has it (API 31+) **and it is on**; else by accuracy: `HIGH_ACCURACY` gps and network; `BALANCED` gps and network for a one-shot call, network else gps for a stream; `LOW_POWER` network else passive. A "network else gps" row uses the first source that is **on**, and the first present one only when none is on; a device that has only a fused provider uses it even when it is off |
| `FUSED` | `FUSED_PROVIDER` only (API 31+) |
| `GPS` | `GPS_PROVIDER` only: raw GNSS, offline, no network positioning |
| `NETWORK` | `NETWORK_PROVIDER` only: Wi-Fi and cell positioning, usually needs a data connection |
| `PASSIVE` | `PASSIVE_PROVIDER` only: fixes other apps already requested, no added power |

A source that is **absent** on the device answers `Unavailable(UNSUPPORTED)`; one that exists but is **off**
answers `SERVICE_DISABLED` at once. `AUTO` races only the enabled sources and reads `SERVICE_DISABLED` only
when none of its candidates is enabled, so a switched-off network provider never hides an enabled gps. A
running `AUTO` stream re-selects on every service event and moves its registration to the enabled source.

`maxUpdateDelay` of the stream options lets the platform batch fixes up to that delay (API 31 and later; no
effect below). `minUpdateInterval` and `minUpdateDistanceMeters` are passed to the platform request **and**
applied in common code from the last emitted fix (the throttle of [`03-guide.md`](03-guide.md#the-options)).

### Android one-shot call

`getCurrentLocation` decides in this order:

1. **Permission.** Neither location permission held: `PERMISSION_MISSING` at once.
2. **Sources.** Resolve them as above. None present: `UNSUPPORTED`. Every source asked is off, or the
   device-wide switch is off: `SERVICE_DISABLED` at once, never after the timeout.
3. **Cache.** The freshest cached fix of the enabled sources, if no older than `maxAge`, answers. Age is
   `SystemClock.elapsedRealtimeNanos()` minus the fix's `elapsedRealtimeNanos` (a wall-clock correction
   changes nothing). `maxAge = Duration.ZERO` accepts no cache. A cached fix with no boot-clock stamp (only a
   hand-built `Location`) has an unknown age, which no finite `maxAge` accepts; a *delivered* fix with no
   stamp is taken as fresh.
4. **Race.** Every enabled source is asked for a fresh fix at once and the **first valid fix wins**, the rest
   are cancelled (their listeners removed), so a silent GPS indoors no longer burns the timeout before the
   network provider is asked. Per source, on API 30 and later the framework's `getCurrentLocation` answers
   first (with a quality on API 31+); it may hand back a cached fix up to 30 s old, so it is judged by the
   delivery rule: a fix that is no older than the call itself, or within `maxAge`, is fresh; an older one is
   discarded and that source continues with a one-update `requestLocationUpdates` registration, removed
   after the first fresh fix, until the timeout. Below API 30 only the one-update registrations run.
5. **Outcome.** Timeout: `NO_FIX`. Every source ended without a fix: `NO_FIX`, or `PERMISSION_MISSING` when
   every candidate refused the app. Sources switched off while the call waits: `SERVICE_DISABLED` at once.
   An unexpected platform failure (a cache read, an enabled-state read) is `NO_FIX`, logged through the
   `logger`. Nothing throws; cancelling the caller cancels every source.

### Android stream

`observeLocation` registers per collection and releases on every way out (cancel, completion, failure);
nothing is held between collections.

- **Seed first, register second.** The first reading is the freshest cached fix of the enabled sources within
  `maxSeedAge`, else the current reason (`SERVICE_DISABLED` while the service is off, else `NO_FIX`). It is sent
  before the listener is registered, so a live fix always arrives after it.
- **Lifecycle.** The stream completes after `PERMISSION_MISSING` and `UNSUPPORTED`, and stays open through
  `SERVICE_DISABLED` and `NO_FIX`.
- **Throttle.** A fix held back by the interval is delivered when it ends; a newer fix replaces it, a fix that
  is too close discards it, and a reading with a reason discards it and resets the reference, so the first fix
  after a reason passes at once. The seed, and every reseed after the service returned, start no
  `minUpdateInterval`: the first live fix that is far enough passes at once. The stream is conflated end to end: a busy collector skips
  intermediate readings and ends on the newest, so it can see two fixes closer than `minUpdateDistanceMeters`
  and can miss an intermediate `SERVICE_DISABLED` that a reseed already replaced; the terminal reading is
  never dropped. The throttle's clock is monotonic and pauses in deep sleep, so an interval can stretch
  across a sleep.
- **Reasons agree.** For the same device state the stream and the one-shot call give the same reason:
  `SERVICE_DISABLED` when an `AUTO` candidate is only switched off, `PERMISSION_MISSING` only when every
  candidate refused (or the source you named refused).

### Android service state

- `isLocationEnabled()` is **device-wide only**: `isLocationEnabled()` of `LocationManager` on API 28 and later,
  otherwise "gps or network is on". It can read `true` while a source you named is off, which the call and the
  stream report as `SERVICE_DISABLED`.
- `observeLocationEnabled()` is driven by the `MODE_CHANGED_ACTION` / `PROVIDERS_CHANGED_ACTION` broadcasts,
  distinct, and starts no location updates. The broadcast only signals; the collector reads the value, so the
  flow always ends on the current state even when a broadcast races its first read. A platform read failure
  is rethrown to its collector.
- A stream that sees the service go off emits `SERVICE_DISABLED` once and **stays open with its listener
  registered**; when the service returns it **reseeds** (the freshest cache within `maxSeedAge`, else
  `NO_FIX`). A transition is reported once even though a provider callback and a broadcast both announce it,
  and a live fix is never overtaken by an older reseed.
- **Current behaviour worth knowing.** When the platform delivers a live fix **after** `SERVICE_DISABLED` was
  already reported, the collector keeps that fix while the service stays off, until the next real change. When
  the service returns, the reseed can replace it with `NO_FIX` or with an older cached fix (the reseed is
  read at the moment the service comes back).

### Android permission and approximate grant

- A **revoke kills the process** (Android restarts the app), so a refusal only goes away with a new grant. A
  grant that arrives while a stream runs is picked up at the next service event: Android has no grant
  broadcast.
- **`ACCESS_COARSE_LOCATION` alone.** Android 10 and lower refuse the GPS provider to a coarse-only app
  (`SecurityException`); Android 11 and later serve it a coarsened fix from every provider. The provider skips a
  source that refuses and serves the others (at network accuracy), so both calls keep answering. A refusal is
  remembered per stream until the fine grant changes, then asked again.
- **Every candidate refuses while the service is off:** the stream stays open on `SERVICE_DISABLED` with
  nothing registered, never reading `NO_FIX` or a fix, and ends with `PERMISSION_MISSING` when the service
  returns (no platform call); a grant that arrives meanwhile registers the source. The one-shot call answers
  `SERVICE_DISABLED` first. In short: **a switched-off service beats a refusal**, as on iOS.
- A permission grant that lands at the moment a stream starts, or while it waits, is read again before the
  stream ends with `PERMISSION_MISSING`.
- A `SecurityException` from a provider is `PERMISSION_MISSING` only once the source is on.

### Android failures of the platform

Nothing throws into a platform callback or a cancellation handler.

- A failing permission read (the package manager dying) drops the fix being delivered and logs it, in the
  stream and the one-shot call; a reseed whose reading cannot be built is retried at the next service event
  and the stream stays open. At the **start** of a stream such a failure ends the collection with the failure,
  nothing registered; a non-security failure of the first candidate at start fails the stream, while the same
  failure during a move of `AUTO` is recovered.
- A failed re-read of the service state in the middle of an evaluation wedges nothing and shows nothing wrong:
  the next service event compares the true state with what the stream last showed. A position stays while the
  service is on, `SERVICE_DISABLED` is reported as soon as the service is off (also after a live fix that
  arrived in between), and a reseed is sent only when the stream shows `SERVICE_DISABLED` and the service is on
  again. No duplicate and no replaced position.
- A registration that fails after another succeeded leaves no listener behind; a failing removal is logged.
- A stream whose preferred source cannot be registered on the platform does not retry the failing
  registration forever.

### The location settings screen and tasks (Android)

`openLocationSettings()` goes through a `SystemScreenLauncher`
([guide](03-guide.md#which-task-the-settings-screen-opens-in-android)), and the task the screen lands in is
what decides where the user ends up when they leave it.

- **Up to 1.6.0** the screen was started with `FLAG_ACTIVITY_NEW_TASK` alone. That joins any background task
  with the Settings affinity (a Settings page opened earlier from a quick-settings long-press or a
  notification), so Back could land on that stale page instead of in your app.
- **`createLocationProvider(context, logger)`** uses `SystemScreenLauncher.SeparateTask`:
  `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, from the application context, from any thread.
  `NEW_DOCUMENT` skips the affinity lookup, so the screen never joins a stale Settings task. Its reuse rule
  matches the component and the data only (extras are ignored): opening the location screen again while its
  task still exists brings that task forward and shows the location screen on top (Back may then pass through
  pages the user opened there before), and each different Settings screen gets a Recents card of its own. Two
  limits remain, both platform behaviour: the location screen is a `SettingsActivity`, so on a two-pane
  Settings (large screens, AOSP 12L and later) a start in a new task hands the page to the Settings
  homepage, whose task may be a stale one; and a Settings build that declares the screen `singleTask` or
  `singleInstance` strips `NEW_DOCUMENT`.
- **`createLocationProviderWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))`** (the
  recipe for an ordinary app) starts the screen with no task flags from your resumed activity, so Back returns
  to it and a two-pane Settings shows the page on its own. That needs the call on the main thread with an
  activity resumed; otherwise it opens the screen in a separate task.
- **`true` from a launcher means the start was handed to the system**, not that the screen is visible: a start
  from the background has been blocked silently since Android 10 (API 29), and a lock-task violation is also
  answered `true` with nothing on screen.
- **Lock-task (kiosk) apps** keep the separate task (a Settings screen on your task is inside the locked task,
  where the allowlist no longer confines it) and open the lock-task allowlist window from a launcher of their
  own, closing it when the launch returns `false` or throws, and otherwise when the app resumes. While it is
  open the allowlist covers the whole Settings package. The recipe is in the
  [guide](03-guide.md#which-task-the-settings-screen-opens-in-android).

Why each flag, the two-pane hand-off and lock-task mode in full:
[`kmptoolkit-activity`: System screens and tasks](../kmptoolkit-activity/05-platform-notes.md#system-screens-and-tasks).

## iOS

### iOS options

| Option | Effect |
|---|---|
| `IosCurrentLocationOptions.desiredAccuracyOverride` | an exact CoreLocation accuracy for the one-shot call, replacing the mapping of `LocationAccuracy` (`requestLocation` honours `desiredAccuracy`) |
| `IosLocationUpdatesOptions.allowsBackgroundLocationUpdates` | `allowsBackgroundLocationUpdates` of the manager; needs `location` in `UIBackgroundModes`. Without it the stream fails with `IllegalStateException` naming the key. The check runs **when collection starts, before any authorization read**, so it fails in every authorization state |
| `showsBackgroundLocationIndicator` | the blue status-bar indicator while the app updates in the background (default `false`, Apple's default) |
| `pausesLocationUpdatesAutomatically` | default `false`, unlike Apple's `true`, which the 5.x stream ran with (a change from 5.x: see [the migration](07-migrating-to-6.md#step-8-defaults-that-changed-at-runtime)). Opt in and the stream emits `NO_FIX` while updates are paused, and the provider restarts updates on the next `UIApplicationDidBecomeActiveNotification`, so a pause that began in the background lasts until the app is next active |
| `activityType` | `CLActivityType`: steers Apple's pause heuristics and map matching |
| `desiredAccuracyOverride` | an exact accuracy for the stream |

### iOS authorization and reasons

The authorization status is read on the main queue, and the answer is immediate: never after the timeout.

| Authorization | Services on | Services off |
|---|---|---|
| `notDetermined`, `restricted` | `PERMISSION_MISSING` | `PERMISSION_MISSING` |
| `denied` | `PERMISSION_MISSING` | `SERVICE_DISABLED` (iOS reports `denied` while the services are off, so the switch is the reason worth naming) |
| authorized | proceeds | `SERVICE_DISABLED` |

The iOS **minimum is iOS 15**: that is what Kotlin/Native links for every iOS target by default, so the iOS 14
authorization APIs are always present; the iOS 15 and 13.4 fix properties are still read behind a version check.
`kCLErrorLocationUnknown` is transient and ignored; invalid fixes are dropped.

### iOS one-shot call

The call answers from `manager.location` when it is within `maxAge` (aged by the formula below with "now" as the
delivery), else it calls `requestLocation()`. A delivered fix older than `maxAge`, or an invalid one, is discarded
and the call **keeps waiting** until the timeout: the same manager continues with ordinary updates (one start,
never a loop of requests) because `requestLocation` answers once and stops. `kCLErrorLocationUnknown` also
keeps it waiting. A denial while the call runs makes it read the authorization again. The manager is stopped,
its delegate set to `nil` and released before the call returns, also when cancelled or timed out. Timeout is
`NO_FIX`.

### iOS stream

- **Seed first.** The first reading is `manager.location` (and the last fix the stream received itself)
  within `maxSeedAge`, else the reason; then updates start. Until the first fresh fix, deliveries older than
  `maxSeedAge` are cached leftovers and are dropped.
- **Lifecycle.** `PERMISSION_MISSING` (also on `locationManagerDidChangeAuthorization` while the stream
  runs) completes it and it cannot restart; `SERVICE_DISABLED` and `NO_FIX` keep it open. When the service
  returns the stream reseeds and resumes on the same manager (a service flap never creates a second manager).
- **One report per switch-off.** `SERVICE_DISABLED` is reported once per switch-off, even though iOS also
  reports `kCLErrorDenied`; a denial error after `SERVICE_DISABLED` does not repeat it.
- **A denial under a granted status.** When iOS answers with `kCLErrorDenied` while the authorization status
  is still granted and the switch is on, the stream stops the updates, emits `NO_FIX` once and **holds** (stays
  open) until the authorization changes or the app becomes active; then it restarts with a reseed. An
  activation after the denial counts, even before the hold is entered. A live fix inside the hold cannot
  occur, `kCLErrorLocationUnknown` is ignored there, and a platform that keeps denying never drives a restart
  loop.
- **Conflation.** The stream is conflated end to end, so a busy collector can see two fixes closer than
  `minUpdateDistanceMeters` / `minUpdateInterval` and can miss an intermediate `SERVICE_DISABLED`; throttling
  never hides a reason. The seed does not start `minUpdateInterval`.
- **Release.** A collection ends only after the main-queue release has run: updates stopped, delegate `nil`,
  manager dropped. A collector that ends on the seed (`first { ... }`) never leaves updates running on a
  released manager.
- `observeLocationEnabled()` is driven by `locationManagerDidChangeAuthorization`, re-reading
  `locationServicesEnabled()` off the main thread, distinct. `isLocationEnabled()` reads
  `locationServicesEnabled()` off the main thread too.
- `distanceFilter` is set from `minUpdateDistanceMeters` and the common check also applies; there is no
  platform interval, so `minUpdateInterval` is the common throttle only.

### iOS background and auto-pause

An iOS stream is a foreground feature: once the app is suspended CoreLocation stops delivering, and the flow
goes quiet without a signal. Background updates need `UIBackgroundModes` containing `location` **and**
`allowsBackgroundLocationUpdates = true`, which the stream options carry; the library checks the key before it
reads any authorization and fails with `IllegalStateException` naming it, never silently ignoring the option
(CoreLocation would otherwise raise an exception and crash the app). Holding "always" authorization
(`CommonPermission.LocationBackground`) does not change that on its own. `pausesLocationUpdatesAutomatically`
is `false` by default, so CoreLocation never pauses the stream by itself (the 5.x stream ran with Apple's
default, `true`). With it set to `true` the pause is reported as `NO_FIX` and a pause that began in the
background lasts until the app is next active.
Android background tracking is the app's own foreground service; Android also throttles fixes delivered to a
background app to a few per hour.

### iOS age of a fix

CoreLocation stamps fixes with a wall-clock `timestamp` only, and the first delivery after starting (or a
`requestLocation` answer) can be a cached fix. At delivery the provider captures
`ageAtDelivery = deliveryDate - location.timestamp` (a short wall-clock difference) and the system uptime.
The age at a later decision is `ageAtDelivery + (uptimeNow - uptimeAtDelivery)`, so a wall-clock correction
after the delivery changes nothing. A timestamp in the future counts as zero. A cached `manager.location` uses
the same formula with "now" as the delivery time. The one-shot call discards a delivered fix older than `maxAge`
(a fix no older than the call itself is always fresh) and keeps waiting; the stream drops deliveries older than
`maxSeedAge` until its first fresh fix.

## Heading (Android)

`createHeadingProvider(context, options, logger)` registers nothing at creation and holds nothing between
collections: every collection of `observeHeading` registers its own sensor listener (and, only when true north
needs a position, its own passive location listener) and unregisters them when it ends.

- **Sensor pipeline.** `AndroidHeadingOptions.sensor`: `AUTO` takes the rotation vector, else the geomagnetic
  rotation vector, else the accelerometer with the magnetometer; `isAvailable` is `false` when the device has
  none of the chosen ones, and an explicit choice that is absent is never substituted. Samples arrive at
  `SENSOR_DELAY_UI` (about 16 Hz), below the rates that need `HIGH_SAMPLING_RATE_SENSORS`.
- **True north.** `trueDegrees` is the magnetic heading plus the declination of
  `android.hardware.GeomagneticField`. With `declinationAt` the declination is computed there **without any
  permission and without a location listener**. Without it the newest position the app is permitted to read is
  used: the newest cached fix of any provider at the start of the collection, then every fix the passive
  provider delivers (fixes other apps or this library's `LocationProvider` already asked for, so the compass adds
  no power draw of its own); the passive listener asks for fixes with a one-minute and a five-kilometre
  minimum, so after the first one another arrives at most once a minute and only after the device moved five
  kilometres. A stationary device gets no further one, which is harmless for a declination. With neither a
  position nor `declinationAt`, `trueDegrees` is `null`. The library declares no permission: with neither
  location permission granted the compass works and `trueDegrees` stays `null` unless `declinationAt` is given.
- **Screen rotation** is an axis remap (`SensorManager.remapCoordinateSystem`), correct for a tilted device as
  well as a flat one, and fixed per collection.
- **Accuracy and calibration.** `accuracyDegrees` is the rotation vector's `values[4]` estimate (radians to
  degrees), `null` for every other pipeline or while the platform reports none or a value that is not a finite
  number (which never costs the heading). `needsCalibration` is `true` while the platform reports the heading
  sensor as `SENSOR_STATUS_UNRELIABLE` or `SENSOR_STATUS_ACCURACY_LOW`, read from `onAccuracyChanged` **and** from
  the `accuracy` of every sample (the framework calls `onAccuracyChanged` only on a change from its initial
  value `SENSOR_STATUS_UNRELIABLE`, so a compass that starts unreliable never reaches that callback); in the raw
  pipeline only the magnetometer counts. A negative accuracy (`SENSOR_STATUS_NO_CONTACT`) says nothing. The
  library does not compare the field strength with the expected one. The raw accelerometer pipeline is not
  smoothed.
- **Filter.** `minChangeDegrees` is applied in common code from the last **emitted** heading with
  `angleDifference`; the first reading, a calibration flip and `trueDegrees` going `null` or non-null are
  always emitted. The stream conflates, so a calibration flip that starts and ends inside one batch of
  callbacks before the collector runs can be swallowed: "always emitted" means "never filtered out".
- **Failures.** A sensor the platform refuses to enable leaves nothing registered and the stream open and
  silent; a registration that throws propagates to the collector with nothing left registered. Removing a
  listener during cleanup is logged at warning level with the failure attached, never thrown. A logger that
  throws an exception loses its own event and nothing else; an `Error` from the logger is not absorbed (a
  defect of the app) but every registration is released before anything is logged, and the collection then ends
  with that `Error`, the failure or cancellation that was ending it attached as suppressed.

## Heading (iOS)

`createHeadingProvider(showsSystemCalibration, logger)`: every collection owns its own `CLLocationManager`,
created on the main queue (`headingOrientation` and `headingFilter` are per manager), so concurrent collectors
are independent. When the collection ends, heading and location updates stop, the delegate is set to `nil` and
the manager is dropped; nothing is held between collections.

- **True north.** CoreLocation reports `trueHeading` only while location updates run on the same manager. So
  while the app is authorized for location the provider also runs `startUpdatingLocation` on that manager at
  `kCLLocationAccuracyThreeKilometers` (a declination changes by about one degree over hundreds of
  kilometres), starts them when authorization arrives mid-collection and stops them on revocation; it never
  starts them without authorization. Without authorization `trueDegrees` is `null`, also when CoreLocation
  still hands over a `trueHeading` it computed from a location cached before a revocation: the provider keeps a
  `trueHeading` only while authorization holds, so one that arrives while unauthorized is dropped and never
  shown when authorization comes back; true north is shown again with the next heading received while
  authorized. **`declinationAt` is ignored on iOS** (there is no declination API).
- **Whether location is running** is tracked as `true`, `false` or *unknown*. After a failed native start or
  stop call the state is unknown and is never guessed; the next authorization report issues the native call
  again, so it repairs the state (starting or stopping twice is harmless).
- **Rotation, filter, accuracy.** `screenRotation` maps to `headingOrientation` (`ROTATION_90` to
  `LandscapeLeft`, `ROTATION_180` to `PortraitUpsideDown`, `ROTATION_270` to `LandscapeRight`, `NATURAL` to
  `Portrait`). `minChangeDegrees` is the manager's `headingFilter` (0 asks for every update) and the common
  filter applies on top, measured from the last emitted heading. Because the manager's own filter holds back
  updates while the device is still, a change that needs no movement (true north appearing after
  authorization, a calibration request) is emitted when CoreLocation next delivers a heading. `accuracyDegrees`
  is `headingAccuracy` when not negative, else `null`; `needsCalibration` is `true` while `headingAccuracy` is
  negative or CoreLocation asked to show its calibration overlay and no valid heading has followed (best
  effort: a request followed at once by a valid heading clears it, and a slow collector can miss the pair).
- **Calibration overlay.** `showsSystemCalibration` lets iOS show its overlay only while a collection runs. Once
  the collection is over (also when the collector ended on the very reading that reported the request) iOS is
  told no, and an overlay that was agreed to is dismissed when the collection's manager is released, **after**
  both updates were stopped; a dismissal that throws still leaves the manager stopped, its delegate `nil` and
  the references dropped.
- `isAvailable` is `CLLocationManager.headingAvailable()`; when `false` the stream stays open and silent.
- **Release and re-entrancy.** After the collection ends (including inside a CoreLocation callback, on
  `Dispatchers.Main.immediate`) no location updates are started or stopped on the released manager; a grant that
  arrives while the collector ends on the heading it publishes starts nothing. A failing native call does not
  overwrite what authorization changes delivered inside it. An `Error` thrown while the manager is being
  started releases it first and then leaves; an exception is released, logged at `WARN`, and leaves the stream
  open and silent. A logger that throws an exception loses its own event and nothing else.
- Not observed on a device: the simulator has no compass.

### Magnetic declination

True north is the magnetic heading plus the local declination (east positive). The error of a magnetic needle
against a true bearing equals it. Rounded to the nearest degree, from the World Magnetic Model around 2025
(it drifts by a fraction of a degree a year; compute the exact value with `GeomagneticField`):

| City | Declination |
|---|---|
| London | +1° |
| Istanbul | +6° |
| Moscow | +12° |
| New York | -13° |
| Seattle | +15° |
| Tokyo | -8° |
| Sydney | +13° |

Declination varies slowly over about 100 km, so a coarse city fix is enough to correct a compass.

## Datum and distance notes

- **Datum.** `mslAltitudeMeters` and `ellipsoidalAltitudeMeters` are different datums:
  `msl` is about `ellipsoidal - N`, where `N` (the geoid height) runs from about -106 m to +85 m over the
  globe. Never subtract them without knowing which one a platform reported
  ([the table above](#fix-fields-per-platform)).
- **`distanceInMetersTo` versus `Location.distanceBetween`.** The library computes on a sphere (mean radius
  6 371 008.8 m, the atan2 form), within 0.6 % of the WGS-84 geodesic. Android's `distanceBetween` uses the
  ellipsoid and can differ by up to about 0.56 %, and by more near antipodes, where Android's iteration can
  stop early. Do not mix the two in one comparison.

## Desktop JVM

No desktop location source exists in this library, and there is no IP lookup: that needs a third-party
service, is a privacy question and is city-grade at best. The point of the `jvm` target is that shared code
(the geo math, every common type, a `LocationProvider` parameter) runs on the desktop unchanged and gets the
lifecycle right.

- `createLocationProvider()`: `getCurrentLocation` answers `Unavailable(UNSUPPORTED)`; `observeLocation` emits
  it and completes; `isLocationEnabled()` is `false`; `observeLocationEnabled()` emits `false` and stays open
  (a state stream never ends on its own, and the state never changes); `promptToEnableService()` answers
  `UNSUPPORTED`; `openLocationSettings()` does nothing.
- `createHeadingProvider()`: `isAvailable` is `false`; the stream stays open and silent until cancelled;
  `minChangeDegrees` is validated at the call.
- Both are stateless and take no logger or options, so there is nothing to release. To get a real desktop
  position, implement `LocationProvider` in your app (or a future engine artifact) for the engine you use.

## The in-place prompt on each platform

`promptToEnableService()` returns `PROMPTED` from neither factory-built provider as it comes:

- **Android** raises its in-place "turn on Location?" dialog only through Play Services' `SettingsClient`; see the
  decorator in [`03-guide.md`](03-guide.md#8-decorator-over-play-services-android). Without it the default
  answers `UNSUPPORTED` while the service is off, and the route left is `openLocationSettings()`.
- **iOS** has no public API to switch the service on or to open its settings page. What it has is its own "Turn
  On Location Services" alert, which it raises when an authorized app asks for a location while the service is
  off. `createLocationProvider().withSystemServicesPrompt()` makes exactly that ask from
  `promptToEnableService()` and answers `PROMPTED`. It is opt-in because the system, not the app, decides
  whether the alert appears: never for an app without location authorization (the decorator does not request
  it; that stays your call), and not again after the user dismissed it. `PROMPTED` means asked, not shown;
  re-check `isLocationEnabled()` when the app becomes active. At most one such request is kept at a time: for
  an app that is not yet authorized CoreLocation may never answer it, and a prompt made while one is still
  pending changes nothing instead of retaining one more manager per call.
- **Desktop** answers `UNSUPPORTED`.

## Thread safety

- `LocationProvider` methods are safe to call from any thread. On iOS that includes `openLocationSettings()`,
  which hops to the main thread for `UIApplication` when called from elsewhere. On Android,
  `openLocationSettings()` calls its `SystemScreenLauncher` on the caller's thread: both presets are safe from
  any thread, but only `SeparateTask` behaves the same on every thread (`callerTask` puts the screen on your task
  only when called on the main thread). A launcher of your own that touches UI switches threads itself.
- Android: the underlying `LocationListener` callbacks are delivered on `Looper.getMainLooper()`. Every
  decision about the service state of one collection runs under one per-collection state machine, one action
  at a time and in arrival order, and the platform calls made under it are plain reads and (de)registrations.
- iOS: every `CLLocationManager` used by this module (the authorization-status reader included) is created,
  started and released on the **main queue**, regardless of the calling thread: CoreLocation delivers delegate
  callbacks on the run loop of the thread that *created* the manager, so a manager created on a worker thread
  would never deliver a callback. `isLocationEnabled()` runs off the main thread because
  `locationServicesEnabled()` logs a runtime warning on it.

# kmptoolkit-location — Getting started

A first reading, in about five minutes.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-location:<version>")
        }
    }
}
```

The artifact publishes Android, iOS and `jvm`. It exposes `kmptoolkit-core` (an `api` dependency: the
platform slot interfaces carry the `@ToolkitInternalApi` marker) and, in the Android and iOS source sets
only, `kmptoolkit-logging` (the factories take a `Logger`). **Shared code that logs through `Logger`
declares `kmptoolkit-logging` itself:** the common source set no longer receives it from this module.

Test fixtures are a separate artifact, see [`06-testing.md`](06-testing.md).

## 2. Declare the platform permission

This library declares **no** Android permission and requests **no** iOS authorization: that stays your
app's decision.

**Android** (the app manifest, not this library's):

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- Or, if a city-level fix is enough for your feature: -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

**iOS** (`Info.plist`):

| Key | When |
|---|---|
| `NSLocationWhenInUseUsageDescription` | always: the text of the "while using the app" prompt |
| `NSLocationAlwaysAndWhenInUseUsageDescription` | only when you request "always" (`CommonPermission.LocationBackground`) |
| `UIBackgroundModes` containing `location` | only when you pass `IosLocationUpdatesOptions(allowsBackgroundLocationUpdates = true)`: without the entry that stream fails with `IllegalStateException` naming the key |
| `NSLocationTemporaryUsageDescriptionDictionary` | only for iOS temporary full accuracy: it holds the purpose strings, requested through `kmptoolkit-permission` (`IosPermissionOptions.temporaryFullAccuracyPurposeKey` or `requestTemporaryFullAccuracy(purposeKey)`) |

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Used to show prayer times and nearby content for your location.</string>
```

Request the runtime permission (Android) or trigger the authorization prompt (iOS) yourself before you
expect a fix: with `kmptoolkit-permission`, `CommonPermission.Location` asks for it from shared code. This
module has no code dependency on it ([`05-platform-notes.md`](05-platform-notes.md#relationship-to-kmptoolkit-permission)).
Without the permission both calls answer the typed `Unavailable(PERMISSION_MISSING)` at once and the
stream ends after it; neither throws.

## 3. Create the provider

The factories are platform functions, declared in the Android, iOS and JVM source sets; there is no common
`createLocationProvider`. Create the provider in platform code and hand the `LocationProvider` to shared
code (a constructor parameter, your DI graph, or an `expect fun` of your own).

**Android:**

```kotlin
val location: LocationProvider = createLocationProvider(context = applicationContext)
```

**iOS:**

```kotlin
val location: LocationProvider = createLocationProvider()
```

**Desktop JVM:**

```kotlin
val location: LocationProvider = createLocationProvider()
```

The Android and iOS factories take an optional `logger: Logger`; the JVM one takes nothing. On Android, if
your app already creates an `ActivityAccess` (from `kmptoolkit-activity`), use
`createLocationProviderWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))` so
`openLocationSettings()` opens on your own task and Back returns to your screen, see
[`03-guide.md`](03-guide.md#which-task-the-settings-screen-opens-in-android).

The iOS minimum is **iOS 15**: that is what Kotlin/Native links for every iOS target by default, so the
iOS 14 authorization APIs are always present. Fix properties added in iOS 15 and 13.4 are still read behind
a version check (see the table in [`05-platform-notes.md`](05-platform-notes.md#fix-fields-per-platform)).

## 4. Get a reading

`getCurrentLocation()` returns a `LocationReading`: a fix, or a typed reason. One `when` covers it.

<!-- docs-sample: first-call -->
```kotlin
suspend fun showWhereIAm(provider: LocationProvider, ui: MapUi) {
    when (val reading: LocationReading = provider.getCurrentLocation()) {
        is LocationReading.Available -> ui.show(reading.fix.coordinates, reading.fix.horizontalAccuracyMeters)
        is LocationReading.Unavailable -> when (reading.reason) {
            LocationUnavailability.PERMISSION_MISSING -> ui.askPermission()
            LocationUnavailability.SERVICE_DISABLED -> ui.offerSettings()
            else -> ui.showNoFix()
        }
    }
}
```

`MapUi` is your own presenter. The call never throws for a missing permission, a switched-off service, a
platform error or a timeout: each comes back as `Unavailable`. Its default options ask for `BALANCED`
accuracy, accept a cached fix up to 30 seconds old and wait up to 30 seconds for a fresh one. The `else`
branch matters: `LocationUnavailability` can grow in a minor version.

## 5. Or observe continuously

<!-- docs-sample: stream -->
```kotlin
fun CoroutineScope.followTheUser(provider: LocationProvider, ui: MapUi): Job = launch {
    val options = LocationUpdatesOptions(minUpdateDistanceMeters = 25.0, minUpdateInterval = 5.seconds)
    provider.observeLocation(options).collect { reading: LocationReading ->
        when (reading) {
            is LocationReading.Available -> ui.show(reading.fix.coordinates, reading.fix.horizontalAccuracyMeters)
            is LocationReading.Unavailable -> when (reading.reason) {
                LocationUnavailability.PERMISSION_MISSING -> ui.askPermission()
                LocationUnavailability.SERVICE_DISABLED -> ui.offerSettings()
                LocationUnavailability.UNSUPPORTED -> ui.showUnsupported()
                else -> ui.showNoFix()
            }
        }
    }
}
```

Collecting starts the platform request, and cancelling the collector releases it. The stream seeds at once
(a cached fix within `maxSeedAge`, else a reason), stays open through `SERVICE_DISABLED` and `NO_FIX`, and
completes after `PERMISSION_MISSING` or `UNSUPPORTED`, identically on every platform.

## 6. Coordinates you did not produce

`GeoCoordinates` validates its values and **throws** `IllegalArgumentException` for a non-finite or
out-of-range pair: the compiler cannot show it. Literals and values already known to be valid keep using the
constructor; coordinates from storage, a network response, a catalogue or user input go through `orNull`:

<!-- docs-sample: untrusted-coordinates -->
```kotlin
fun parseStoredPlace(latitude: Double, longitude: Double): GeoCoordinates? =
    GeoCoordinates.orNull(latitude, longitude)
```

## Next

- [`03-guide.md`](03-guide.md) — one-shot versus stream, options, recipes.
- [`05-platform-notes.md`](05-platform-notes.md) — what each platform actually guarantees.

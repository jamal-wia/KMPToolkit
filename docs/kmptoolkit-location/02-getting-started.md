# kmptoolkit-location — Getting started

A single location fix, in about five minutes.

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

Test fixtures are a separate artifact — see [`06-testing.md`](06-testing.md).

## 2. Declare the platform permission

This library declares **no** Android permission and requests **no** iOS authorization — that stays
your app's decision.

**Android** (app manifest, not this library's):

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- Or, if a city-level fix is enough for your feature: -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

**iOS** (`Info.plist`):

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Used to show prayer times and nearby content for your location.</string>
```

Request the runtime permission (Android) or trigger the authorization prompt (iOS) yourself, before
calling into this module — see [`05-platform-notes.md`](05-platform-notes.md) for how
`kmptoolkit-permission` relates to this module (it does not cover location; the two are used
side by side). Without the permission, [`getCurrentLocation`](04-api-reference.md) returns `null`
and [`observeLocation`](04-api-reference.md) emits `null`; neither throws.

## 3. Create the provider

**Android:**

```kotlin
val location: LocationProvider = createLocationProvider(context = applicationContext)
```

**iOS:**

```kotlin
val location: LocationProvider = createLocationProvider()
```

Both accept an optional `config: LocationProviderConfig` and `logger: Logger` — defaults are fine to
start with.

## 4. Get a fix

```kotlin
val coordinates: GeoCoordinates? = location.getCurrentLocation()
if (coordinates != null) {
    show(coordinates.latitude, coordinates.longitude)
} else {
    showNoFixState()
}
```

`getCurrentLocation()` never throws for a missing permission, a disabled location service, or no
signal — all three come back as `null`.

## 5. Or observe continuously

```kotlin
location.observeLocation().collect { coordinates ->
    coordinates?.let { updateMapCamera(it) }
}
```

Collecting starts the platform location request; cancelling the collector stops it. See
[`03-guide.md`](03-guide.md) for the difference between the two calls and when to reach for each.

## Next

- [`03-guide.md`](03-guide.md) — one-shot vs. continuous, the enabled check, common mistakes
- [`05-platform-notes.md`](05-platform-notes.md) — what each platform actually guarantees

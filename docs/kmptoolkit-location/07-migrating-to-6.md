# kmptoolkit-location — Migrating to 6.0.0

`kmptoolkit-location` was redesigned as a whole in 6.0.0: readings carry their accuracy or a typed reason, the
options are per call, coordinates validate themselves, and the fake is thread safe. This page is the path from
5.0.0 and earlier (the API of the 4.x line is the same); the changes are listed under `Breaking` in
`CHANGELOG.md`.

## What changed, at a glance

| Before | After |
|---|---|
| `getCurrentLocation(): GeoCoordinates?` | `getCurrentLocation(options): LocationReading` (a fix with accuracy, or a typed reason) |
| `observeLocation(): Flow<GeoCoordinates?>` | `observeLocation(options): Flow<LocationReading>` |
| `LocationProviderConfig` and the `config` parameter of every factory | removed; per-call `CurrentLocationOptions` / `LocationUpdatesOptions` |
| `LocationProvider` had three members | gains `observeLocationEnabled()`: custom implementations must implement it |
| `GeoCoordinates(any, any)` | the constructor **throws** `IllegalArgumentException` for a non-finite or out-of-range value; use `GeoCoordinates.orNull` for data you do not control |
| iOS stream kept running after errors | on every platform the stream completes after `PERMISSION_MISSING` or `UNSUPPORTED` |
| iOS turned `kCLErrorLocationUnknown` into a value and waited the full timeout without authorization | the transient error is ignored; a missing authorization answers `PERMISSION_MISSING` at once |
| Android returned a cached fix of any age | the default maximum age is 30 s (`Duration.INFINITE` restores "any") |
| Android default: the most accurate provider | the default accuracy is `BALANCED` |
| `kmptoolkit-logging` was an `api` dependency of `commonMain` | only of `androidMain` and `iosMain` |
| `FakeLocationProvider(current, ...)`, `emit(GeoCoordinates?)` | `FakeLocationProvider(locationEnabled, streamSeed)`, `emit(LocationReading)`, `currentLocationAnswer`; thread safe |

## Step 1: find the sites

```bash
rg -n 'GeoCoordinates\(' --type kotlin                      # data boundaries -> GeoCoordinates.orNull
rg -n 'LocationProviderConfig|createLocationProvider' --type kotlin
rg -n 'getCurrentLocation\(|observeLocation\(' --type kotlin
rg -n ':\s*LocationProvider\b' --type kotlin                 # custom implementations / decorators
rg -n 'FakeLocationProvider\(|\.emit\(' --type kotlin        # tests on the fake
rg -n 'import io\.github\.jamal_wia\.kmptoolkit\.logging' -g '**/commonMain/**'  # Logger via location's old api dependency
```

## Step 2: construction from stored or remote data

```kotlin
// Before (4.x): any pair was accepted
val coordinates = GeoCoordinates(stored.latitude, stored.longitude)
// After: an invalid pair is rejected at the boundary you already guard
val coordinates: GeoCoordinates = GeoCoordinates.orNull(stored.latitude, stored.longitude) ?: return null
```

**This is a runtime throw the compiler cannot show.** Literals and values that are already valid keep using
the constructor unchanged. `copy` validates too.

## Step 3: the one-shot call

```kotlin
// Before
val here: GeoCoordinates? = provider.getCurrentLocation()
```

<!-- docs-sample: migration-one-shot -->
```kotlin
suspend fun migratedOneShot(provider: LocationProvider, ui: MapUi) {
    when (val reading: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(timeout = 10.seconds))) {
        is LocationReading.Available -> ui.show(reading.fix.coordinates, reading.fix.horizontalAccuracyMeters)
        is LocationReading.Unavailable -> when (reading.reason) {
            LocationUnavailability.PERMISSION_MISSING -> ui.askPermission()
            LocationUnavailability.SERVICE_DISABLED -> provider.openLocationSettings()
            else -> ui.showNoFix()
        }
    }
}

suspend fun migratedCoordinatesOnly(provider: LocationProvider): GeoCoordinates? =
    provider.getCurrentLocation().fixOrNull()?.coordinates
```

The old `singleFixTimeoutMillis` of the config is now the `timeout` of the call (30 s by default). A missing
permission is no longer a `null` you cannot tell from "no signal": it is `PERMISSION_MISSING`.

## Step 4: the stream and the config

```kotlin
// Before
val provider = createLocationProvider(context, LocationProviderConfig(minUpdateDistanceMeters = 50f, minUpdateIntervalMillis = 10_000))
provider.observeLocation().collect { coordinates -> coordinates?.let(::update) }
// After: val provider: LocationProvider = createLocationProvider(context)
```

<!-- docs-sample: migration-stream -->
```kotlin
suspend fun migratedStream(provider: LocationProvider, update: (GeoCoordinates) -> Unit) {
    provider.observeLocation(LocationUpdatesOptions(minUpdateDistanceMeters = 50.0, minUpdateInterval = 10.seconds))
        .collect { reading: LocationReading -> reading.fixOrNull()?.let { fix: LocationFix -> update(fix.coordinates) } }
}
```

`minUpdateDistanceMeters` is a `Double` and `minUpdateInterval` a `Duration`, both honoured on every platform
(iOS used to ignore the interval). The stream now **completes** after `PERMISSION_MISSING` and
`UNSUPPORTED`, and stays open through `SERVICE_DISABLED` and `NO_FIX`: a `null` from 4.x could mean any of
them and never ended the stream on Android.

## Step 5: custom providers (decorators)

- Implement the new signatures and `observeLocationEnabled()`.
- Map platform values through `GeoCoordinates.orNull` and drop the invalid ones.
- Follow the stream lifecycle: seed at once, stay open through `SERVICE_DISABLED` and `NO_FIX`, complete
  after `PERMISSION_MISSING` and `UNSUPPORTED`.
- See the decorator recipe in [`03-guide.md`](03-guide.md#8-decorator-over-play-services-android).

## Step 6: tests on the fake

```kotlin
// Before
val fake = FakeLocationProvider(current = GeoCoordinates(21.4, 39.8))
fake.emit(GeoCoordinates(21.5, 39.9))
```

<!-- docs-sample: migration-fake -->
```kotlin
fun migratedFake(): FakeLocationProvider {
    val fake = FakeLocationProvider()
    fake.currentLocationAnswer = LocationReading.Available(fakeFix(21.4, 39.8))
    fake.emit(LocationReading.Available(fakeFix(21.5, 39.9)))
    return fake
}
```

`emit` no longer changes what `getCurrentLocation` answers. Set `currentLocationAnswer`, which defaults to
`Unavailable(NO_FIX)`. `emit(null)` of the old fake is `emit(LocationReading.Unavailable(NO_FIX))`. The fake
is thread safe and ends its stream like the real providers do ([`06-testing.md`](06-testing.md)).

## Step 7: `Logger` in shared code

If the last grep of step 1 finds imports in a module that does not declare `kmptoolkit-logging`, add
`implementation("io.github.jamal-wia:kmptoolkit-logging")` to that module's `commonMain`.

## Step 8: defaults that changed at runtime

- **Maximum age.** If your code relied on an old cache, pass `maxAge = Duration.INFINITE` (one-shot) or
  `maxSeedAge = Duration.INFINITE` (stream) explicitly.
- **Android accuracy.** The default accuracy is now `BALANCED` (4.x asked for the most accurate provider). Pass
  `accuracy = LocationAccuracy.HIGH_ACCURACY` to keep GNSS-grade fixes. iOS is unchanged.
- **iOS.** `kCLErrorLocationUnknown` no longer becomes a value, and a missing authorization answers at once
  instead of after the timeout. The documented minimum is iOS 15.

## Related changes in other modules

- **`kmptoolkit-permission` (iOS):** `IosPermissionOptions` gains a last parameter,
  `temporaryFullAccuracyPurposeKey` (default `null`). Source-compatible for Kotlin callers; binary-incompatible
  for precompiled callers of its constructor or `copy`, and Swift callers of the initializer must pass the new
  argument. See [`docs/kmptoolkit-permission`](../kmptoolkit-permission/03-guide.md#temporary-full-accuracy-ios) and the
  `CHANGELOG`.
- **`kmptoolkit-logging`:** a `jvm` target; nothing to migrate.

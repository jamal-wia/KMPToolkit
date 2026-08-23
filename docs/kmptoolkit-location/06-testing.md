# kmptoolkit-location — Testing

`LocationProvider` is not something a unit test can exercise directly — you cannot fake a GPS fix,
cannot dismiss a location permission dialog, and cannot flip the device's location toggle from a
test. `kmptoolkit-location-testing` ships one double: `FakeLocationProvider`.

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-location")
    testImplementation("io.github.jamal-wia:kmptoolkit-location-testing")
}
```

Works in `commonTest`, so one test covers both platforms.

| Fixture | Drives | Records |
|---|---|---|
| `FakeLocationProvider` | `emit(coordinates)`, `locationEnabled` | `openSettingsCount` |

## A fix arriving after the screen is already showing

The scenario a real provider cannot stage on demand: no fix yet, then one arrives.

```kotlin
@Test
fun `centers the map once a fix arrives`() = runTest {
    val location = FakeLocationProvider()
    val presenter = MapPresenter(location)
    assertEquals(MapState.NoFix, presenter.state)

    location.emit(GeoCoordinates(latitude = 21.4225, longitude = 39.8262))

    assertEquals(MapState.Centered, presenter.state)
}
```

`emit` updates both `getCurrentLocation()`'s next answer and every live `observeLocation()`
collector at once — they read from the same backing `StateFlow`.

## Starting from a known fix

```kotlin
@Test
fun `shows the distance to the nearest masjid on open`() = runTest {
    val here = GeoCoordinates(latitude = 21.4225, longitude = 39.8262)
    val presenter = NearbyMasjidsPresenter(FakeLocationProvider(current = here))

    presenter.onScreenOpened()

    assertEquals(expectedDistance, presenter.state.distanceMeters)
}
```

## The location service being off

```kotlin
@Test
fun `prompts to enable location when the service is off`() = runTest {
    val location = FakeLocationProvider(locationEnabled = false)
    val presenter = MapPresenter(location)

    presenter.onScreenOpened()

    assertEquals(MapState.ServiceDisabled, presenter.state)
}
```

## Proving a screen sent the user to settings

```kotlin
@Test
fun `tapping enable opens the system settings`() = runTest {
    val location = FakeLocationProvider(locationEnabled = false)
    val presenter = MapPresenter(location)

    presenter.onEnableLocationTapped()

    assertEquals(1, location.openSettingsCount)
}
```

`FakeLocationProvider` does not flip `locationEnabled` back on after `openLocationSettings()` is
called — a real provider has no way to know either, and `openSettingsCount` is the only thing it can
honestly report. Set `locationEnabled` yourself in the test to simulate the user turning it on and
returning to your app.

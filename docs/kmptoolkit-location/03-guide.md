# kmptoolkit-location — Guide

## One-shot vs. continuous

`getCurrentLocation()` and `observeLocation()` answer different questions and cost different
things.

| | `getCurrentLocation()` | `observeLocation()` |
|---|---|---|
| Shape | `suspend fun … : GeoCoordinates?` | `fun … : Flow<GeoCoordinates?>` |
| Returns | one fix, or `null` | every update, forever, until cancelled |
| Prefers a cached fix | yes | no — always live |
| Underlying request | stopped as soon as a fix arrives (or the timeout expires) | stopped when the flow is cancelled |
| Use for | "where is the user right now" — centering a map on open, tagging a single action | a live map camera, a distance-based feature that must react as the user moves |

Reach for `getCurrentLocation()` first. It is cheaper — a cached fix on Android answers instantly —
and it does not leave a location request running after you stop caring about the answer. Only use
`observeLocation()` when the feature is genuinely continuous.

```kotlin
class NearbyMasjidsPresenter(private val location: LocationProvider) {

    suspend fun onScreenOpened() {
        val fix: GeoCoordinates? = location.getCurrentLocation()
        state = if (fix != null) State.Loaded(fix) else State.NoFix
    }
}

class LiveMapPresenter(private val location: LocationProvider, scope: CoroutineScope) {

    val camera: StateFlow<GeoCoordinates?> = location.observeLocation()
        .stateIn(scope, SharingStarted.WhileSubscribed(), initialValue = null)
}
```

`observeLocation()` is a *hot* underlying request only while collected: nothing is registered with
the platform before the first collector, and `SharingStarted.WhileSubscribed()` above releases it
when the last one goes away — see [`05-platform-notes.md`](05-platform-notes.md) for exactly what
"registered" means on each platform.

## Checking whether location is even on

`getCurrentLocation()` returning `null` is ambiguous by itself: missing permission, service off, or
just no signal all look the same. When your UI needs to tell those apart — "turn on Location" is a
different message from "still searching" — check the service explicitly:

```kotlin
suspend fun diagnose(location: LocationProvider): NoFixReason = when {
    !location.isLocationEnabled() -> NoFixReason.ServiceDisabled
    location.getCurrentLocation() == null -> NoFixReason.NoSignal
    else -> NoFixReason.None
}
```

`isLocationEnabled()` reports the device-wide toggle, independent of your app's permission — it can
be `true` while your app's own permission is denied, and `false` while your app holds the
permission just fine.

## Sending the user to settings

```kotlin
if (!location.isLocationEnabled()) {
    showEnableLocationPrompt(onConfirm = { location.openLocationSettings() })
}
```

Show your own explanation first. `openLocationSettings()` leaves your app immediately and reports
nothing about what the user did there — there is no callback, no result, and no way to know whether
they turned the service back on. Re-check `isLocationEnabled()` when your screen resumes.

## Never block on a fix that will not arrive soon

Both calls are capped by `LocationProviderConfig.singleFixTimeoutMillis` — `getCurrentLocation()`
directly, and the underlying single-shot request each platform issues when there is no cached fix.
Thirty seconds is the default; raise it for a feature that can tolerate the wait (a background sync)
and lower it for one that cannot (an interactive screen with its own spinner). There is no
equivalent cap on `observeLocation()` — a `Flow` that "gives up" would be indistinguishable from
"no updates right now", which is exactly the ambiguity `isLocationEnabled()` exists to resolve.

## Common mistakes

- **Recreating the provider per screen.** `createLocationProvider(...)` is cheap to call but the
  object it returns is meant to be built once and shared — create it where you assemble your object
  graph (an `Application`-scoped or app-lifetime component).
- **Treating `null` as an error.** It is the documented "no answer" value for a missing permission,
  a disabled service, or no signal. Nothing throws.
- **Assuming `observeLocation()` is silent until a real fix arrives.** Both platform implementations
  seed the flow immediately — with the freshest cached fix, or `null` — precisely so a `combine()`
  downstream never hangs waiting for its first value.
- **Polling `isLocationEnabled()` in a loop.** There is no observe API for it, deliberately — see
  [`05-platform-notes.md`](05-platform-notes.md). Read it when you are about to act on the answer:
  screen resume, before starting a feature, after `openLocationSettings()` returns control to your
  app.

## Next

- [`04-api-reference.md`](04-api-reference.md) — every public declaration
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, and the LocationManager vs. Play
  Services trade-off

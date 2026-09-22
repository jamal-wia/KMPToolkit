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

### Which task the settings screen opens in (Android)

*Since 1.7.0.* On Android, `openLocationSettings()` hands the screen to a `SystemScreenLauncher` from
`kmptoolkit-activity`, and which one depends on the factory you called:

| Factory | Launcher | Where the settings screen goes |
|---|---|---|
| `createLocationProvider(context, config, logger)` | `SystemScreenLauncher.SeparateTask` (the default, unchanged signature) | a task of its own; Back leaves Settings |
| `createLocationProviderWithLauncher(context, systemScreenLauncher, config, logger)` | the one you pass | whatever that launcher decides |

**For an ordinary app, this is the recipe** — pass `callerTask` on the `ActivityAccess` you create in
`Application.onCreate`:

```kotlin
val location: LocationProvider = createLocationProviderWithLauncher(
    context,
    SystemScreenLauncher.callerTask(activityAccess),
)
```

The screen is pushed on top of your resumed activity: Back returns to your screen, and a two-pane
Settings on a tablet or foldable shows the page on its own instead of handing it to its homepage.
`callerTask` does that only when `openLocationSettings()` is called **on the main thread** with an
activity resumed; called off the main thread, or with no activity resumed, it opens the screen in a
separate task, exactly like `SeparateTask`. `SeparateTask` works from any thread. The provider never
releases `activityAccess`.

The Context-only `createLocationProvider` keeps `SeparateTask`, and it stays a single function, so an
untyped reference such as `singleOf(::createLocationProvider)` still resolves.

**Logging each request** without changing behaviour — wrap the default this module would use,
`SeparateTask` (or `callerTask(activityAccess)` if that is what you pass):

```kotlin
val location: LocationProvider = createLocationProviderWithLauncher(
    context,
    SystemScreenLauncher { request ->
        logger.d { "Opening ${request.kind}" }
        SystemScreenLauncher.SeparateTask.launch(request)
    },
)
```

**A lock-task (kiosk) app** keeps a separate task — a screen on your task is inside the locked task —
and opens its lock-task allowlist window only for the launch. While the window is open the allowlist
covers the whole Settings package, not just this screen. Close it again when the launch answers
`false` or throws, and otherwise when your app resumes (`startActivity` returns before the screen is
shown, so there is no earlier point to close it at):

```kotlin
val location: LocationProvider = createLocationProviderWithLauncher(
    context,
    SystemScreenLauncher { request ->
        kioskWindow.open()                      // your code: adds the Settings package to the allowlist
        val opened: Boolean = try {
            SystemScreenLauncher.SeparateTask.launch(request)
        } catch (failure: Exception) {
            kioskWindow.close()
            throw failure
        }
        if (!opened) kioskWindow.close()
        opened                                  // on true, close the window in your activity's onResume
    },
)
```

Your launcher is called once per `openLocationSettings()` call, on the caller's thread, with one or
more candidates, most specific first — currently one, `Settings.ACTION_LOCATION_SOURCE_SETTINGS`,
without launch flags — and the kind `LocationSettingsScreen`. If it answers `false` or throws, the
provider logs "Could not open the location settings screen" at `WARN` through its `logger` (with the
exception as the cause, when there is one) — the same as a device without the screen — and
`openLocationSettings()` returns normally. `true` means the start was handed to the system, not that
the screen is visible: since Android 10 (API 29) a start from the background is blocked silently, and
a lock-task violation is also answered `true` with nothing on screen. The full comparison of the
presets and the per-factory defaults of every module are in
[`kmptoolkit-activity`'s guide](../kmptoolkit-activity/03-guide.md#opening-system-screens).

## Prompting to re-enable the service

`promptToEnableService()` exists for the platform that *can* raise an in-place "turn location back
on" dialog without sending the user out to Settings — write your code against all four
`LocationServicePrompt` cases and it already does the right thing today, and keeps doing the right
thing if a future revision of this module (or your own `LocationProvider`, wrapping this one) adds a
real resolution dialog behind `PROMPTED` / `NOT_NOW`:

```kotlin
when (location.promptToEnableService()) {
    LocationServicePrompt.ALREADY_ON -> proceedWithLocation()
    LocationServicePrompt.PROMPTED -> Unit // the OS is showing its own dialog; re-check on resume
    LocationServicePrompt.NOT_NOW -> Unit // your app was backgrounded before it could ask; try again
    LocationServicePrompt.UNSUPPORTED -> showEnableLocationPrompt(onConfirm = { location.openLocationSettings() })
}
```

Both factory-built providers, as they come, only ever return `ALREADY_ON` or `UNSUPPORTED`. Two ways
to get a real prompt:

- **iOS:** `createLocationProvider().withSystemServicesPrompt()` asks the system to show its own
  "Turn On Location Services" alert and answers `PROMPTED`. See
  [`05-platform-notes.md`](05-platform-notes.md#the-in-place-prompt-on-each-platform) for when iOS
  actually shows it.
- **Android:** the in-place dialog exists only in Google Play Services' `SettingsClient`, which this
  module deliberately does not depend on. If your app already has Play Services, write a Fused
  decorator — [`05-platform-notes.md`](05-platform-notes.md#writing-a-play-services-decorator-android)
  has the full recipe, including the `NOT_NOW` case.

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

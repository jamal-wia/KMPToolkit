# kmptoolkit-permission — Guide

From the simplest case to the ones that actually bite: the state machine, the lifecycle, and the
mistakes.

## The state machine

`PermissionRequestFlow` has six states. Three are questions asked of *you*, three are resting points.

```
                        ┌───────────────────────────────┐
                        │            Idle               │◄────── refresh(), reset()
                        └───────────────┬───────────────┘
                                 start()│
              ┌─────────────────────────┼──────────────────────────┐
              │                         │                          │
     status Granted            status Denied(rationale)   status PermanentlyDenied
              │                         │                          │
              ▼                         ▼                          ▼
        ┌──────────┐          ┌──────────────────┐        ┌──────────────────┐
        │ Granted  │          │ AwaitingRationale│        │ AwaitingSettings │
        └──────────┘          └────────┬─────────┘        └────────┬─────────┘
                        acknowledged() │  dismissed()   openSettings()│  declined()
                                       ▼        └──────┐             ▼        └──────┐
                                ┌────────────┐         │      (stays here,           │
                                │ Requesting │         │       call refresh()        │
                                └─────┬──────┘         │       on resume)            │
                       granted / permanently denied /  │                             │
                              denied or unanswered     ▼                             ▼
                                       │           ┌────────┐                   ┌────────┐
                                       └──────────►│ Denied │◄──────────────────┤ Denied │
                                                   └────────┘                   └────────┘
```

`start()` with a status of `Denied(shouldShowRationale = false)` or `NotDetermined` goes straight to
`Requesting` — there is nothing to explain yet.

The complete transition table is in the KDoc on `PermissionRequestFlow` and in
[`04-api-reference.md`](04-api-reference.md); every row of it is pinned by a test.

## The simplest useful screen

```kotlin
class CameraPresenter(handler: PermissionHandler) {

    private val flow = PermissionRequestFlow(Permission.CAMERA, handler)

    val state: StateFlow<PermissionFlowState> = flow.state

    suspend fun onOpenCameraTapped() {
        if (flow.start() == PermissionFlowState.Granted) openCamera()
    }
}
```

Your UI observes `state`. When it becomes `AwaitingRationale` you show your explanation; when it
becomes `AwaitingSettings` you offer the settings trip. Both are your components and your copy.

## Handling each state

| State | What your UI does | What it calls back |
|---|---|---|
| `Idle` | nothing — nothing has been asked | `start()` on the user's action |
| `Requesting` | nothing; the OS owns the screen | — |
| `AwaitingRationale` | your explanation, with a continue and a cancel | `rationaleAcknowledged()` / `rationaleDismissed()` |
| `AwaitingSettings` | your prompt offering system settings | `openSettings()` / `settingsDeclined()` |
| `Granted` | proceed | — |
| `Denied` | degrade gracefully; leave a way to try again | `start()` again later |

Every method that does not apply to the current state is a no-op returning the state unchanged.
A double-tapped button therefore requests once, not twice, and nothing throws.

## The lifecycle: `refresh()` is not optional

A permission granted when your screen opened may be gone when it resumes. The user can revoke it in
settings, Android can auto-reset it for an app they have not opened in months, and an MDM profile
can withdraw it. None of that notifies you.

```kotlin
// Android: a lifecycle observer, a LaunchedEffect keyed on the lifecycle, or your own onResume.
// iOS: viewWillAppear, or a UIApplication.didBecomeActiveNotification observer.
suspend fun onScreenResumed() {
    when (flow.refresh()) {
        PermissionFlowState.Granted -> enableTheFeature()
        else -> disableTheFeature()
    }
}
```

`refresh()` never shows a dialog, so it is safe to call on every resume. It is also the only way to
learn what happened during a settings trip — the OS does not tell you, and `openSettings()` returns
`true` merely because the settings screen opened.

One thing it deliberately does: it **clears** a `Denied` reached earlier in this flow. `Denied`
records a choice the user made in *your* UI; the OS has no memory of it, and after a resume the
honest answer is whatever the OS now says. A permission that can simply be asked for again lands
back on `Idle`.

## Denied once versus permanently denied

This is the distinction that makes the module worth having.

- **`AwaitingRationale`** — the OS will still show its dialog. Explain, then ask again. Android
  reaches this after the first refusal.
- **`AwaitingSettings`** — the OS will show nothing. Asking again is a silent no-op, and only a trip
  to settings can change it. Android reaches this after the second refusal (on Android 11+ the
  second "Don't allow"; before it, "Don't ask again"); **iOS reaches it after the first**, because iOS
  shows its dialog at most once per install.

A dialog the user **dismissed** — back, or a tap outside it — is neither. On Android 11+ it changes
nothing, so the permission still reads `NotDetermined` and the next request shows the dialog again.

An app that treats these the same either nags a user the OS is willing to prompt, or leaves a user
tapping a button that can never work.

On Android the distinction rests on a flag this module persists, because Android's own API cannot
report it — see [`05-platform-notes.md`](05-platform-notes.md#the-asked-flag).

## Explain *before* asking, on iOS

Because iOS treats a refusal as final, its `AwaitingRationale` never happens: there is no second
dialog for a rationale to precede. If your permission is one users refuse when surprised by it, show
your explanation *before* calling `start()`, while the status is still `NotDetermined`:

```kotlin
suspend fun onFeatureOpened() {
    if (handler.check(Permission.MICROPHONE) == PermissionStatus.NotDetermined) {
        showYourPreDialogExplanation()   // then call flow.start() from its continue button
    } else {
        flow.start()
    }
}
```

That is a product decision, not a platform one, which is why the flow does not do it for you: a
pre-dialog explanation costs a screen the user may not need.

## Using the handler directly

Not every case needs the flow. A settings screen that only shows the current state wants
`check()` and nothing else:

```kotlin
val enabled: Boolean = handler.check(Permission.NOTIFICATIONS).isGranted
```

`isGranted` and `canPrompt` cover the two questions a toggle actually asks: is it on, and would
tapping it prompt or need a settings trip.

## Special access permissions

`SpecialPermissionHandler` is not a variant of `PermissionHandler` — it is a different contract for
a different category of Android grant, and understanding why clarifies when to reach for each.

A runtime [`Permission`] has a system dialog: the OS asks, the user taps, your process gets the
answer back synchronously (or, on iOS, through a short async round trip). A [`SpecialPermission`]
has no dialog at all — `SCHEDULE_EXACT_ALARM`, `SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`, and
the rest are granted only by the user finding a specific toggle in system Settings and flipping it
there, on their own schedule, with your app usually not even running. There is nothing to await and
nothing to distinguish a first refusal from a permanent one — the permission is simply on or off
right now, which is exactly what `isGranted()` answers and all it promises.

```kotlin
val specialHandler: SpecialPermissionHandler = createSpecialPermissionHandler(context)

fun onExactRemindersToggled(wantExact: Boolean) {
    if (wantExact && !specialHandler.isGranted(SpecialPermission.EXACT_ALARM)) {
        specialHandler.requestViaSettings(SpecialPermission.EXACT_ALARM)
    }
}

// Re-check whenever your screen could plausibly have missed the change — typically resume:
suspend fun onScreenResumed() {
    exactRemindersEnabled = specialHandler.isGranted(SpecialPermission.EXACT_ALARM)
}
```

### There is no `refresh()` here, and that is not an oversight

`PermissionRequestFlow` exists because a runtime permission's state machine has enough shape to be
worth modelling — rationale, request, settings, each a real decision point. A special permission has
exactly one useful question ("is it on?") and one useful action ("open the place to turn it on"), so
wrapping it in a flow would add a type without adding a decision. Call `isGranted()` from wherever
your screen already re-reads state on resume.

### Every entry is a no-op on iOS

`isGranted()` always returns `true` and `requestViaSettings()` always returns `false` on iOS — there
is no `SpecialPermission` case that maps to anything there. Code that checks before requesting
(`if (!isGranted(...)) requestViaSettings(...)`) is therefore automatically a no-op on iOS without
an `expect`/`actual` branch anywhere in your own code.

## Mistakes worth avoiding

- **Requesting without declaring.** A permission missing from your `AndroidManifest.xml` produces no
  dialog and an immediate denial with no rationale — which reads exactly like a dismissed dialog, so
  the status stays `NotDetermined` and every request returns at once without showing anything. On iOS a missing `Info.plist` usage string terminates the
  app. Check [`05-platform-notes.md`](05-platform-notes.md) first.
- **Building the handler before the launcher is registered.** `registerForActivityResult` must be
  called while the activity is below `RESUMED`. Register it as a field initializer, as in
  [`02-getting-started.md`](02-getting-started.md); the handler itself can be built whenever.
- **Driving one flow from two coroutines.** It holds mutable state and does not synchronize.
  Neither platform will show two permission dialogs at once anyway.
- **Treating `openSettings() == true` as consent.** It means the settings screen opened. Call
  `refresh()` when you come back.
- **Asking on app start.** Both platforms give you one good moment to ask. Spending it on a splash
  screen, before the user has seen why the permission matters, is how a permission becomes
  permanently denied on the first run.
- **Reading `state.value` instead of the return value.** They are the same thing; the return value
  just saves you a read. But do not read `state.value` *while* a `start()` is in flight expecting
  the outcome — it will be `Requesting`.
- **Reaching for `PermissionRequestFlow` around a `SpecialPermission`.** There is no rationale state
  and no permanent-denial bookkeeping to model — call `isGranted()` / `requestViaSettings()`
  directly, and re-check on resume.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — every public symbol
- [`05-platform-notes.md`](05-platform-notes.md) — manifest, `Info.plist`, and what does not map
- [`06-testing.md`](06-testing.md) — driving your own screen through all six states

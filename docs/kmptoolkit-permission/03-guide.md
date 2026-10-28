# kmptoolkit-permission — Guide

From the simplest case to the ones that actually bite: the state machine, the lifecycle, batches,
the Android-only grants, and the mistakes.

## The state machine

`PermissionRequestFlow` has eight states. Two are questions asked of *you*, one means the OS owns
the screen, and five are resting points.

```
                        ┌───────────────────────────────┐
                        │            Idle               │◄────── refresh(), reset()
                        └───────────────┬───────────────┘
                                 start()│
   ┌──────────────┬─────────────────────┼──────────────────────────┬────────────────┐
   │              │                     │                          │                │
Granted        Limited          Denied(explainFirst)       PermanentlyDenied   Restricted /
   │              │                     │                          │            Unavailable
   ▼              ▼                     ▼                          ▼                ▼
┌───────┐   ┌─────────┐       ┌──────────────────┐        ┌──────────────────┐ ┌─────────────┐
│Granted│   │ Limited │       │ AwaitingRationale│        │ AwaitingSettings │ │ Unavailable │
└───────┘   └─────────┘       └────────┬─────────┘        └────────┬─────────┘ └─────────────┘
                     acknowledged()    │  dismissed()   openSettings()│  declined()
                                       ▼        └──────┐             ▼        └──────┐
                                ┌────────────┐         │      (stays here; observe   │
                                │ Requesting │         │       or refresh() on       │
                                └─────┬──────┘         │       resume)               │
             granted / limited / permanently denied /  │                             │
           unavailable / denied or unanswered          ▼                             ▼
                                       │           ┌────────┐                   ┌────────┐
                                       └──────────►│ Denied │◄──────────────────┤ Denied │
                                                   └────────┘                   └────────┘
```

`start()` with a status of `Denied(explainFirst = false)` or `NotDetermined` goes straight to
`Requesting` — there is nothing to explain yet. The complete transition table is in the KDoc on
`PermissionRequestFlow` and in [`04-api-reference.md`](04-api-reference.md#permissionrequestflow);
every row of it is pinned by a test.

## The simplest useful screen

```kotlin
class CameraPresenter(controller: PermissionController) {

    private val flow = PermissionRequestFlow(CommonPermission.Camera, controller)

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
| `Limited` | proceed with what was granted; optionally offer to widen it | `start()` again to ask where the platform can — see [Limited grants](#limited-grants) |
| `Unavailable` | hide or disable the feature; do **not** send the user to settings | — |
| `Denied` | degrade gracefully; leave a way to try again | `start()` again later |

Every method that does not apply to the current state is a no-op returning the state unchanged. A
double-tapped button therefore requests once, not twice, and nothing throws.

`Unavailable` covers both `PermissionStatus.Restricted` (a parental control, an MDM profile or a
device policy withholds it — the user may not be able to change that) and
`PermissionStatus.Unavailable` (the OS, the device or your manifest has nothing to grant). Neither is
a refusal the user can undo, which is why the flow does not offer settings for them. Read the
controller's `check` if your UI must tell the two apart.

## The lifecycle: observe, or refresh on resume

A permission granted when your screen opened may be gone when it resumes. The user can revoke it in
settings, Android can auto-reset it for an app they have not opened in months, and an MDM profile
can withdraw it. None of that notifies you — but the controller re-reads every permission you
`observe` whenever the app comes back to the foreground:

```kotlin
controller.observe(CommonPermission.Location)
    .map { status -> status.isUsable }
    .collect { usable -> autoLocateEnabled = usable }
```

`observe` re-reads on every activity resume (Android) or every time the app becomes active (iOS), and
after every request through the same controller that covered the permission; it emits only on a
change. For long-lived UI state, `stateIn` it once per screen rather than collecting per composable.

A flow does not observe on its own. Call `refresh()` from your screen's resume:

```kotlin
suspend fun onScreenResumed() {
    when (flow.refresh()) {
        PermissionFlowState.Granted, PermissionFlowState.Limited -> enableTheFeature()
        else -> disableTheFeature()
    }
}
```

`refresh()` never shows anything, so it is safe on every resume. It deliberately **clears** a
`Denied` reached earlier in this flow: `Denied` records a choice the user made in *your* UI, and after
a resume the honest answer is whatever the OS now says.

## Denied once versus permanently denied

This is the distinction that makes the module worth having.

- **`AwaitingRationale`** — the OS will still show its UI. Explain, then ask again. Android reaches
  this after the first refusal; a special access, a role or a device admin that is not granted reads
  it too, because its request leaves your app and should be announced first.
- **`AwaitingSettings`** — the OS will show nothing. Asking again is a silent no-op, and only a trip
  to settings can change it. Android reaches this after the second refusal (on Android 11+ the
  second "Don't allow"; before it, "Don't ask again"), and a role after its second refusal; **iOS
  reaches it after the first**, because iOS shows its dialog at most once per install.

A dialog the user **dismissed** — back, or a tap outside it — is neither. On Android 11+ it changes
nothing, so the permission still reads `NotDetermined` and the next request shows the dialog again.

On Android the distinction rests on two flags this module persists, because Android's own API cannot
report it — see [`05-platform-notes.md`](05-platform-notes.md#the-stored-flags).

## Explain *before* asking, on iOS

Because iOS treats a refusal as final, its `AwaitingRationale` never happens — the one exception is
the "always" location upgrade. If your permission is one users refuse when surprised by it, show
your explanation *before* calling `start()`, while the status is still `NotDetermined`:

```kotlin
suspend fun onFeatureOpened() {
    if (controller.check(CommonPermission.Microphone) == PermissionStatus.NotDetermined) {
        showYourPreDialogExplanation()   // then call flow.start() from its continue button
    } else {
        flow.start()
    }
}
```

That is a product decision, not a platform one, which is why the flow does not do it for you.

## Limited grants

`PermissionStatus.Limited` — and the flow's `Limited` state — is a grant the app can use, narrower
than it asked for. Treat it as usable (`isUsable` is `true`); whether to offer a way to widen it is
your call, and how to widen it depends on the entry:

| Entry | Limited means | To widen it |
|---|---|---|
| `Location`, `LocationBackground` | approximate location only (Android 12+, iOS 14+) | Android: `request` again — the dialog offers the upgrade to precise. iOS: the user changes it in settings (a temporary full-accuracy prompt is outside this module) |
| `PhotoLibrary` | the user picked some photos (Android 14+ with `READ_MEDIA_VISUAL_USER_SELECTED` declared, iOS 14+) | Android: `request` again — the dialog lets the user change the selection. iOS: `presentLimitedPhotoLibraryPicker(viewController)` |
| `Contacts` | the user picked some contacts (iOS 18+) | the user changes it in settings, or your app uses the system contact picker |
| `Calendar` | write-only access granted instead of full (iOS 17+) | `request` again — iOS asks for the upgrade to full access |
| `Notifications` | provisional: delivered quietly to Notification Center (iOS) | `request` again — iOS shows the full prompt |

`canPrompt` is `false` for `Limited`, because whether a request shows anything depends on the
platform and the entry. `PermissionRequestFlow.start()` from `Limited` re-checks and lands on
`Limited` again; call `controller.request(permission)` directly when you mean "ask to widen it".

## Using the controller directly

Not every case needs the flow. A settings screen that only shows the current state wants `check()`:

```kotlin
val enabled: Boolean = controller.check(CommonPermission.Notifications).isUsable
```

The extension properties answer the questions a toggle actually asks:

| Property | `true` for |
|---|---|
| `isGranted` | `Granted` only — the full grant |
| `isUsable` | `Granted` and `Limited` — what a call site about to *use* the capability wants |
| `canPrompt` | `NotDetermined` and `Denied` — tapping would put the system UI on screen; otherwise the button should open settings, or not exist |

`check` never shows UI. It suspends — iOS answers some authorizations asynchronously and Android reads
and occasionally clears the stored flags — so call it from a coroutine, and `observe` instead of
polling it from a composition.

## Several permissions at once

```kotlin
val statuses: Map<Permission, PermissionStatus> = controller.request(
    listOf(CommonPermission.Camera, CommonPermission.Microphone),
)
```

The map has one entry per distinct permission, in your order. On Android every runtime permission in
the batch that can still show a dialog shares **one** system dialog — the user sees "Allow camera?"
and "Allow microphone?" back to back, without your app in between — and everything else (special
accesses, roles, background location, an entry already decided) is then requested one by one, in
order. On iOS there is no shared dialog: each prompt follows the previous one.

Use a batch where the permissions belong to one feature and one explanation covers them all. Where
each needs its own explanation, drive a `PermissionRequestFlow` per permission instead — that is why
the flow takes exactly one.

Background location (and background body sensors) never joins the shared dialog: Android grants it
only on top of the foreground permission and silently ignores a request for both at once. Put
`Location` before `LocationBackground` in the batch, and the second is asked for once the first is
granted.

## Platform permissions from shared code

Shared code cannot name `AndroidPermission.ExactAlarm` or `IosPermission.Tracking`. When shared code
has to decide something about one, take the permission as a value:

```kotlin
// commonMain
class ExactReminders(private val controller: PermissionController, private val exactAlarm: Permission?) {
    suspend fun canBeExact(): Boolean = exactAlarm == null || controller.check(exactAlarm).isGranted
}

// androidMain
ExactReminders(controller, AndroidPermission.ExactAlarm)
// iosMain
ExactReminders(controller, exactAlarm = null)   // nothing to grant on iOS
```

A controller asked about a type it does not know — an `AndroidPermission` handed to the iOS
controller through such a parameter, say — answers `Unavailable` and shows nothing.

## Special access, roles and device admins

On Android three kinds of grant are not the permission dialog, and all three go through the same
`check` / `request` / `openSettings`:

- **A special access** (`AndroidPermission.SpecialAccess`: exact alarms, overlays, all-files access,
  usage stats, battery optimizations, notification-listener access, Do Not Disturb, media management,
  installing packages, full-screen notifications, keeping permissions when unused, and one of your
  accessibility services) is a toggle on a Settings screen. `request` opens that screen and suspends
  until an activity of your app is resumed again — the user coming back — then re-reads the status.
  Not granted reads `Denied(explainFirst = true)`: the screen can always be opened again, and sending
  a user out of the app unannounced is the worst way to ask. Nothing is stored.
- **A role** (`AndroidPermission.Role`, API 29+ — `Role.Home` for the default launcher, any other by
  its `RoleManager.ROLE_*` name) is a system dialog answering with a result. A first refusal reads
  `Denied(explainFirst = true)`; after the second, Android stops showing the dialog and the role reads
  `PermanentlyDenied` — `openSettings` then opens the default-apps screen.
- **A device admin** (`AndroidPermission.DeviceAdmin(component)`) is the "Activate device admin app"
  screen, which Android shows every time it is asked, so a refusal is never permanent.

```kotlin
// androidMain
suspend fun onBecomeLauncherTapped() {
    when (controller.request(AndroidPermission.Role.Home)) {
        PermissionStatus.Granted -> showHome()
        PermissionStatus.PermanentlyDenied -> controller.openSettings(AndroidPermission.Role.Home)
        else -> Unit
    }
}
```

`PermissionRequestFlow` works over all three. A special access's `AwaitingRationale` is where you say
"we are about to take you to Settings".

A special access request that is cancelled while the user is on the Settings screen leaves the screen
where it is; the next `check` or `observe` sees what the user did there.

## Any other Android permission: `Runtime(name)`

```kotlin
val phoneState = AndroidPermission.Runtime(Manifest.permission.READ_PHONE_STATE)
controller.request(phoneState)
```

The escape hatch for a permission this vocabulary does not model. It is handled by the permission's
protection level on the device: a **dangerous** permission gets its own dialog and the same refusal
bookkeeping as every typed entry, with its flags keyed by the name; **any other** level (install-time,
signature, privileged, development) cannot be prompted for, and reads `Granted` when the app holds it
and `Unavailable` otherwise; a name **unknown to the device** reads `Unavailable`. It knows nothing of
a permission's history across API levels — prefer a typed entry where one exists.

## Scanning for Bluetooth devices

A device picker that lists what is nearby and then connects needs `BluetoothScan` to list and
`BluetoothConnect` to connect — one batch:

```kotlin
suspend fun canOpenPicker(): Boolean =
    controller.request(listOf(CommonPermission.BluetoothScan, CommonPermission.BluetoothConnect))
        .values.all { it.isGranted }
```

- **Below Android 12 the scan permission is location.** `BluetoothScan` then reports and requests
  exactly what `Location` does, so the user sees the location dialog. Say in your own copy, before the
  request, that listing Bluetooth devices is why.
- **From Android 12 the manifest decides.** Declare `BLUETOOTH_SCAN` with
  `android:usesPermissionFlags="neverForLocation"`. Without it the entry reads `Unavailable`, a request
  shows nothing, and the controller logs a warning naming the attribute.

A granted scan still finds nothing with Bluetooth off, or — below Android 12 — with location services
off; neither is a permission. To let the user pick one device to pair with, Android's
`CompanionDeviceManager` shows a system chooser that needs neither permission.

## How settings screens open (Android)

`openSettings()` and every special-access request leave the app for a system screen, and which task
that screen lands in decides where Back goes, what Recents shows, and whether a kiosk app's lock-task
allowlist still applies. That decision is a `SystemScreenLauncher` from `kmptoolkit-activity`, the
factory's `systemScreenLauncher` parameter. The default is `SystemScreenLauncher.callerTask` on the
controller's `ActivityAccess`:

- Called on the main thread with an activity resumed, the screen is started from that activity with no
  task flags, so it lands on your task, Back returns to the screen that asked, and a two-pane Settings
  shows it in a single pane.
- Off the main thread, or with no activity resumed, it opens in a task of its own
  (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`).

The defaults of all the suite's factories side by side are in
[`kmptoolkit-activity`'s guide](../kmptoolkit-activity/03-guide.md#opening-system-screens).

### A kiosk (lock-task) app

A lock-task app keeps every screen out of its locked task with `SeparateTask` — `callerTask` would
put Settings inside it, where the allowlist no longer confines it. Settings in a separate task needs
its package on the allowlist, and that allows the **whole** Settings package while it is on the
list, not just the one screen. So open the allowlist only for the screens you expect, close it again
if the launch did not happen — it returned `false` or threw — and otherwise close it when your app
resumes:

```kotlin
val kiosk = SystemScreenLauncher { request ->
    when (request.kind) {
        PermissionScreen(AndroidPermission.ExactAlarm), PermissionScreen(CommonPermission.Notifications) -> {
            settingsWindow.open() // yours: adds the Settings package to the lock-task allowlist
            val launched: Boolean = try {
                SystemScreenLauncher.SeparateTask.launch(request)
            } catch (cause: Exception) {
                settingsWindow.close()
                throw cause
            }
            if (!launched) settingsWindow.close()
            launched
        }
        else -> SystemScreenLauncher.SeparateTask.launch(request)
    }
}
// …and settingsWindow.close() in your activity's onResume.

val controller = createPermissionController(context, storage, activityAccess, systemScreenLauncher = kiosk)
```

### Logging each launch without changing it

A launcher that only observes wraps the **same** default the factory would have used — otherwise
adding a log line silently moves the screens to another task:

```kotlin
val activityAccess: ActivityAccess = createActivityAccess(application)
val controller = createPermissionController(
    context = application,
    storage = storage,
    activityAccess = activityAccess,
    systemScreenLauncher = SystemScreenLauncher { request ->
        Log.i("Screens", "opening ${request.kind}")
        SystemScreenLauncher.callerTask(activityAccess).launch(request)
    },
)
```

### What the launcher receives

One call per `openSettings()` or special-access request, with kind `PermissionScreen(permission)` and
every candidate intent at once: one or more, most specific first — this app's own page, then the
generic list of the same access where the platform has one, and for `openSettings` of a runtime
permission the app's details page. `SeparateTask` and `callerTask` open the first one the device can
show. Returning `false`, or throwing, makes that call return `false` (or the request return the
status unchanged), and is logged. The candidates per screen are in
[`05-platform-notes.md`](05-platform-notes.md#special-accesses).

## Bringing your own host (Android)

The default `ActivityResultPermissionRequestHost` covers every `ComponentActivity`. If your app
already owns its `registerForActivityResult` plumbing — or shows dialogs from something that is not a
`ComponentActivity` — implement `PermissionRequestHost` and pass it as `host`. Its two members show the
permission dialog for a list of Android permission strings and start an intent for a result (roles,
device admins); each must call back exactly once when it returns `true`, and never when it returns
`false`. The controller always calls them on the main thread. See
[`04-api-reference.md`](04-api-reference.md#permissionrequesthost-android-only).

## Mistakes worth avoiding

- **Requesting without declaring.** A permission missing from your manifest or `Info.plist` reads
  `Unavailable` and logs a warning; a request shows nothing. Check
  [`05-platform-notes.md`](05-platform-notes.md) first.
- **Building the Android controller after the first activity resumed.** Its activity tracker is then
  not told about the activity already on screen, and the first request finds no activity to show its
  dialog from. Build it in `Application.onCreate`.
- **Sending an `Unavailable` user to settings.** There is nothing to switch on there.
- **Treating `Limited` as refused.** An approximate location or a few photos is usually enough;
  `isUsable` is the check for "may I proceed".
- **Driving one flow from two coroutines.** It holds mutable state and does not synchronize. The
  controller underneath serializes requests, so no two dialogs overlap, but the flow's state would
  follow whichever finished last.
- **Treating `openSettings() == true` as consent.** It means a screen was handed to the system. Read
  the status when the user is back — `observe` does that for you.
- **Asking on app start.** Both platforms give you one good moment to ask. Spending it on a splash
  screen, before the user has seen why the permission matters, is how a permission becomes
  permanently denied on the first run. On iOS App Tracking Transparency is not even shown then: a
  request before the app is active returns `NotDetermined` at once.
- **Reading `state.value` while a `start()` is in flight** expecting the outcome — it will be
  `Requesting`. Use the return value.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — every public symbol
- [`05-platform-notes.md`](05-platform-notes.md) — manifest, `Info.plist`, and what each entry maps to
- [`06-testing.md`](06-testing.md) — driving your own screen through every state

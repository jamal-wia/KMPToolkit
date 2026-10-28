# kmptoolkit-permission — Migrating from 2.x to 3.0

3.0 replaces the module's two engines — `PermissionHandler` for runtime permissions and
`SpecialPermissionHandler` for Android special access — with one `PermissionController`, and the
`Permission` and `SpecialPermission` enums with a vocabulary split by platform. The coordinates are
unchanged. What you have stored is kept: every 2.x flag is read by 3.0 as it is.

Read [What behaves differently](#what-behaves-differently) even if the code compiles after the
renames: several statuses now say something 2.x folded away, most visibly `Limited`.

## The renames

| 2.x | 3.0 |
|---|---|
| `Permission.NOTIFICATIONS` | `CommonPermission.Notifications` |
| `Permission.MICROPHONE` | `CommonPermission.Microphone` |
| `Permission.CAMERA` | `CommonPermission.Camera` |
| `Permission.LOCATION` | `CommonPermission.Location` |
| `Permission.LOCATION_BACKGROUND` | `CommonPermission.LocationBackground` |
| `Permission.MEDIA_AUDIO` | `CommonPermission.AudioLibrary` |
| `Permission.BLUETOOTH_CONNECT` | `CommonPermission.BluetoothConnect` |
| `Permission.BLUETOOTH_SCAN` | `CommonPermission.BluetoothScan` |
| `SpecialPermission.EXACT_ALARM` | `AndroidPermission.ExactAlarm` |
| `SpecialPermission.OVERLAY` | `AndroidPermission.Overlay` |
| `SpecialPermission.WRITE_SETTINGS` | `AndroidPermission.WriteSettings` |
| `SpecialPermission.ALL_FILES_ACCESS` | `AndroidPermission.AllFilesAccess` |
| `SpecialPermission.USAGE_STATS_ACCESS` | `AndroidPermission.UsageStatsAccess` |
| `SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS` | `AndroidPermission.IgnoreBatteryOptimizations` |
| `SpecialPermission.NOTIFICATION_LISTENER_ACCESS` | `AndroidPermission.NotificationListenerAccess` |
| `SpecialPermission.DO_NOT_DISTURB_ACCESS` | `AndroidPermission.DoNotDisturbAccess` |
| `SpecialPermission.MEDIA_MANAGEMENT` | `AndroidPermission.MediaManagement` |
| `SpecialPermission.INSTALL_UNKNOWN_APPS` | `AndroidPermission.InstallPackages` |
| `SpecialPermission.FULL_SCREEN_INTENT` | `AndroidPermission.FullScreenNotifications` |
| `SpecialPermission.KEEP_PERMISSIONS_WHEN_UNUSED` | `AndroidPermission.KeepPermissionsWhenUnused` |
| `PermissionHandler` | `PermissionController` |
| `SpecialPermissionHandler` | `PermissionController` — the same instance |
| `handler.openAppSettings()` | `controller.openSettings(permission)` — opens that permission's most specific page |
| `special.isGranted(p)` | `controller.check(p).isGranted` (suspends) |
| `special.requestViaSettings(p)` | `controller.request(p)` — opens the screen and returns the re-read status once the user is back — or `controller.openSettings(p)` to open it without waiting |
| `PermissionStatus.Denied(shouldShowRationale)` | `PermissionStatus.Denied(explainFirst)` |
| `PermissionConfig` | `AndroidPermissionConfig` (androidMain), same `keyPrefix` and default |
| `AppDetailsScreen`, `SpecialPermissionScreen(p)` | `PermissionScreen(permission)` |
| `PermissionRequestFlow(permission, handler)` | `PermissionRequestFlow(permission, controller)` |
| `RecordingPermissionHandler` | `FakePermissionController` |
| `RecordingSpecialPermissionHandler` | `FakePermissionController` — `setStatus(AndroidPermission.X, PermissionStatus.Granted)` |
| `handler.openAppSettingsCount` | `controller.openedSettings.size` |

A `when` over the old enums becomes a `when` over the sealed types; keep an `else` branch, since the
vocabularies and `PermissionStatus` can grow in a minor release.

## The factories

**Android.** One factory replaces `createPermissionHandler` (both overloads),
`createPermissionHandlerWithLauncher`, `createSpecialPermissionHandler` and
`createSpecialPermissionHandlerWithLauncher`:

```kotlin
// 2.x
val handler = createPermissionHandler(context, host = this, storage = storage, activityAccess = activityAccess)
val special = createSpecialPermissionHandler(context)

// 3.0 — in Application.onCreate
val controller = createPermissionController(context, storage, activityAccess = activityAccess)
```

- **The host is optional.** The default `ActivityResultPermissionRequestHost` shows the dialog from the
  resumed `ComponentActivity` through its `ActivityResultRegistry`. Delete the activity's
  `PermissionRequestHost` implementation and its `registerForActivityResult` launchers — or keep them
  and pass `host = …` if your app needs its own plumbing.
- **A host you keep must change.** The single-permission `launch(androidPermission: String, …)` is
  gone; the multi-permission `launch(androidPermissions: List<String>, …)` and a new
  `launchForResult(intent, onResult: (resultCode: Int) -> Unit)` (roles, device admins) are both
  abstract. `RequestMultiplePermissions` and `StartActivityForResult` are the contracts.
- **Build it in `Application.onCreate`** when you let it create its own `ActivityAccess`: the tracker
  must be listening before the first activity resumes.
- **Parameter order.** `activityAccess` now comes before `host`. Call it with named arguments.

**iOS.** `createPermissionHandler(logger)` and `createSpecialPermissionHandler()` become
`createPermissionController(options = IosPermissionOptions(), logger = …)`.

## Special access in shared code

2.x exposed `SpecialPermission` and `SpecialPermissionHandler` in `commonMain`, answering "granted"
for every entry on iOS. In 3.0 `AndroidPermission` exists only in `androidMain`, so shared code that
named an Android special access no longer compiles. Take the permission as a value from platform code
instead — `null`, or no check at all, on iOS:

```kotlin
// commonMain
class ExactReminders(private val controller: PermissionController, private val exactAlarm: Permission?) {
    suspend fun canBeExact(): Boolean = exactAlarm == null || controller.check(exactAlarm).isGranted
}
// androidMain: ExactReminders(controller, AndroidPermission.ExactAlarm)
// iosMain:     ExactReminders(controller, exactAlarm = null)
```

## What behaves differently

### `Limited`, where 2.x said `Granted`

`PermissionStatus.Limited` is new: the app may use the capability, narrower than it asked for. Some
grants that 2.x reported as `Granted` are `Limited` now:

| Entry | 2.x | 3.0 |
|---|---|---|
| `Location` / `LocationBackground`, approximate only (Android 12+, iOS 14+ reduced accuracy) | `Granted` | `Limited` |
| `Notifications`, provisional (iOS) | `Granted` | `Limited` |

**Code that tests `status.isGranted` before using one of these will now refuse an approximate
location or a provisional grant.** Use `status.isUsable` — `true` for `Granted` and `Limited` — at
call sites that are about to use the capability, and keep `isGranted` only where you need the full
grant (precise location for turn-by-turn navigation, say). `PermissionRequestFlow` has a matching
`Limited` state; a `when (flow.start())` that only handled `Granted` should handle it too.

The new entries use `Limited` from the start: photos the user picked (Android 14+, iOS), contacts the
user picked (iOS 18+), and write-only calendar access for `Calendar` (iOS 17+).

### `Restricted` and `Unavailable`, where 2.x guessed

| Situation | 2.x | 3.0 |
|---|---|---|
| iOS parental controls or MDM (`.restricted`) | `PermanentlyDenied` | `Restricted` |
| Android device policy revoked it | read like any refusal or a permission never asked for | `Restricted` |
| Android permission not declared in the manifest | `NotDetermined`, request returned at once | `Unavailable`, logged once |
| iOS purpose string missing from `Info.plist` | the OS terminated the app at the request | `Unavailable`, logged once, nothing requested |
| `BLUETOOTH_SCAN` declared without `neverForLocation` (API 31+) | `NotDetermined` | `Unavailable` |
| a permission type the controller does not know | — | `Unavailable` |

`PermissionRequestFlow` maps both to its new `Unavailable` state, and never to `AwaitingSettings`:
sending the user to settings for either is a dead end. A `when` that sent every non-granted state to
a settings prompt should give `Unavailable` its own branch.

### Notifications below Android 13

2.x reported `Granted` below API 33, unconditionally. 3.0 reports whether the user has the app's
notifications switched on (`areNotificationsEnabled()`): `Granted`, or `PermanentlyDenied` when they
are off — settings being the only place that changes it.

### Special access

- **A request waits for the user.** `requestViaSettings` returned once the screen was handed to the
  system; `request` suspends until an activity of the app is resumed again and returns the re-read
  status. Use `openSettings` for the old fire-and-forget behaviour.
- **Not granted is `Denied(explainFirst = true)`**, not a `false`, so `PermissionRequestFlow` works
  over a special access and stops at `AwaitingRationale` — your "we are taking you to Settings" step —
  before leaving the app.
- **The screens open on your task.** 2.x's `createSpecialPermissionHandler` used
  `SystemScreenLauncher.SeparateTask`; the controller's default is `callerTask(activityAccess)`, which
  2.x's runtime handler already used for the app-details page. A kiosk (lock-task) app passes
  `systemScreenLauncher = SystemScreenLauncher.SeparateTask` — or its own launcher around it, matching
  `PermissionScreen(…)` instead of `SpecialPermissionScreen(…)` / `AppDetailsScreen`.
- **Entries that do not exist below their API level are `Unavailable`**, not granted:
  `MediaManagement` below API 31, `InstallPackages` below API 26 (2.x read the unrelated device-wide
  "Unknown sources" switch there), and `FullScreenNotifications` below API 34 when the manifest does not
  declare `USE_FULL_SCREEN_INTENT`.

### Everything else

- **`check` suspends on Android too.** It always had the `suspend` modifier, but 2.x's KDoc said it
  returned without suspending on Android. It does suspend: it reads, and for a granted permission with
  a stale flag clears, the flags in the suspending `KeyValueStorage`. Call it from a coroutine, or
  `observe` instead.
- **Requests are serialized.** Two concurrent `request` calls through one controller run one after
  the other instead of racing for the dialog.
- **`canPrompt` is `false` for `Limited`.** Whether a request shows anything from there depends on the
  entry and platform; see [the guide](03-guide.md#limited-grants).
- **`PermissionStatus` has seven cases.** An exhaustive `when` over it needs the three new ones, or an
  `else`.

## Stored flags

Nothing to migrate. 3.0 keys its flags by `Permission.id`, and every entry that existed in 2.x has its
2.x enum name as its id — `CAMERA`, `MEDIA_AUDIO`, `EXACT_ALARM`, `INSTALL_UNKNOWN_APPS`, … — under the
same default prefix. A permission 2.x recorded as permanently denied is still permanently denied after
the upgrade.

## Modules that depend on this one

- **`kmptoolkit-notification`** — `createNotifier(context, permissions = …)` takes a
  `PermissionController` instead of a `PermissionHandler`, on both platforms. It treats `Limited` (iOS
  provisional) as permission to post, as 2.x treated provisional `Granted`.

## Tests

- Replace `RecordingPermissionHandler` and `RecordingSpecialPermissionHandler` with
  `FakePermissionController`; the scripting calls keep their names.
- `openAppSettingsCount` becomes `openedSettings`, a list of the permissions passed.
- A test that scripted a special access with `setGranted(p, false)` scripts
  `setStatus(p, PermissionStatus.Denied(explainFirst = true))`, which is what the real controller
  reports.

See [`06-testing.md`](06-testing.md).

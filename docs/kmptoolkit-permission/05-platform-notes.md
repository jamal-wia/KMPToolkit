# kmptoolkit-permission — Platform notes

What you must declare, what each `Permission` maps to, and — the part worth reading before you
design a screen — where the two platforms genuinely do not agree.

## What you must declare

This module declares **no** Android permission in its own manifest. That is a repository-wide rule
([`../01-architecture.md`](../01-architecture.md#android-manifests)) and it matters most here: a
permission library that merged `CAMERA` into every consumer's manifest would hand apps a Play Store
declaration to justify and users a permission on the listing, for a capability they may never use.
An `androidUnitTest` asserts the merged manifest against a real `PackageManager` to keep it honest.

So each permission you intend to request is yours to declare, on both platforms:

| `Permission` | Android `<uses-permission>` | iOS `Info.plist` key |
|---|---|---|
| `NOTIFICATIONS` | `android.permission.POST_NOTIFICATIONS` (API 33+) | none |
| `MICROPHONE` | `android.permission.RECORD_AUDIO` | `NSMicrophoneUsageDescription` |
| `CAMERA` | `android.permission.CAMERA` | `NSCameraUsageDescription` |

Getting this wrong fails differently on each platform, and both failures are confusing:

- **Android**: a permission missing from the manifest produces no dialog and an immediate denial.
  The handler records that as a real refusal, so the permission goes *permanently denied* because of
  a build-configuration mistake. If a permission is denied instantly on a fresh install, check the
  manifest first.
- **iOS**: a missing usage-description string does not produce an error. The OS **terminates the
  app** at the moment of the request. There is nothing this module can turn into a status.

## Android

### Where the status comes from

`Context.checkSelfPermission` for granted, `Activity.shouldShowRequestPermissionRationale` (reached
through the module's own internally tracked activity, per call, never retained) for the rationale
hint, and one persisted flag for the rest.

### The asked flag

Android cannot distinguish "never asked" from "permanently denied": both report the permission as
not granted with `shouldShowRequestPermissionRationale() == false`. The only way to tell them apart
is to remember whether the dialog was ever shown — which is why the Android factory takes a
`KeyValueStorage`.

- One entry per permission, written as `"<prefix>.asked.<PERMISSION NAME>"`, where `<prefix>`
  defaults to `"<your application id>.kmptoolkit.permission"`. Configurable through
  `PermissionConfig`; nothing is hardcoded to this library's own namespace.
- Written **after** the dialog resolves with a refusal, never before. A dialog that could not be
  shown at all — no activity, no registered launcher — leaves no flag, so a launcher bug cannot turn
  a permission permanently denied.
- Cleared the moment the permission is granted. A permission the user later revokes therefore reads
  as `NotDetermined` again, which is correct: Android will show its dialog for it again. The same
  applies after Android's automatic reset of permissions for apps that have not been opened in
  months.
- Cleared **only when there is something to clear**. `check` stays a query: checking a granted
  permission that has no flag stored — the overwhelmingly common case — performs no write at all, so
  a consumer that checks before every action can do so as often as it likes. Checking a granted
  permission that *does* still carry a stale flag costs exactly one write, once.
- Keyed by the enum's name rather than the platform string, so the flag survives Android changing
  which string a permission maps to — which has already happened once, when notifications became a
  runtime permission.

The flag is not a secret and needs no encryption; a plain `createKeyValueStorage(context)` is the
right store.

### Notifications below API 33

`POST_NOTIFICATIONS` did not exist before API 33, and notifications were allowed by default. The
handler reports `Granted` there without consulting anything, and requesting shows no dialog.

**`Granted` does not mean the user sees your notifications.** They may have switched the app's
notifications off in system settings, and that is not a permission — `NotificationManagerCompat`
`.areNotificationsEnabled()` is the question, and it is a different one, on every API level. This
module answers the permission question only.

### The settings trip

`openAppSettings()` fires `ACTION_APPLICATION_DETAILS_SETTINGS` for your own package, preferring the
resumed activity so the screen lands on your app's task and the back button returns to it; when
there is no resumed activity it falls back to the application context with
`FLAG_ACTIVITY_NEW_TASK`. Android offers no way to deep-link a single permission toggle.

### `minSdk`

24, matching the rest of the toolkit. Everything above uses APIs available from 23.

## iOS

### The one rule that shapes the API

**iOS shows its permission dialog at most once per install.** A refusal is final; only system
settings can change it. So the iOS handler never returns `Denied` — a refusal is
`PermanentlyDenied` immediately — and `shouldShowRationale` is never `true`, because there is no
second dialog for a rationale to precede.

The practical consequence for your UI: if a permission needs explaining, explain it *before*
calling `request()`, while the status is still `NotDetermined`. See
[`03-guide.md`](03-guide.md#explain-before-asking-on-ios).

### What maps cleanly, and what does not

| `Permission` | API | Fit |
|---|---|---|
| `CAMERA` | `AVCaptureDevice.authorizationStatus(for: .video)` + `requestAccess` | **Clean.** A synchronous status with exactly the four cases, and a callback returning a boolean. |
| `MICROPHONE` | `AVAudioSession.recordPermission` + `requestRecordPermission` | **Clean.** Same shape: a synchronous three-valued status, a boolean callback. |
| `NOTIFICATIONS` | `UNUserNotificationCenter.getNotificationSettings` + `requestAuthorization` | **Mostly clean, but asynchronous.** The status arrives in a completion handler, not as a return value. |

That last row is the reason `PermissionHandler.check` is a **suspending** function even though
Android answers it synchronously. The alternative — a synchronous `check` that returns
`NotDetermined` for notifications and quietly lies — is what the donor implementation did, and it
made every notification screen wrong on iOS.

Notification authorization also has two states this module folds into `Granted`: **provisional**
(quiet delivery, granted without a dialog) and **ephemeral** (an App Clip). Neither can be requested
through this module, but an app that obtained one elsewhere must not be told it has nothing.

`AVAuthorizationStatusRestricted` — a parental control or an MDM profile — maps to
`PermanentlyDenied`. It is not a refusal the user made and may not be one they can lift, but
settings is still the only place it could possibly change, which is exactly what
`PermanentlyDenied` promises. Nothing in this module claims a settings trip will *succeed*.

### What is not in the catalog, and why

These were considered and left out rather than shipped as untested scaffolding:

- **Location.** iOS grants it through `CLLocationManager`'s delegate — asynchronously, possibly long
  after the call, and possibly more than once as the user moves between "while in use" and "always".
  `check`/`request` cannot express that without becoming a subscription, and the four-case
  `PermissionStatus` has no room for the when-in-use/always distinction or for iOS 14's temporary
  precise-location grant. Android adds its own wrinkle: fine and coarse must be requested in the
  same dialog for the Precise/Approximate toggle to render correctly.
- **Photo library.** iOS's `PHAuthorizationStatus` has `.limited` — the user picked specific photos —
  which is neither granted nor denied, and collapsing it either way loses the only fact a photo
  picker cares about. Android's string, meanwhile, depends on the API level and splits per media
  type (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, plus the visual-media-picker path that needs no
  permission at all).
- **Contacts, calendar, health, Bluetooth, exact alarms.** No mapping was written and none is
  claimed. Exact alarms are worth naming specifically: Android's `SCHEDULE_EXACT_ALARM` is a
  settings-only grant with no runtime dialog, so it cannot be driven by this API at all —
  `kmptoolkit-scheduler` deliberately falls back to an inexact alarm instead of requiring it.

`openAppSettings()` on iOS opens `UIApplicationOpenSettingsURLString` on the main queue, so `true`
means "handed to UIKit" rather than "the settings screen is up".

## Read next

- [`06-testing.md`](06-testing.md) — the fixtures, and what to assert
- [`04-api-reference.md`](04-api-reference.md) — every public symbol

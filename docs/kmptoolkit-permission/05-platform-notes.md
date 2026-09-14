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
| `LOCATION` | `android.permission.ACCESS_FINE_LOCATION` **and** `android.permission.ACCESS_COARSE_LOCATION` | `NSLocationWhenInUseUsageDescription` |
| `LOCATION_BACKGROUND` | `android.permission.ACCESS_BACKGROUND_LOCATION` (API 29+), plus the two above | `NSLocationAlwaysAndWhenInUseUsageDescription`, plus the one above, and the `location` background mode if you use it |
| `MEDIA_AUDIO` | `android.permission.READ_MEDIA_AUDIO` (API 33+) and `android.permission.READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"` | `NSAppleMusicUsageDescription` |
| `BLUETOOTH_CONNECT` | `android.permission.BLUETOOTH_CONNECT` (API 31+) and `android.permission.BLUETOOTH` with `android:maxSdkVersion="30"` | `NSBluetoothAlwaysUsageDescription` |

**App Store review looks at what the binary links, not at what it calls.** Linking this module links
CoreLocation, CoreBluetooth and MediaPlayer into your iOS app even if you only ever request the
microphone, and App Store Connect has been known to ask for the matching purpose strings on the
strength of that alone. If it does, add the strings — they are shown only if a request is made.

Getting this wrong fails differently on each platform, and both failures are confusing:

- **Android**: a permission missing from the manifest produces no dialog and an immediate denial
  with no rationale. That is indistinguishable from a dismissed dialog, so the status stays
  `NotDetermined` and each request returns at once, showing nothing. If requesting a permission never
  shows a dialog on a fresh install, check the manifest first.
- **iOS**: a missing usage-description string does not produce an error. The OS **terminates the
  app** at the moment of the request. There is nothing this module can turn into a status.

## Android

### Where the status comes from

`Context.checkSelfPermission` for granted, `Activity.shouldShowRequestPermissionRationale` (reached
through an activity tracker, per call, never retained — the module's own, or the `ActivityAccess`
you pass to the overload that takes one) for the rationale hint, and two persisted flags for the
rest.

| Granted | Rationale | Asked | Refused before | Status |
|---|---|---|---|---|
| yes | — | — | — | `Granted` (both flags cleared) |
| no | `true` | — | — | `Denied(shouldShowRationale = true)`; "refused before" is recorded |
| no | `false` | no | — | `NotDetermined` |
| no | `false` | yes | yes | `PermanentlyDenied` |
| no | `false` | yes | no | `NotDetermined` — a dismissed dialog, which Android 11+ shows again (an asked entry written before 1.5.0 reads `PermanentlyDenied` instead; see below) |
| no | no activity to ask | no | — | `NotDetermined` |
| no | no activity to ask | yes | — | `Denied(shouldShowRationale = false)` — nothing permanent is concluded without an answer |

The dialog's answer arrives just before the requesting activity is resumed. A request whose
continuation runs in that gap waits, up to a second, for the activity to be back before it reads the
rationale — otherwise a genuine first refusal would look like a dismissal.

**The one case no app can see:** a permission the user switched to "Don't allow" in system settings
before the app ever asked for it. Android then refuses without a dialog and without a rationale —
exactly what a dismissal looks like — so it reads `NotDetermined`, and requesting it returns at once.
Offer a way to settings from a screen whose request keeps coming back refused.

### The asked flag

Android cannot distinguish "never asked", "dismissed" and "permanently denied": all three report the
permission as not granted with `shouldShowRequestPermissionRationale() == false`. The only way to
tell them apart is to remember whether the dialog was ever shown, and whether the user ever refused
it there — which is why the Android factory takes a `KeyValueStorage`.

- Two entries per permission: `"<prefix>.asked.<PERMISSION NAME>"` and
  `"<prefix>.rationale.<PERMISSION NAME>"` (since 1.5.0), where `<prefix>` defaults to
  `"<your application id>.kmptoolkit.permission"`. Configurable through `PermissionConfig`; nothing
  is hardcoded to this library's own namespace.
- The `rationale` entry is written the first time Android asks for a rationale — which it does only
  after a refusal through the dialog — and only then, by `check` as well as by `request`: once per
  permission, since a set flag is never written again.
- The `asked` entry holds `dialog-shown` since 1.5.0. Versions before it wrote `true` and recorded no
  refusals, so a `true` entry is read as they read it: with no rationale, `PermanentlyDenied`. A
  permission those versions recorded as permanently denied therefore stays so after an upgrade; the
  dismissal fix applies from the next grant, or on a fresh install.
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

### Location, audio files and Bluetooth

- **`LOCATION`** is two Android permission strings requested in one dialog, through the host's
  multi-permission `launch`. Either grant — precise or approximate — reports `Granted`. The rationale
  counts as shown if Android asks for it on either string.
- **`LOCATION_BACKGROUND`** is a grant on top of foreground location. From API 30 Android refuses a
  background request, silently, while foreground location is not granted, so the handler does not
  launch one then: request `LOCATION` first. From API 30 the "dialog" is the app's location page in
  system settings, where the user picks "Allow all the time". Below API 29 there is no separate grant
  and this entry reports whatever `LOCATION` reports.
- **`MEDIA_AUDIO`** requests `READ_MEDIA_AUDIO` from API 33 and `READ_EXTERNAL_STORAGE` below it.
- **`BLUETOOTH_CONNECT`** has no runtime grant below API 31 and reports `Granted` there.

### Watching for changes

`observe(permission)` re-reads the status on every activity resume — which is where a change made in
system settings, or the system auto-resetting an unused app's permissions, becomes visible — and after
every `request` through the same handler. It emits only when the status actually changed. It goes
through the same `ActivityAccess` as the rationale question, and unregisters from it when collection
stops.

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
second dialog for a rationale to precede. The single exception is `LOCATION_BACKGROUND` — see the
table below: "while in use" can still be upgraded once, which is what `Denied(shouldShowRationale =
true)` says.

**`LOCATION` and `LOCATION_BACKGROUND` need iOS 14 or later.** They read the instance
`CLLocationManager.authorizationStatus` and listen to `locationManagerDidChangeAuthorization`, both
iOS 14 APIs; an app that still deploys to iOS 13 must not use these two entries.

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

| `LOCATION` | `CLLocationManager.authorizationStatus` + `requestWhenInUseAuthorization` | **Folded.** "While in use" and "always" are both granted. The answer arrives through the delegate, which `request` awaits; a manager and its delegate are held until it does. Reduced (approximate) accuracy is still granted — precision is a property of the fix, not the permission. |
| `LOCATION_BACKGROUND` | the same manager + `requestAlwaysAuthorization` | **Folded, with one inference.** Only "always" is granted; "while in use" is `Denied(shouldShowRationale = true)`, because iOS may still offer the upgrade. iOS shows that upgrade prompt **at most once** and says nothing when it declines, so `request` waits for a changed status or — if the app did not resign active within a second, meaning no prompt covered it — returns the status as it is. Once the upgrade has been asked for in this process and the status stayed "while in use", `check` and `request` report `PermanentlyDenied`, so a request flow moves on to settings instead of asking again. The fact is kept in memory: after a restart, one more request finds it out again. While location is not determined, a request asks for "while in use" first, as iOS itself requires. |
| `MEDIA_AUDIO` | `MPMediaLibrary.authorizationStatus` + `requestAuthorization` | **Clean.** The user's music library. Restricted is permanently denied. |
| `BLUETOOTH_CONNECT` | `CBManager.authorization` + a `CBCentralManager` created to raise the prompt | **Clean, indirectly requested.** iOS has no "request Bluetooth permission" call: the prompt appears when the app first creates a central manager, which `request` does with the power alert turned off, and the answer is read once the manager reports its state. iOS has one Bluetooth permission, so this entry is it. |

### What is not in the catalog, and why

These were considered and left out rather than shipped as untested scaffolding:

- **Photo library.** iOS's `PHAuthorizationStatus` has `.limited` — the user picked specific photos —
  which is neither granted nor denied, and collapsing it either way loses the only fact a photo
  picker cares about. Android's string, meanwhile, depends on the API level and splits per media
  type (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, plus the visual-media-picker path that needs no
  permission at all).
- **Contacts, calendar, health, SMS, phone state, Bluetooth scanning and advertising.** No mapping
  was written and none is claimed.
- **Exact alarms.** Not in `Permission`, and never will be: Android's `SCHEDULE_EXACT_ALARM` is a
  settings-only grant with no runtime dialog, so it does not fit `PermissionHandler`'s shape at all.
  It is `SpecialPermission.EXACT_ALARM` instead — see below.
  `kmptoolkit-scheduler` deliberately falls back to an inexact alarm rather than requiring it.

`openAppSettings()` on iOS opens `UIApplicationOpenSettingsURLString` on the main queue, so `true`
means "handed to UIKit" rather than "the settings screen is up".

## Special access permissions

None of these need a manifest permission declaration to be *checked* — `canScheduleExactAlarms()`
and its siblings all work with nothing declared. Actually being granted `EXACT_ALARM`, though, still
requires your own manifest to declare `SCHEDULE_EXACT_ALARM` — this module never declares it for
you, the same rule as every runtime `Permission`.

| `SpecialPermission` | Android API | Settings screen |
|---|---|---|
| `EXACT_ALARM` | `AlarmManager.canScheduleExactAlarms()` (API 31+; always granted below) | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| `OVERLAY` | `Settings.canDrawOverlays()` | `ACTION_MANAGE_OVERLAY_PERMISSION` |
| `WRITE_SETTINGS` | `Settings.System.canWrite()` | `ACTION_MANAGE_WRITE_SETTINGS` |
| `ALL_FILES_ACCESS` | `Environment.isExternalStorageManager()` (API 30+; always granted below) | `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` |
| `USAGE_STATS_ACCESS` | `AppOpsManager` (`OPSTR_GET_USAGE_STATS`) | `ACTION_USAGE_ACCESS_SETTINGS` |
| `IGNORE_BATTERY_OPTIMIZATIONS` | `PowerManager.isIgnoringBatteryOptimizations()` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `NOTIFICATION_LISTENER_ACCESS` | `NotificationManagerCompat.getEnabledListenerPackages()` | `ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| `DO_NOT_DISTURB_ACCESS` | `NotificationManager.isNotificationPolicyAccessGranted` | `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |

`NOTIFICATION_LISTENER_ACCESS` additionally needs a manifest-declared listener `<service>` of your
own, bound with `BIND_NOTIFICATION_LISTENER_SERVICE` — this module only reports and redirects to the
toggle, it does not declare or implement the listener service itself.

All eight are always granted, with nothing to open, on iOS — none of them name a concept that exists
there.

## Read next

- [`06-testing.md`](06-testing.md) — the fixtures, and what to assert
- [`04-api-reference.md`](04-api-reference.md) — every public symbol

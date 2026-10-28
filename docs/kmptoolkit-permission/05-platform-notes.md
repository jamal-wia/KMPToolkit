# kmptoolkit-permission — Platform notes

What you must declare, what each entry maps to, and — the part worth reading before you design a
screen — where the two platforms genuinely do not agree.

## What you must declare

This module declares **no** Android permission in its own manifest. That is a repository-wide rule
([`../01-architecture.md`](../01-architecture.md#android-manifests)) and it matters most here: a
permission library that merged `CAMERA` into every consumer's manifest would hand apps a Play Store
declaration to justify and users a permission on the listing, for a capability they may never use.
An `androidHostTest` asserts the merged manifest against a real `PackageManager` to keep it honest.

So each permission you intend to request is yours to declare, on both platforms. A missing
declaration reads `Unavailable`, a request shows nothing, and the controller logs, once, which
declaration is missing — on Android because the dialog would never appear, on iOS because the OS
would terminate the app at the request.

### Common permissions

| `CommonPermission` | Android `<uses-permission>` | iOS `Info.plist` key |
|---|---|---|
| `Notifications` | `POST_NOTIFICATIONS` (API 33+) | none |
| `Microphone` | `RECORD_AUDIO` | `NSMicrophoneUsageDescription` |
| `Camera` | `CAMERA` | `NSCameraUsageDescription` |
| `Location` | `ACCESS_FINE_LOCATION` **and** `ACCESS_COARSE_LOCATION` | `NSLocationWhenInUseUsageDescription` |
| `LocationBackground` | `ACCESS_BACKGROUND_LOCATION` (API 29+), plus the two above | `NSLocationAlwaysAndWhenInUseUsageDescription`, plus the one above, and the `location` background mode if you use it |
| `AudioLibrary` | `READ_MEDIA_AUDIO` (API 33+) and `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"` | `NSAppleMusicUsageDescription` |
| `PhotoLibrary` | `READ_MEDIA_IMAGES` and/or `READ_MEDIA_VIDEO` (API 33+), `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+, for partial access), `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"` | `NSPhotoLibraryUsageDescription` |
| `PhotoLibraryAdd` | `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"` | `NSPhotoLibraryAddUsageDescription` |
| `Contacts` | `READ_CONTACTS`, and `WRITE_CONTACTS` if you write | `NSContactsUsageDescription` |
| `Calendar` | `READ_CALENDAR` and `WRITE_CALENDAR` | `NSCalendarsFullAccessUsageDescription` (iOS 17+) and `NSCalendarsUsageDescription` (below) |
| `CalendarWriteOnly` | `WRITE_CALENDAR` | `NSCalendarsWriteOnlyAccessUsageDescription` (iOS 17+) and `NSCalendarsUsageDescription` (below) |
| `MotionActivity` | `ACTIVITY_RECOGNITION` (API 29+) | `NSMotionUsageDescription` |
| `BluetoothConnect` | `BLUETOOTH_CONNECT` (API 31+) and `BLUETOOTH` with `android:maxSdkVersion="30"` | `NSBluetoothAlwaysUsageDescription` |
| `BluetoothScan` | `BLUETOOTH_SCAN` (API 31+) **with `android:usesPermissionFlags="neverForLocation"`**; below API 31 `BLUETOOTH` and `BLUETOOTH_ADMIN` with `android:maxSdkVersion="30"`, plus the two `Location` permissions | `NSBluetoothAlwaysUsageDescription` |
| `BluetoothAdvertise` | `BLUETOOTH_ADVERTISE` (API 31+) | `NSBluetoothAlwaysUsageDescription` |

All Android names are `android.permission.*`. Where a row lists several Android strings, the
controller asks only for the ones your manifest declares — `Contacts` without `WRITE_CONTACTS` asks for
reading alone — and reads `Unavailable` only when none is declared.

For Bluetooth scanning the declaration is the whole difference between a scan that finds devices and
one that finds nothing:

```xml
<!-- Drop the maxSdkVersion on the two location lines if the app also requests Location. -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="30" />
```

### Android-only permissions

| `AndroidPermission` | `<uses-permission>` |
|---|---|
| `NearbyWifiDevices` | `NEARBY_WIFI_DEVICES` (API 33+), with `android:usesPermissionFlags="neverForLocation"` unless you derive location; below API 33 the two `Location` permissions |
| `LocalNetwork` | `ACCESS_LOCAL_NETWORK` (API 37+, apps targeting 37+) |
| `BodySensors` | `BODY_SENSORS` with `android:maxSdkVersion="35"`, and `android.permission.health.READ_HEART_RATE` (API 36+) |
| `BodySensorsBackground` | `BODY_SENSORS_BACKGROUND` with `android:maxSdkVersion="35"`, and `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` (API 36+), plus the `BodySensors` ones |
| `Runtime(name)` | `name` |
| special accesses | see [Special accesses](#special-accesses) |
| `Role(name)` | none, but the app must qualify for the role — a `HOME` activity for `Role.Home`, the SMS components for the SMS role |
| `DeviceAdmin(component)` | a `DeviceAdminReceiver` declared with `BIND_DEVICE_ADMIN` and its policies XML |
| `AccessibilityService(component)` | an `AccessibilityService` declared with `BIND_ACCESSIBILITY_SERVICE` |

### iOS-only permissions

| `IosPermission` | `Info.plist` key |
|---|---|
| `Tracking` | `NSUserTrackingUsageDescription` |
| `SpeechRecognition` | `NSSpeechRecognitionUsageDescription` |
| `Reminders` | `NSRemindersFullAccessUsageDescription` (iOS 17+) and `NSRemindersUsageDescription` (below) |
| `FocusStatus` | `NSFocusStatusUsageDescription`, and the Communication Notifications capability |

**App Store review looks at what the binary links, not at what it calls.** Linking this module links
CoreLocation, CoreBluetooth, MediaPlayer, Photos, Contacts, EventKit, CoreMotion,
AppTrackingTransparency, Speech and Intents into your iOS app even if you only ever request the
microphone, and App Store Connect has been known to ask for the matching purpose strings on the
strength of that alone. If it does, add the strings — they are shown only if a request is made.

## Android

### Where a runtime status comes from

`Context.checkSelfPermission` for granted, `PackageManager.isPermissionRevokedByPolicy` for
`Restricted`, `Activity.shouldShowRequestPermissionRationale` (asked of the resumed activity through
the `ActivityAccess`, never retained) for the rationale hint, and two persisted flags for the rest.

| Granted | Rationale | Asked | Refused before | Status |
|---|---|---|---|---|
| yes (or the limited subset) | — | — | — | `Granted` (or `Limited`); both flags cleared |
| no, revoked by policy | — | — | — | `Restricted` |
| no | `true` | — | — | `Denied(explainFirst = true)`; "refused before" is recorded |
| no | `false` | no | — | `NotDetermined` |
| no | `false` | yes | yes | `PermanentlyDenied` |
| no | `false` | yes | no | `NotDetermined` — a dismissed dialog, which Android 11+ shows again (an asked entry written before 1.5.0 reads `PermanentlyDenied`) |
| no | no activity to ask | no | — | `NotDetermined` |
| no | no activity to ask | yes | — | `Denied(explainFirst = false)` — nothing permanent is concluded without an answer |

The dialog's answer arrives just before the requesting activity is resumed. A request whose
continuation runs in that gap waits, up to a second, for the activity to be back before it reads the
rationale — otherwise a genuine first refusal would look like a dismissal.

**The one case no app can see:** a permission the user switched to "Don't allow" in system settings
before the app ever asked for it. Android then refuses without a dialog and without a rationale —
exactly what a dismissal looks like — so it reads `NotDetermined`, and requesting it returns at once.
Offer a way to settings from a screen whose request keeps coming back refused.

### The stored flags

Android cannot distinguish "never asked", "dismissed" and "permanently denied": all three report the
permission as not granted with `shouldShowRequestPermissionRationale() == false`. The only way to
tell them apart is to remember whether the dialog was ever shown, and whether the user ever refused
it there — which is why the Android factory takes a `KeyValueStorage`.

- Two entries per permission: `"<prefix>.asked.<id>"` and `"<prefix>.rationale.<id>"`, where `<id>` is
  the entry's `Permission.id` and `<prefix>` defaults to `"<your application id>.kmptoolkit.permission"`
  (configurable through `AndroidPermissionConfig`). Every id that existed in 2.x — `CAMERA`,
  `MEDIA_AUDIO`, … — is unchanged, so 3.0 reads 2.x's flags as they are.
- The `rationale` entry is written the first time Android asks for a rationale — which it does only
  after a refusal through the dialog — by `check` as well as by `request`, once per permission.
- The `asked` entry holds `dialog-shown`. Versions before 1.5.0 wrote `true` and recorded no
  refusals, so a `true` entry is read as they read it: with no rationale, `PermanentlyDenied`.
- Written **after** the dialog resolves with a refusal, never before. A dialog that could not be
  shown, or was interrupted without an answer, leaves no flag, so a host bug cannot turn a permission
  permanently denied.
- Cleared the moment the permission is granted (or limited), and **only when there is something to
  clear**: checking a granted permission with no flag stored performs no write.
- An entry that *is* another on an API level — `BluetoothScan` and `NearbyWifiDevices` below their
  API levels are `Location` there, background location below API 29 is `Location`, background body
  sensors below API 33 is `BodySensors` — reads and writes that entry's flags, so the two can never
  disagree.
- A role reuses the two keys: `asked` after its first refusal, `rationale` after its second.
- Special accesses and device admins store nothing.

The flags are not a secret and need no encryption; a plain `createKeyValueStorage(context)` is the
right store.

### Common permissions on Android

- **`Notifications`** — `POST_NOTIFICATIONS` from API 33. Below it there is no runtime permission,
  and the entry reports whether the user has the app's notifications switched on
  (`NotificationManagerCompat.areNotificationsEnabled()`): `Granted`, or `PermanentlyDenied`, because
  settings is the only place that switch changes. A request shows nothing there. From API 33,
  `Granted` still does not mean every channel is on — channels are per-channel settings.
- **`Location`** — fine and coarse in **one** dialog, the only way Android 12+ shows the Precise /
  Approximate choice. Precise is `Granted`, approximate-only `Limited`, and a request from `Limited`
  shows the dialog again, which offers the upgrade.
- **`LocationBackground`** — on top of foreground location. Requested only once `Location` is usable:
  from API 30 Android refuses a background request without it, silently, and on API 29 it would widen
  into a foreground request. From API 30 the "dialog" is the app's location page in system settings.
  Below API 29 there is no separate grant and the entry is `Location`.
- **`AudioLibrary`** — `READ_MEDIA_AUDIO` from API 33, `READ_EXTERNAL_STORAGE` below.
- **`PhotoLibrary`** — `READ_EXTERNAL_STORAGE` up to API 32; `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`
  on API 33; on API 34+ also `READ_MEDIA_VISUAL_USER_SELECTED`, which makes the dialog offer "Select
  photos and videos". Every declared full-access string granted is `Granted`; the user-selected string
  alone is `Limited`, and a request from there shows the dialog again so the user can change the
  selection. An app that does not declare the user-selected string on API 34 gets Android's
  compatibility behaviour — a temporary full grant of the selected items — which reads `Granted`.
- **`PhotoLibraryAdd`** — `WRITE_EXTERNAL_STORAGE` up to API 28; from API 29 an app adds media
  through `MediaStore` with no permission, and the entry is `Granted`.
- **`Contacts`** — `READ_CONTACTS`, and `WRITE_CONTACTS` when declared, in one dialog; granted only
  when every declared one is.
- **`Calendar`** — `READ_CALENDAR` and `WRITE_CALENDAR` in one dialog. **`CalendarWriteOnly`** —
  `WRITE_CALENDAR`. Writing granted alone is `CalendarWriteOnly` `Granted` and `Calendar` not.
- **`MotionActivity`** — `ACTIVITY_RECOGNITION` from API 29; an install-time permission below, and
  `Granted`.
- **`BluetoothConnect`**, **`BluetoothAdvertise`** — their runtime permissions from API 31; no runtime
  grant below, and `Granted`.
- **`BluetoothScan`** — two different things on either side of API 31:
  - **API 31 and up:** `BLUETOOTH_SCAN`, declared with `neverForLocation`. Without that flag Android
    delivers scan results only to an app that *also* holds fine location and has location services
    on — a second permission group this entry will not fold into one answer. So the controller reads
    the flag from the app's own `PackageInfo`, once, and without it reports `Unavailable`, launches
    nothing, records nothing, and logs one warning naming the attribute.
  - **Below API 31:** scan results reach only an app holding location, so the entry is `Location`
    there in every respect.
  - **Not a permission, so not in the status:** Bluetooth switched on, and — below API 31 — location
    services switched on.

### Android-only permissions

- **`NearbyWifiDevices`** — `NEARBY_WIFI_DEVICES` from API 33; below it Wi-Fi scan results need
  location, and the entry is `Location`.
- **`LocalNetwork`** — `ACCESS_LOCAL_NETWORK`, a runtime permission (in the Nearby devices group) only
  on API 37+ for an app whose `targetSdkVersion` is 37+; `Granted` otherwise, read from the app's own
  `ApplicationInfo`.
- **`BodySensors`** — `BODY_SENSORS` up to API 35; from API 36 Android replaced it with Health
  Connect's `READ_HEART_RATE`, which also needs your app to declare a privacy-policy activity for
  Health Connect. **`BodySensorsBackground`** — `BODY_SENSORS_BACKGROUND` on API 33–35,
  `READ_HEALTH_DATA_IN_BACKGROUND` from 36, `BodySensors` below 33; requested only on top of
  `BodySensors`, and never in a shared dialog with it.
- **`Runtime(name)`** — by `PermissionInfo.protection` on the device: dangerous gets a dialog and the
  full bookkeeping keyed by `name`; any other level is `Granted` when held and `Unavailable`
  otherwise — a development permission granted with `adb shell pm grant` reads `Granted`; a name the
  package manager does not know is `Unavailable`.

### Roles and device admins

- **`Role(name)`** — `RoleManager` from API 29: `isRoleAvailable`, `isRoleHeld`, and
  `createRequestRoleIntent` started through the host's `launchForResult`. Whatever the result code,
  the role is re-read; not held afterwards is a refusal. Android stops showing a role's dialog after
  the user refuses it twice, which is when the entry reads `PermanentlyDenied`. `openSettings` opens
  `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, then the app's details page. Android shows the dialog only to
  an app that qualifies for the role; otherwise it refuses at once, and that counts as a refusal.
- **`DeviceAdmin(component)`** — `DevicePolicyManager.isAdminActive(component)`, requested through
  `ACTION_ADD_DEVICE_ADMIN` with `EXTRA_DEVICE_ADMIN` and re-read afterwards. A device owner or a
  profile owner set up over ADB (`dpm set-device-owner`) is an active admin and reads `Granted`.
  `openSettings` opens `ACTION_SECURITY_SETTINGS`.

### Watching for changes

`observe(permission)` re-reads the status on every activity resume — which is where a change made in
system settings, or the system auto-resetting an unused app's permissions, becomes visible — and after
every request through the same controller. It emits only when the status actually changed, and
unregisters from the `ActivityAccess` when collection stops.

### The settings trip

`openSettings(permission)` hands a `SystemScreenLauncher` from `kmptoolkit-activity` one request
with kind `PermissionScreen(permission)` and its candidates, most specific first:

| Permission | Candidates |
|---|---|
| `Notifications` | `ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` (API 26+), then the app's details page |
| a special access | the table below |
| a role | `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, then the app's details page |
| a device admin | `ACTION_SECURITY_SETTINGS` |
| any other runtime entry | `ACTION_APPLICATION_DETAILS_SETTINGS` for this app |

The default launcher, `SystemScreenLauncher.callerTask(activityAccess)`, starts the first candidate the
device can show from the resumed activity, with no task flags, when called on the main thread: the
screen lands on your task and Back returns to your app. Off the main thread, with no activity resumed,
or from a `singleInstance` activity, it opens from the application context with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, in a task of its own:

- **Its own task, never a stale one.** `FLAG_ACTIVITY_NEW_DOCUMENT` reuses a task only when one is
  rooted at the same component with the same data: opening the same page again brings its existing
  task forward. Each different screen gets its own Recents card.
- **Two-pane Settings.** On large screens (AOSP 12L+) a Settings page started in a task of its own
  hands itself to the Settings homepage, whose task may be a stale one. Only a launch from your
  activity avoids it.
- **Lock-task (kiosk).** A page in a separate task needs the Settings package on your lock-task
  allowlist. A kiosk app passes `SystemScreenLauncher.SeparateTask` (or a launcher around it) — see
  [the guide](03-guide.md#a-kiosk-lock-task-app).
- **`true` is not "the user saw it".** Android has blocked activity starts from the background
  silently since API 29, and a start refused by lock-task mode also reports success.

The details are in
[`kmptoolkit-activity` — System screens and tasks](../kmptoolkit-activity/05-platform-notes.md#system-screens-and-tasks).

### The dialog host

The default `ActivityResultPermissionRequestHost` registers with the resumed `ComponentActivity`'s
`ActivityResultRegistry` under a key of its own, and registers again under the same key on every
activity that resumes while a request is pending. The registry keeps a launched request's key across
a configuration change and delivers the answer when something registers for it there, so a dialog
answered while the activity was being recreated still reaches the waiting request. Process death
ends the request: nothing in the new process is waiting, and nothing was recorded.

### `minSdk`

24, matching the rest of the toolkit.

## Special accesses

None of these need a manifest declaration to be *checked*. Actually being granted one still requires
your manifest to declare its permission where it has one — `SCHEDULE_EXACT_ALARM`,
`SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`, `PACKAGE_USAGE_STATS`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_NOTIFICATION_POLICY`, `MANAGE_MEDIA`,
`REQUEST_INSTALL_PACKAGES`, `USE_FULL_SCREEN_INTENT` — and this module never declares one for you.

| `AndroidPermission` | Android API | Settings screens, in the order tried |
|---|---|---|
| `ExactAlarm` | `AlarmManager.canScheduleExactAlarms()` (API 31+; `Granted` below) | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with `package:`, then without |
| `Overlay` | `Settings.canDrawOverlays()` | `ACTION_MANAGE_OVERLAY_PERMISSION` with `package:`, then without |
| `WriteSettings` | `Settings.System.canWrite()` | `ACTION_MANAGE_WRITE_SETTINGS` with `package:`, then without |
| `AllFilesAccess` | `Environment.isExternalStorageManager()` (API 30+; `Granted` below) | `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with `package:`, then `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` |
| `UsageStatsAccess` | `AppOpsManager` (`OPSTR_GET_USAGE_STATS`) | `ACTION_USAGE_ACCESS_SETTINGS` (with `EXTRA_APP_PACKAGE` from API 29) |
| `IgnoreBatteryOptimizations` | `PowerManager.isIgnoringBatteryOptimizations()` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` (a dialog), then `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |
| `NotificationListenerAccess` | `NotificationManagerCompat.getEnabledListenerPackages()` | `ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| `DoNotDisturbAccess` | `NotificationManager.isNotificationPolicyAccessGranted` | `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| `MediaManagement` | `MediaStore.canManageMedia()` (API 31+; `Unavailable` below) | `ACTION_REQUEST_MANAGE_MEDIA` with `package:` |
| `InstallPackages` | `PackageManager.canRequestPackageInstalls()` (API 26+; `Unavailable` below) | `ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:`, then without |
| `FullScreenNotifications` | `NotificationManager.canUseFullScreenIntent()` (API 34+); below it `Granted` when `USE_FULL_SCREEN_INTENT` is declared, `Unavailable` when not | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` with `package:` |
| `KeepPermissionsWhenUnused` | `PackageManager.isAutoRevokeWhitelisted` (API 30+; `Granted` below) | `Intent.ACTION_AUTO_REVOKE_PERMISSIONS` with `package:` |
| `AccessibilityService(component)` | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`; `Unavailable` for an unknown service | `ACTION_ACCESSIBILITY_SETTINGS` |

A platform call that throws — `isExternalStorageManager` is known to on some OEM builds — reads as not
granted, and is logged.

**Why the fallbacks.** Some OEM Settings apps drop a package-specific screen while keeping the list
it belongs to. Each entry that has both tries this app's own page first and then the generic list of
the same access, one tap away from the toggle. Only public platform actions are used, at the API
levels that define them; usage access, notification listeners, Do Not Disturb and accessibility have
one candidate because their action already is the list. `ExactAlarm` and `MediaManagement` below API
31, `AllFilesAccess` and `KeepPermissionsWhenUnused` below API 30, `InstallPackages` below API 26 and
`FullScreenNotifications` below API 34 have nothing to open: a request returns the status at once
without calling the launcher, and `openSettings` returns `false`.

`NotificationListenerAccess` additionally needs a manifest-declared listener `<service>` of your own,
bound with `BIND_NOTIFICATION_LISTENER_SERVICE` — this module only reports and redirects to the
toggle. `KeepPermissionsWhenUnused` needs no declaration; its switch is the one labelled "Pause app
activity if unused" or "Remove permissions if app is unused", and the access is granted while it is
off.

## iOS

### The one rule that shapes the API

**iOS shows its permission prompt at most once per install.** A refusal is final; only system
settings can change it. So the iOS controller never returns `Denied` for a refusal — it is
`PermanentlyDenied` immediately — with a single exception: `LocationBackground` while location is
"when in use", which iOS may still upgrade once (`Denied(explainFirst = true)`).

The practical consequence for your UI: if a permission needs explaining, explain it *before*
calling `request()`, while the status is still `NotDetermined`. See
[`03-guide.md`](03-guide.md#explain-before-asking-on-ios).

The deployment target is iOS 15. APIs introduced later — the iOS 17 EventKit access levels, the
iOS 18 limited contacts status — are reached behind a runtime OS check.

### What each entry maps to

| Entry | API | Fit |
|---|---|---|
| `Camera` | `AVCaptureDevice.authorizationStatus(for: .video)` + `requestAccess` | **Clean.** Restricted is `Restricted`. |
| `Microphone` | `AVAudioSession.recordPermission` + `requestRecordPermission` | **Clean.** No restricted state exists. |
| `Notifications` | `UNUserNotificationCenter.getNotificationSettings` + `requestAuthorization` with `IosPermissionOptions.notificationOptions` | **Asynchronous.** Provisional is `Limited`, and a request from it asks again without `Provisional`, which shows the full prompt; ephemeral (an App Clip) is `Granted`. `openSettings` opens the app's notification page on iOS 15.4+ (`UIApplicationOpenNotificationSettingsURLString`), the app's page below. |
| `Location` | `CLLocationManager.authorizationStatus` + `requestWhenInUseAuthorization` | **Folded.** "When in use" and "always" are granted; reduced accuracy is `Limited`. The answer arrives through the delegate, which `request` awaits. |
| `LocationBackground` | the same manager + `requestAlwaysAuthorization` | **Folded, with one inference.** Only "always" is granted (`Limited` with reduced accuracy); "when in use" is `Denied(explainFirst = true)`. iOS shows the upgrade prompt **at most once** and says nothing when it declines, so `request` waits for a changed status or — if the app did not resign active within a second — returns the status as it is. Once the upgrade was asked for in this process and the status stayed "when in use", the entry reads `PermanentlyDenied`. While location is not determined, a request asks for "when in use" first. |
| `AudioLibrary` | `MPMediaLibrary.authorizationStatus` + `requestAuthorization` | **Clean.** The user's music library. |
| `PhotoLibrary` | `PHPhotoLibrary.authorizationStatus(for: .readWrite)` + `requestAuthorization(for:)` | **Clean.** `.limited` is `Limited`; iOS does not prompt again from there — `presentLimitedPhotoLibraryPicker` lets the user change the selection. |
| `PhotoLibraryAdd` | the same, for `.addOnly` | **Clean.** |
| `Contacts` | `CNContactStore.authorizationStatus(for: .contacts)` + `requestAccess` | **Clean.** `.limited` (iOS 18+) is `Limited`. |
| `Calendar` | `EKEventStore.authorizationStatus(for: .event)` + `requestFullAccessToEvents` (iOS 17+) or `requestAccess(to: .event)` | **Folded.** Write-only access is `Limited`; a request from it asks for full access. |
| `CalendarWriteOnly` | the same status + `requestWriteOnlyAccessToEvents` (iOS 17+) or `requestAccess(to: .event)` | **Folded.** Write-only or full access is `Granted`. Below iOS 17 there is no write-only access: the request asks for, and grants, full access. |
| `MotionActivity` | `CMMotionActivityManager.authorizationStatus()`; the request is an empty activity query, which raises the prompt | **Clean, indirectly requested.** No motion hardware (`isActivityAvailable() == false`, every simulator) is `Unavailable`. |
| `BluetoothConnect`, `BluetoothScan`, `BluetoothAdvertise` | `CBManager.authorization` + a `CBCentralManager` created to raise the prompt | **One authorization.** iOS has no "request Bluetooth permission" call: the prompt appears when the app first creates a manager, which `request` does with the power alert turned off. All three entries report the same status, and a request for one answers all three. |
| `Tracking` | `ATTrackingManager.trackingAuthorizationStatus` + `requestTrackingAuthorization` | **Clean.** Shown only while the app is active; with "Allow Apps to Request to Track" off, a refusal without a prompt. |
| `SpeechRecognition` | `SFSpeechRecognizer.authorizationStatus()` + `requestAuthorization` | **Clean.** |
| `Reminders` | `EKEventStore.authorizationStatus(for: .reminder)` + `requestFullAccessToReminders` (iOS 17+) or `requestAccess(to: .reminder)` | **Clean.** |
| `FocusStatus` | `INFocusStatusCenter.default.authorizationStatus` + `requestAuthorization` | **Clean.** |

`openSettings` on iOS opens `UIApplicationOpenSettingsURLString` (or the notification page) on the
main queue, so `true` means "handed to UIKit" rather than "the settings screen is up". There is no
per-permission page.

## What is not in the vocabulary

- **HealthKit** authorizes per data type and deliberately hides whether read access was granted, so
  there is no single status to report honestly. It belongs in a module of its own.
- **iOS local network privacy** (`NSLocalNetworkUsageDescription`) has no status API — the prompt
  appears on the first local network traffic and the app is never told the answer. Android's
  `LocalNetwork` is modelled; iOS's is not.
- **A notification listener by component**, the default input method and HomeKit, NFC and Nearby
  Interaction are not modelled. `NotificationListenerAccess` covers "is any of this app's listeners
  enabled"; roles other than `Role.Home` are reached by name.
- **Exact alarms on iOS** — nothing to grant. Shared code that needs `AndroidPermission.ExactAlarm`
  takes it as a value; see [the guide](03-guide.md#platform-permissions-from-shared-code).

## Read next

- [`06-testing.md`](06-testing.md) — the fixture, and what to assert
- [`04-api-reference.md`](04-api-reference.md) — every public symbol

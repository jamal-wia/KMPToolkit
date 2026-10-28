# kmptoolkit-permission — API reference

Every public symbol, its contract, and its thread-safety. Package
`io.github.jamal_wia.kmptoolkit.permission` throughout.

## `Permission`

```kotlin
@SubclassOptInRequired(ToolkitInternalApi::class)
public interface Permission {
    public val id: String
}
```

Anything a `PermissionController` can check and request. The interface is open only because Kotlin
does not let a sealed hierarchy span the source sets of one multiplatform module; implementing it is
an opt-in to internal API (`ToolkitInternalApi`, from `kmptoolkit-core`), and a controller answers
`Unavailable` for any type it does not know. Exhaustive `when` is possible over each sealed
sub-hierarchy below, not over `Permission` itself.

`id` is stable across releases: the Android controller keys its stored flags by it, and every entry
that existed in 2.x keeps its 2.x enum name (`CAMERA`, `MEDIA_AUDIO`, `EXACT_ALARM`, …). It is also
yours to use for your own persisted data about a permission.

## `CommonPermission`

```kotlin
public sealed interface CommonPermission : Permission {
    data object Notifications        // id "NOTIFICATIONS"
    data object Microphone           // "MICROPHONE"
    data object Camera               // "CAMERA"
    data object Location             // "LOCATION"
    data object LocationBackground   // "LOCATION_BACKGROUND"
    data object AudioLibrary         // "MEDIA_AUDIO"
    data object BluetoothConnect     // "BLUETOOTH_CONNECT"
    data object BluetoothScan        // "BLUETOOTH_SCAN"
    data object BluetoothAdvertise   // "BLUETOOTH_ADVERTISE"
    data object PhotoLibrary         // "PHOTO_LIBRARY"
    data object PhotoLibraryAdd      // "PHOTO_LIBRARY_ADD"
    data object Contacts             // "CONTACTS"
    data object Calendar             // "CALENDAR"
    data object CalendarWriteOnly    // "CALENDAR_WRITE_ONLY"
    data object MotionActivity       // "MOTION_ACTIVITY"

    public companion object {
        public val entries: List<CommonPermission>
    }
}
```

The permissions both platforms have. Each entry's KDoc states what it maps to on each platform and how
the platform's states fold into `PermissionStatus`; the tables are in
[`05-platform-notes.md`](05-platform-notes.md). The set can grow in a minor release.

## `AndroidPermission` (Android only)

```kotlin
public sealed interface AndroidPermission : Permission {
    public sealed interface SpecialAccess : AndroidPermission

    // Special accesses — a toggle on a Settings screen
    data object ExactAlarm                 // "EXACT_ALARM"
    data object Overlay                    // "OVERLAY"
    data object WriteSettings              // "WRITE_SETTINGS"
    data object AllFilesAccess             // "ALL_FILES_ACCESS"
    data object UsageStatsAccess           // "USAGE_STATS_ACCESS"
    data object IgnoreBatteryOptimizations // "IGNORE_BATTERY_OPTIMIZATIONS"
    data object NotificationListenerAccess // "NOTIFICATION_LISTENER_ACCESS"
    data object DoNotDisturbAccess         // "DO_NOT_DISTURB_ACCESS"
    data object MediaManagement            // "MEDIA_MANAGEMENT"
    data object InstallPackages            // "INSTALL_UNKNOWN_APPS"
    data object FullScreenNotifications    // "FULL_SCREEN_INTENT"
    data object KeepPermissionsWhenUnused  // "KEEP_PERMISSIONS_WHEN_UNUSED"
    data class AccessibilityService(val component: ComponentName) // "ACCESSIBILITY_SERVICE:<component>"

    // A system screen answering with a result
    data class DeviceAdmin(val component: ComponentName)          // "DEVICE_ADMIN:<component>"
    data class Role(val name: String) {                           // id = name
        companion object { val Home: Role }                       // RoleManager.ROLE_HOME
    }

    // Android-only runtime permissions
    data object NearbyWifiDevices          // "NEARBY_WIFI_DEVICES"
    data object LocalNetwork               // "LOCAL_NETWORK"
    data object BodySensors                // "BODY_SENSORS"
    data object BodySensorsBackground      // "BODY_SENSORS_BACKGROUND"
    data class Runtime(val name: String)   // id = name

    public companion object {
        /** Every SpecialAccess that takes no parameter. */
        public val specialAccesses: List<SpecialAccess>
    }
}
```

- **`SpecialAccess`** — `request` opens its Settings screen through the `SystemScreenLauncher` and
  suspends until an activity of the app is resumed again, then re-reads the status. Not granted is
  `Denied(explainFirst = true)`. Below the API level that introduced it, an access the OS used to give
  every app is `Granted`; one that did not exist is `Unavailable`. Nothing is stored.
- **`AccessibilityService(component)`** — enabled when `component` is in
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`; a component the package manager does not know as a
  service is `Unavailable`. Opens the accessibility settings list.
- **`DeviceAdmin(component)`** — `DevicePolicyManager.isAdminActive`; a receiver the package manager
  does not know is `Unavailable`. `request` starts `ACTION_ADD_DEVICE_ADMIN` through the host's
  `launchForResult` and re-reads; a refusal is `Denied(explainFirst = true)`, never permanent.
  `openSettings` opens the security settings.
- **`Role(name)`** — `Unavailable` below API 29 or when `RoleManager.isRoleAvailable` is `false`;
  `Granted` while held. `request` starts `RoleManager.createRequestRoleIntent` through the host's
  `launchForResult`; a first refusal is `Denied(explainFirst = true)`, a second `PermanentlyDenied`.
  A blank `name` throws `IllegalArgumentException`. `openSettings` opens the default-apps screen.
- **`NearbyWifiDevices`** — `NEARBY_WIFI_DEVICES` from API 33; below it, `Location` in every respect.
- **`LocalNetwork`** — `ACCESS_LOCAL_NETWORK` on API 37+ for an app targeting 37+; `Granted` otherwise.
- **`BodySensors`** / **`BodySensorsBackground`** — `BODY_SENSORS` / `BODY_SENSORS_BACKGROUND` up to
  API 35, the Health Connect `READ_HEART_RATE` / `READ_HEALTH_DATA_IN_BACKGROUND` from API 36. The
  background entry is `BodySensors` below API 33 and is requested only once `BodySensors` is granted.
- **`Runtime(name)`** — by protection level on the device: dangerous gets a dialog and the full
  bookkeeping keyed by `name`; any other level is `Granted` when held and `Unavailable` otherwise,
  never prompting; unknown to the device is `Unavailable`. A blank `name` throws
  `IllegalArgumentException`.

## `IosPermission` (iOS only)

```kotlin
public sealed interface IosPermission : Permission {
    data object Tracking            // "TRACKING"
    data object SpeechRecognition   // "SPEECH_RECOGNITION"
    data object Reminders           // "REMINDERS"
    data object FocusStatus         // "FOCUS_STATUS"

    public companion object {
        public val entries: List<IosPermission>
    }
}
```

`ATTrackingManager`, `SFSpeechRecognizer`, EventKit reminders (full access from iOS 17) and
`INFocusStatusCenter`. Each needs its purpose string — see
[`05-platform-notes.md`](05-platform-notes.md#ios).

## `PermissionStatus`

```kotlin
public sealed interface PermissionStatus {
    public data object Granted : PermissionStatus
    public data object Limited : PermissionStatus
    public data class Denied(public val explainFirst: Boolean = false) : PermissionStatus
    public data object PermanentlyDenied : PermissionStatus
    public data object NotDetermined : PermissionStatus
    public data object Restricted : PermissionStatus
    public data object Unavailable : PermissionStatus
}

public val PermissionStatus.isGranted: Boolean
public val PermissionStatus.isUsable: Boolean
public val PermissionStatus.canPrompt: Boolean
```

| Case | Meaning | Reached on Android | Reached on iOS |
|---|---|---|---|
| `Granted` | usable now, in full | permission granted; an entry with no runtime grant on this API level; a special access, role or admin held | authorized; ephemeral notifications |
| `Limited` | usable, narrower than asked | approximate-only location; photos the user picked (API 34+) | reduced-accuracy location; limited photo library; limited contacts (iOS 18+); write-only calendar for `Calendar`; provisional notifications |
| `Denied(explainFirst = true)` | refused, the system UI can be shown again, explain first | after the first refusal through the dialog; a special access, role (refused once) or device admin not granted | "when in use" for `LocationBackground`, before the upgrade was asked |
| `Denied(explainFirst = false)` | refused, no explanation advised | asked before, but no activity to read the rationale from | never |
| `PermanentlyDenied` | asking shows nothing; only settings | the second refusal; a role refused twice; notifications switched off below API 33 | any refusal |
| `NotDetermined` | the next request shows the system UI, as far as the app can tell | never asked; a dismissed dialog; a permission refused in settings before the app ever asked | never asked |
| `Restricted` | withheld by something other than the user | revoked by a device policy | parental controls or an MDM profile |
| `Unavailable` | nothing to grant here | not on this API level or device; not declared in the manifest; `BluetoothScan` without `neverForLocation`; a type the controller does not know | no purpose string in `Info.plist`; no motion hardware; a type the controller does not know |

`isGranted` is `true` only for `Granted`; `isUsable` for `Granted` and `Limited`; `canPrompt` for
`NotDetermined` and `Denied`. The set can grow in a minor release.

## `PermissionController`

```kotlin
public interface PermissionController {
    public suspend fun check(permission: Permission): PermissionStatus
    public suspend fun request(permission: Permission): PermissionStatus
    public suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus>
    public fun observe(permission: Permission): Flow<PermissionStatus>
    public fun openSettings(permission: Permission): Boolean
}
```

- **`check`** shows no UI. It suspends on both platforms — iOS answers some authorizations
  asynchronously, and Android reads (and, for a granted permission with a stale flag, clears) its
  stored flags. Safe on every screen entry; `observe` instead of polling it.
- **`request`** shows the system UI — the dialog, the Settings screen, the role chooser — and returns
  the status once the user has answered it or come back from it. When the current status shows no
  UI (`Granted`, `PermanentlyDenied`, `Restricted`, `Unavailable`, and `Limited` where the platform has
  nothing to show) it returns that status at once.
- **`request(permissions)`** — one entry per distinct permission, in iteration order. On Android every
  runtime permission that can still prompt shares one system dialog; background location and
  background body sensors never join it. Everything else is requested in order, one at a time.
- **Requests are serialized.** A request made while another is in flight — through the same
  controller — waits for it, so two prompts never overlap.
- **`observe`** emits the current status on collection, then each *different* status: on every
  activity resume on Android, every time the app becomes active on iOS, and after every request
  through the same controller that covered the permission (or the entry it stands for on this API
  level). It never shows UI.
- **`openSettings`** opens the most specific system screen for `permission`: a special access's own
  page, the app's notification page for `Notifications`, the default-apps screen for a role, and
  otherwise the app's details page. `true` means a screen was handed to the system, not that the user
  saw it or changed anything. `false` for a type the controller does not know.
- **Nothing throws** for a platform failure — a missing activity, a launcher that cannot fire, a
  screen no app handles — it arrives as a status or as `false`, and is logged.
- **Cancellation.** Cancelling the coroutine that called `request` abandons the *result*, not the
  system UI: the user's answer still reaches the OS, nothing is recorded for an unanswered dialog, and
  the next `check` sees what happened.

## `PermissionRequestFlow`

```kotlin
public class PermissionRequestFlow(
    public val permission: Permission,
    controller: PermissionController,
) {
    public val state: StateFlow<PermissionFlowState>

    public suspend fun start(): PermissionFlowState
    public suspend fun rationaleAcknowledged(): PermissionFlowState
    public fun rationaleDismissed(): PermissionFlowState
    public fun openSettings(): Boolean
    public fun settingsDeclined(): PermissionFlowState
    public suspend fun refresh(): PermissionFlowState
    public fun reset(): PermissionFlowState
}
```

Construction asks the OS nothing; `state` starts at `Idle`. Each method returns the state it
reached, identical to `state.value` once it returns.

**The transition table** — every row is pinned by a test:

| From | Event | To |
|---|---|---|
| any but `Requesting` | `start()`, status `Granted` | `Granted` |
| any but `Requesting` | `start()`, status `Limited` | `Limited` |
| any but `Requesting` | `start()`, status `PermanentlyDenied` | `AwaitingSettings` |
| any but `Requesting` | `start()`, status `Restricted` or `Unavailable` | `Unavailable` |
| any but `Requesting` | `start()`, status `Denied(explainFirst = true)` | `AwaitingRationale` |
| any but `Requesting` | `start()`, status `Denied(explainFirst = false)` or `NotDetermined` | `Requesting`, then the request outcome |
| `Requesting` | request granted | `Granted` |
| `Requesting` | request limited | `Limited` |
| `Requesting` | request permanently denied | `AwaitingSettings` |
| `Requesting` | request restricted or unavailable | `Unavailable` |
| `Requesting` | request denied or unanswered | `Denied` |
| `AwaitingRationale` | `rationaleAcknowledged()` | `Requesting`, then the request outcome |
| `AwaitingRationale` | `rationaleDismissed()` | `Denied` |
| `AwaitingSettings` | `openSettings()` | `AwaitingSettings` (the app is backgrounded) |
| `AwaitingSettings` | `settingsDeclined()` | `Denied` |
| any but `Requesting` | `refresh()` | the OS's current view: `Granted` / `Limited` / `AwaitingSettings` / `Unavailable` / `AwaitingRationale` / `Idle` |
| any but `Requesting` | `reset()` | `Idle` |

Any method that does not apply to the current state is a **no-op returning the unchanged state**,
never an exception.

`openSettings()` returns `false` both when the flow is not in `AwaitingSettings` (nothing is opened)
and when the controller could not open a screen. It calls `controller.openSettings(permission)`.

**Not thread-safe.** Drive one flow from one coroutine. It holds no platform resource.

## `PermissionFlowState`

```kotlin
public sealed interface PermissionFlowState {
    public data object Idle : PermissionFlowState
    public data object Requesting : PermissionFlowState
    public data object AwaitingRationale : PermissionFlowState
    public data object AwaitingSettings : PermissionFlowState
    public data object Granted : PermissionFlowState
    public data object Limited : PermissionFlowState
    public data object Unavailable : PermissionFlowState
    public data object Denied : PermissionFlowState
}
```

`AwaitingRationale` and `AwaitingSettings` are questions asked of your UI. `Requesting` means the OS
owns the screen. The others are resting points. None carries text.

## Android factory

```kotlin
public fun createPermissionController(
    context: Context,
    storage: KeyValueStorage,
    activityAccess: ActivityAccess = createActivityAccess(context.applicationContext as Application),
    host: PermissionRequestHost = ActivityResultPermissionRequestHost(activityAccess),
    systemScreenLauncher: SystemScreenLauncher = SystemScreenLauncher.callerTask(activityAccess),
    config: AndroidPermissionConfig = AndroidPermissionConfig(),
    logger: Logger = NoopLogger,
): PermissionController
```

Every collaborator but `context` and `storage` has a default:

- **`context`** — only its application context is retained.
- **`storage`** — where the two flags per permission live; `createKeyValueStorage(context)` from
  `kmptoolkit-storage`. The flags are not secret.
- **`activityAccess`** — the resumed activity the rationale is asked of and screens are opened from.
  The default tracker must be created before the first activity resumes, so build the controller in
  `Application.onCreate` — or pass the `ActivityAccess` your app already owns (typically narrowed with
  `isTracked`).
- **`host`** — shows the permission dialog and the screens that answer with a result. The default
  needs no wiring; see `ActivityResultPermissionRequestHost` below.
- **`systemScreenLauncher`** — how a Settings screen opens. The default puts it on your task from the
  resumed activity when called on the main thread, and in a task of its own otherwise. A kiosk passes
  `SystemScreenLauncher.SeparateTask` or a launcher around it — see the
  [guide](03-guide.md#a-kiosk-lock-task-app).
- **`config`** — see `AndroidPermissionConfig`.
- **`logger`** — where an undeclared permission, a dialog that could not be shown, an unreadable flag
  and a launcher failure are reported.

### `AndroidPermissionConfig` (Android only)

```kotlin
public data class AndroidPermissionConfig(public val keyPrefix: String? = null)
```

The prefix of the stored flags' keys. `null` — the default — resolves at construction to
`"<your application id>.kmptoolkit.permission"`, the same as 2.x's `PermissionConfig`, so 2.x flags
are read unchanged. A blank prefix throws `IllegalArgumentException`.

### `PermissionScreen` (Android only)

```kotlin
public data class PermissionScreen(public val permission: Permission) : SystemScreenKind
```

The `SystemScreenKind` of every request the controller hands its `SystemScreenLauncher` — a special
access's screen, and every screen `openSettings` opens. Equal when it names the same permission, so a
launcher can match `PermissionScreen(AndroidPermission.Overlay)`, or any of them with
`is PermissionScreen`.

## `PermissionRequestHost` (Android only)

```kotlin
public interface PermissionRequestHost {
    public fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean
    public fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean
}
```

What shows the system UI. `launch` shows one permission dialog for every string in
`androidPermissions` (`RequestMultiplePermissions`) and reports each answer; an empty map means the
request was interrupted without an answer, and nothing is recorded. `launchForResult` starts `intent`
for a result (`StartActivityForResult`) and reports its result code — roles and device admins use it.

For both: return `false` if nothing could be shown, and then never call `onResult`; return `true`
and call `onResult` **exactly once**. The controller always calls the host on the main thread.

### `ActivityResultPermissionRequestHost` (Android only)

```kotlin
public class ActivityResultPermissionRequestHost(
    activityAccess: ActivityAccess,
    logger: Logger = NoopLogger,
) : PermissionRequestHost
```

The default host. It launches from the `ComponentActivity` `activityAccess` reports as resumed,
through that activity's `ActivityResultRegistry`, and re-registers on each activity that resumes
while a request is pending — so the answer reaches the waiting request even when the activity was
recreated meanwhile (not across process death). One request at a time: a launch while another is
pending returns `false`. A launch returns `false` with no resumed activity, or one that is not a
`ComponentActivity`. Holds no activity.

## iOS factory

```kotlin
public fun createPermissionController(
    options: IosPermissionOptions = IosPermissionOptions(),
    logger: Logger = NoopLogger,
): PermissionController
```

No context, no activity, no storage. Answers for every `CommonPermission` and `IosPermission`; any
other type is `Unavailable`. A permission whose `Info.plist` purpose string is missing is
`Unavailable`, a request shows nothing, and the missing key is logged once.

### `IosPermissionOptions` and `IosNotificationOption` (iOS only)

```kotlin
public data class IosPermissionOptions(
    public val notificationOptions: Set<IosNotificationOption> = setOf(Alert, Badge, Sound),
)

public enum class IosNotificationOption { Alert, Badge, Sound, Provisional, CriticalAlert }
```

What a `Notifications` request asks iOS for, as `UNAuthorizationOptions`. With `Provisional`, iOS
grants quiet delivery without a prompt and the status is `Limited`; a request from `Limited` leaves
`Provisional` out, so it shows the full prompt. `CriticalAlert` needs an entitlement from Apple. An
empty set throws `IllegalArgumentException`.

### `presentLimitedPhotoLibraryPicker` (iOS only)

```kotlin
public suspend fun presentLimitedPhotoLibraryPicker(from: UIViewController): Boolean
```

Presents the system's limited-library picker from `from`, so the user can change which photos a
`Limited` `PhotoLibrary` grant covers, and suspends until it is dismissed. `false`, showing nothing,
when the photo library is not limited.

## `kmptoolkit-permission-testing`

Package `io.github.jamal_wia.kmptoolkit.permission.testing`.

```kotlin
public class FakePermissionController(
    defaultStatus: PermissionStatus = PermissionStatus.NotDetermined,
) : PermissionController {
    public var defaultStatus: PermissionStatus
    public val checks: List<Permission>
    public val requests: List<Permission>
    public val batchRequests: List<List<Permission>>
    public val openedSettings: List<Permission>
    public var settingsAvailable: Boolean
    public var holdRequests: Boolean
    public fun setStatus(permission: Permission, status: PermissionStatus)
    public fun scriptRequest(permission: Permission, outcome: PermissionStatus)
    public fun clearRecordings()
}
```

See [`06-testing.md`](06-testing.md).

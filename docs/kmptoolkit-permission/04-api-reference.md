# kmptoolkit-permission — API reference

Every public symbol, its contract, and its thread-safety. Package
`io.github.jamal_wia.kmptoolkit.permission` throughout.

## `Permission`

```kotlin
public enum class Permission {
    NOTIFICATIONS, MICROPHONE, CAMERA,
    LOCATION, LOCATION_BACKGROUND, MEDIA_AUDIO, BLUETOOTH_CONNECT, // since 1.5.0
}
```

The closed catalog. Each entry maps to Android permission strings and an iOS authorization API, both
exercised by tests; the full mapping is in [`05-platform-notes.md`](05-platform-notes.md). The photo
library is deliberately absent — see [`01-overview.md`](01-overview.md#why-the-catalog-is-closed).
The catalog can grow in a minor release: prefer an `else` branch in a `when` over it.

## `PermissionStatus`

```kotlin
public sealed interface PermissionStatus {
    public data object Granted : PermissionStatus
    public data class Denied(public val shouldShowRationale: Boolean = false) : PermissionStatus
    public data object PermanentlyDenied : PermissionStatus
    public data object NotDetermined : PermissionStatus
}

public val PermissionStatus.isGranted: Boolean
public val PermissionStatus.canPrompt: Boolean
```

| Case | Meaning | Reached on Android | Reached on iOS |
|---|---|---|---|
| `Granted` | usable now | permission granted; also notifications below API 33 | authorized (also provisional/ephemeral notifications) |
| `Denied(shouldShowRationale = true)` | refused, another dialog is possible | after the first refusal | never |
| `Denied(shouldShowRationale = false)` | refused, no rationale asked for | rare; a device that reports no rationale mid-flow | never |
| `PermanentlyDenied` | no dialog will appear again | second refusal ("Don't allow" twice on Android 11+, "Don't ask again" before it); a dismissed dialog is not a refusal | any refusal; also restricted by MDM or parental controls |
| `NotDetermined` | the next request shows a dialog, as far as the app can tell | never asked; also a dialog the user dismissed, and — indistinguishably — a permission refused in settings before the app asked or missing from the manifest, for which the request returns at once | never asked |

`isGranted` is `true` only for `Granted`. `canPrompt` is `true` for `NotDetermined` and `Denied` —
the two cases where requesting would put a dialog on screen.

## `PermissionHandler`

```kotlin
public interface PermissionHandler {
    public suspend fun check(permission: Permission): PermissionStatus
    public suspend fun request(permission: Permission): PermissionStatus
    public fun openAppSettings(): Boolean
    public fun observe(permission: Permission): Flow<PermissionStatus> // since 1.5.0, has a default
}
```

- **`check`** shows no UI. Safe on every screen entry, in a loop, from a composition. It suspends
  only because iOS answers the notification question asynchronously; on Android it returns without
  suspending.
- **`request`** shows the system dialog and returns after the user answers. When the current status
  is `Granted` or `PermanentlyDenied` it shows nothing and returns that status, because the OS would
  show nothing either.
- **`openAppSettings`** opens the OS page for this app. `true` means the screen opened, not that the
  user changed anything; call `check`/`PermissionRequestFlow.refresh` on resume.
- **`observe`** emits the current status on collection, then each *different* status: on every
  activity resume on Android, every time the app becomes active on iOS, and after every `request`
  through the same handler. It never shows UI. The interface default emits the current status once
  and completes, so a handler written before 1.5.0 still compiles and answers once.
- **Nothing throws.** Every failure — a missing activity, a launcher that cannot fire, a settings
  screen no app handles — arrives as a status or as `false`.
- **One at a time.** Drive a handler from a single coroutine; two concurrent `request` calls are not
  serialized for you, and neither platform shows two dialogs at once.
- Cancelling the coroutine that called `request` abandons the *result*, not the dialog. The user's
  answer still reaches the OS; the next `check` sees it.

## `PermissionRequestFlow`

```kotlin
public class PermissionRequestFlow(
    public val permission: Permission,
    handler: PermissionHandler,
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
| any but `Requesting` | `start()`, status `PermanentlyDenied` | `AwaitingSettings` |
| any but `Requesting` | `start()`, status `Denied(rationale = true)` | `AwaitingRationale` |
| any but `Requesting` | `start()`, status `Denied(rationale = false)` or `NotDetermined` | `Requesting`, then the request outcome |
| `Requesting` | request granted | `Granted` |
| `Requesting` | request permanently denied | `AwaitingSettings` |
| `Requesting` | request denied or unanswered | `Denied` |
| `AwaitingRationale` | `rationaleAcknowledged()` | `Requesting`, then the request outcome |
| `AwaitingRationale` | `rationaleDismissed()` | `Denied` |
| `AwaitingSettings` | `openSettings()` | `AwaitingSettings` (the app is backgrounded) |
| `AwaitingSettings` | `settingsDeclined()` | `Denied` |
| any but `Requesting` | `refresh()` | `Granted` / `AwaitingSettings` / `AwaitingRationale` / `Idle`, per the OS |
| any but `Requesting` | `reset()` | `Idle` |

Any method that does not apply to the current state is a **no-op returning the unchanged state**,
never an exception — a permission flow is driven by taps and lifecycle callbacks, which arrive out
of order and twice.

`openSettings()` returns `false` both when the flow is not in `AwaitingSettings` (nothing is opened)
and when the platform could not open the settings screen.

`refresh()` restates the OS's view and therefore clears a `Denied` reached earlier in this flow;
see [`03-guide.md`](03-guide.md#the-lifecycle-refresh-is-not-optional).

**Not thread-safe.** Drive one flow from one coroutine. It holds no platform resource — there is
nothing to release, and building one per tap is as valid as holding one per screen.

## `PermissionFlowState`

```kotlin
public sealed interface PermissionFlowState {
    public data object Idle : PermissionFlowState
    public data object Requesting : PermissionFlowState
    public data object AwaitingRationale : PermissionFlowState
    public data object AwaitingSettings : PermissionFlowState
    public data object Granted : PermissionFlowState
    public data object Denied : PermissionFlowState
}
```

`AwaitingRationale` and `AwaitingSettings` are questions asked of your UI: the flow does not move
until you answer through the matching method. `Requesting` means the OS owns the screen. The other
three are resting points. None carries text.

## `PermissionConfig`

```kotlin
public data class PermissionConfig(public val keyPrefix: String? = null)
```

Where the Android handler's bookkeeping lives inside the `KeyValueStorage` you give it. `null` — the
default — resolves at construction to `"<your application id>.kmptoolkit.permission"`, so two apps
never share flags and neither has to name anything. A blank prefix throws
`IllegalArgumentException` at the call site.

Only Android stores anything; the iOS factory takes no config, because iOS reports "not determined"
itself. See [`05-platform-notes.md`](05-platform-notes.md#the-asked-flag).

## Android factory

```kotlin
public fun createPermissionHandler(
    context: Context,
    host: PermissionRequestHost,
    storage: KeyValueStorage,
    config: PermissionConfig = PermissionConfig(),
    logger: Logger = NoopLogger,
): PermissionHandler
```

`context`'s application context is what gets retained; no activity is held directly — the handler
tracks the currently resumed activity internally, used per call for
`shouldShowRequestPermissionRationale` and to launch settings from the foreground activity when
there is one. `storage` comes from `kmptoolkit-storage`'s `createKeyValueStorage(context)`. Create it
in `Application.onCreate`, like any activity tracker.

```kotlin
public fun createPermissionHandler(
    context: Context,
    host: PermissionRequestHost,
    storage: KeyValueStorage,
    activityAccess: ActivityAccess,
    config: PermissionConfig = PermissionConfig(),
    logger: Logger = NoopLogger,
): PermissionHandler
```

*Since 1.5.0.* The same handler over an `ActivityAccess` from `kmptoolkit-activity` that your app
already owns — typically one narrowed with `isTracked` to your own activities — instead of a tracker
of its own. The rationale is asked of, and settings are opened from, the activity that access
reports.

## `PermissionRequestHost` (Android only)

```kotlin
public interface PermissionRequestHost {
    public fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean

    // since 1.5.0, has a default
    public fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean
}
```

Your activity's `registerForActivityResult` plumbing. `onResult` must be called **exactly once**;
`launch` returns `false` if the dialog could not be shown at all, in which case `onResult` must
**not** be called and the handler leaves the status untouched rather than recording a refusal that
never happened. Pass `androidPermission` through verbatim — the handler picks the string, including
the API-level-dependent choices. A full example is in
[`02-getting-started.md`](02-getting-started.md).

The multi-permission `launch` shows one dialog for all of `androidPermissions`
(`RequestMultiplePermissions`) and reports each answer; the same exactly-once and `false` rules
apply. The handler uses it only for `Permission.LOCATION`. Its default forwards a one-element list to
the single `launch` and returns `false` for more, so an existing host keeps working for every other
permission.

## iOS factory

```kotlin
public fun createPermissionHandler(logger: Logger = NoopLogger): PermissionHandler
```

Nothing else is needed: no context, no activity, no storage.

## `SpecialPermission`

```kotlin
public enum class SpecialPermission {
    EXACT_ALARM, OVERLAY, WRITE_SETTINGS, ALL_FILES_ACCESS, USAGE_STATS_ACCESS,
    IGNORE_BATTERY_OPTIMIZATIONS, NOTIFICATION_LISTENER_ACCESS, DO_NOT_DISTURB_ACCESS,
}
```

An Android "special access" grant with no in-app dialog — see
[`03-guide.md`](03-guide.md#special-access-permissions) for what each maps to and why this is a
separate type from `Permission`. Every entry is a no-op on iOS.

## `SpecialPermissionHandler`

```kotlin
public interface SpecialPermissionHandler {
    public fun isGranted(permission: SpecialPermission): Boolean
    public fun requestViaSettings(permission: SpecialPermission): Boolean
}
```

| Member | Contract |
|---|---|
| `isGranted(permission)` | Whether `permission` is currently granted. Always `true` on iOS |
| `requestViaSettings(permission)` | Opens the system Settings screen for `permission`. No result callback — re-check `isGranted` on resume. Returns whether a screen was actually opened; always `false` on iOS |

### Factories

```kotlin
// Android
public fun createSpecialPermissionHandler(context: Context, logger: Logger = NoopLogger): SpecialPermissionHandler

// iOS
public fun createSpecialPermissionHandler(): SpecialPermissionHandler
```

The Android factory needs only a `Context` — every operation here is context-level, none of it
depends on an `Activity`.

## `kmptoolkit-permission-testing`

Package `io.github.jamal_wia.kmptoolkit.permission.testing`.

```kotlin
public class RecordingPermissionHandler(
    public var defaultStatus: PermissionStatus = PermissionStatus.NotDetermined,
) : PermissionHandler {
    public val checks: List<Permission>
    public val requests: List<Permission>
    public var openAppSettingsCount: Int          // private set
    public var settingsAvailable: Boolean
    public fun setStatus(permission: Permission, status: PermissionStatus)
    public fun scriptRequest(permission: Permission, outcome: PermissionStatus)
    public fun clearRecordings()
}
```

```kotlin
public class RecordingSpecialPermissionHandler(
    public var defaultGranted: Boolean = true,
) : SpecialPermissionHandler {
    public val checks: List<SpecialPermission>
    public val requestedViaSettings: List<SpecialPermission>
    public var defaultSettingsAvailable: Boolean
    public fun setGranted(permission: SpecialPermission, granted: Boolean)
    public fun setSettingsAvailable(permission: SpecialPermission, available: Boolean)
    public fun clearRecordings()
}
```

See [`06-testing.md`](06-testing.md).

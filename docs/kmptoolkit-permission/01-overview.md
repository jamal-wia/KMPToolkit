# kmptoolkit-permission — Overview

Two things: a `PermissionController` that answers *what does the OS think about this permission*
and asks for it, and a `PermissionRequestFlow` that turns those answers into the sequence a real
screen needs — explain, ask, or send the user to settings.

```kotlin
class RecorderPresenter(controller: PermissionController) {

    private val microphone = PermissionRequestFlow(CommonPermission.Microphone, controller)

    suspend fun onRecordTapped() {
        when (microphone.start()) {
            PermissionFlowState.Granted -> startRecording()
            PermissionFlowState.AwaitingRationale -> showYourOwnExplanation()
            PermissionFlowState.AwaitingSettings -> showYourOwnSettingsPrompt()
            else -> Unit
        }
    }
}
```

That call site is shared Kotlin. It does not know about `shouldShowRequestPermissionRationale`,
about `POST_NOTIFICATIONS` only becoming a runtime permission in API 33, about
`UNUserNotificationCenter` answering asynchronously, or about iOS never showing its dialog twice.

## One controller for everything a user grants

Android has three ways to grant an app something — the runtime permission dialog, a Settings screen
for a "special access" such as exact alarms, and a system dialog answering with a result, such as a
role. iOS has one authorization API per framework. 3.0 puts all of them behind the same five calls:

```kotlin
public interface PermissionController {
    public suspend fun check(permission: Permission): PermissionStatus
    public suspend fun request(permission: Permission): PermissionStatus
    public suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus>
    public fun observe(permission: Permission): Flow<PermissionStatus>
    public fun openSettings(permission: Permission): Boolean
}
```

A request suspends until the user has answered — the dialog closed, or the user came back from the
Settings screen it opened — and returns the status that answer produced. Requests are serialized, so
two features asking at once never put two prompts on screen.

## The vocabulary is split by where a permission exists

| Type | Where it is visible | What it holds |
|---|---|---|
| `CommonPermission` | `commonMain` | the 15 permissions both platforms have, under a name neither owns: notifications, microphone, camera, location (foreground and background), the audio library, photos (read, and add-only), contacts, calendar (full and write-only), motion activity, and Bluetooth (connect, scan, advertise) |
| `AndroidPermission` | `androidMain` | Android-only runtime permissions (nearby Wi-Fi devices, local network, body sensors), 13 special accesses, roles, device admins, and `Runtime(name)` for any other permission by its manifest name |
| `IosPermission` | `iosMain` | App Tracking Transparency, speech recognition, reminders, Focus status |

The split is enforced by the compiler: shared code cannot name `AndroidPermission.ExactAlarm`,
because there is nothing on iOS it could mean. Shared code that needs a platform permission takes a
`Permission` value that platform code hands it — see the [guide](03-guide.md#platform-permissions-from-shared-code).
All three implement the one open base type, `Permission`, which the controller and the flow take.

## The problem it solves

Requesting a permission is four lines of platform code and a week of edge cases. The edge cases are
the product:

- **Android cannot tell "never asked" from "permanently denied".** Both report the permission as not
  granted with `shouldShowRequestPermissionRationale() == false`, and a user who merely backed out
  of the dialog looks the same as both. This module remembers, in two flags per permission keyed by
  your own application id, whether it asked and whether the user ever refused.
- **iOS shows its dialog at most once per install.** A refusal there is already final, so "ask,
  explain, ask again" is meaningless — but the code asking is shared, so the difference has to live
  somewhere. It lives in `PermissionStatus`: iOS returns `PermanentlyDenied` where Android returns
  `Denied`.
- **A grant is not always all-or-nothing.** The user can grant approximate location instead of
  precise, pick a few photos or contacts instead of the whole library, or accept quiet (provisional)
  notifications. That is `PermissionStatus.Limited`: usable, narrower than asked.
- **Not every "no" is the user's.** A parental control or a device policy withholds a permission the
  user cannot grant (`Restricted`); a permission missing from your manifest, an OS that predates it,
  or a device without the hardware means there is nothing to grant (`Unavailable`). Telling the user
  to "go to settings" for either is a dead end.
- **The interesting outcomes get dropped.** Real code calls `request()`, gets a denial back, and
  does nothing with it. `PermissionRequestFlow` makes each outcome a state you have to render, or
  visibly choose not to.
- **The user can leave and come back.** Settings, a revocation, Android's auto-reset of permissions
  for unused apps — a permission granted when your screen opened may be gone when it resumes.
  `observe()` re-reads on every resume, and `refresh()` restates the flow from the OS.

## What this is **not**

- **Not a UI.** There is no dialog, no bottom sheet, no `@Composable` anywhere in this module — and
  no Compose dependency at all. It decides *that* a rationale should be shown; showing it is yours.
- **Not a rationale-copy provider.** It ships no strings, in any language. See
  [`../01-architecture.md`](../01-architecture.md#no-user-facing-text).
- **Not a settings screen.** `openSettings(permission)` hands the user to the most specific page the
  OS has for that permission — a special access's own toggle, the app's notification page, the
  default-apps list — and otherwise the app's details page. It cannot tell you what the user did
  there; `observe` and `refresh()` can.
- **Not a manifest.** It declares **no** Android permission of its own, deliberately and with a test
  asserting it against a real `PackageManager`. Every permission you request must be declared in
  *your* app's manifest and, on iOS, backed by an `Info.plist` purpose string. A permission you did
  not declare reads `Unavailable`, and the controller logs which entry is missing — on iOS instead of
  the OS terminating your app. See [`05-platform-notes.md`](05-platform-notes.md).
- **Not HealthKit, HomeKit or local-network privacy on iOS.** HealthKit authorizes per data type
  and hides read access, iOS local-network privacy has no status API, and neither fits a single
  status honestly. See [`05-platform-notes.md`](05-platform-notes.md#what-is-not-in-the-vocabulary).
- **Not a substitute for a picker.** To let the user pick one photo or one contact, the system
  pickers need no permission at all on either platform; ask for `PhotoLibrary` or `Contacts` only
  when the app reads the library itself.

## Why `CommonPermission` is closed

An entry joins `CommonPermission` only together with a contract that says how each platform's states
fold into `PermissionStatus` — written on the entry's KDoc and pinned by a test on both platforms.
That contract is the module's value, and it is why `CommonPermission` is a sealed type rather than a
string you construct. Where a raw string is genuinely what you need, Android has an honest escape
hatch, `AndroidPermission.Runtime(name)`, whose contract is stated by protection level rather than
per permission.

`CommonPermission`, `AndroidPermission`, `IosPermission` and `PermissionStatus` can all grow in a
minor release. That is binary-compatible, but an exhaustive `when` over one of them has to handle the
new case — prefer an `else` branch where you do not care about every entry.

## When to use it

Use it when the code that *knows* a permission is needed lives in shared Kotlin — a presenter, a
recorder's state machine, an onboarding step — and you want the decision about what the user sees
to be shared too, with only the words left to each platform. On Android alone it still earns its
keep the moment you need a special access, a role, or a batch of permissions in one dialog, because
all of them come back as the same `PermissionStatus`.

## Coming from 2.x

3.0 replaces 2.x's `Permission` enum, its handler, and its separate special-access enum and
handler with the types above. Stored flags carry over unchanged. See
[`07-migrating-to-3.md`](07-migrating-to-3.md).

## Read next

- [`02-getting-started.md`](02-getting-started.md) — wiring it up on both platforms
- [`03-guide.md`](03-guide.md) — the flow's states, batches, special access, roles, the lifecycle
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — manifest entries, `Info.plist`, what each entry maps to
- [`06-testing.md`](06-testing.md) — `FakePermissionController` and what to assert with it
- [`07-migrating-to-3.md`](07-migrating-to-3.md) — from 2.x

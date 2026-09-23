# kmptoolkit-permission — Overview

Two things: a `PermissionHandler` that answers *what does the OS think about this permission* and
shows the system dialog, and a `PermissionRequestFlow` that turns those answers into the sequence a
real screen needs — explain, ask, or send the user to settings.

```kotlin
class RecorderPresenter(handler: PermissionHandler) {

    private val microphone = PermissionRequestFlow(Permission.MICROPHONE, handler)

    suspend fun onRecordTapped() {
        when (microphone.start()) {
            PermissionFlowState.Granted -> startRecording()
            PermissionFlowState.AwaitingRationale -> showYourOwnExplanation()
            PermissionFlowState.AwaitingSettings -> showYourOwnSettingsPrompt()
            PermissionFlowState.Denied -> Unit
            else -> Unit
        }
    }
}
```

That call site is shared Kotlin. It does not know about `shouldShowRequestPermissionRationale`,
about the fact that `POST_NOTIFICATIONS` only became a runtime permission in API 33, about
`UNUserNotificationCenter` answering asynchronously, or about iOS never showing its dialog twice.

## The problem it solves

Requesting a permission is four lines of platform code and a week of edge cases. The edge cases are
the product:

- **Android cannot tell "never asked" from "permanently denied".** Both report the permission as not
  granted with `shouldShowRequestPermissionRationale() == false`. An app that does not remember
  which one it is either sends a first-run user to system settings for a permission it has never
  asked for, or leaves a permanently denied user tapping a button that will never do anything
  again — and a user who merely backed out of the dialog looks the same as both. This module
  remembers, in two flags per permission keyed by your own application id, whether it asked and
  whether the user ever refused.
- **iOS shows its dialog at most once per install.** A refusal there is already final, so the
  Android habit of "ask, explain, ask again" is meaningless — but the *code* asking is shared, so
  the difference has to live somewhere. It lives in `PermissionStatus`: iOS returns
  `PermanentlyDenied` where Android returns `Denied`.
- **The interesting outcomes get dropped.** Real code calls `request()`, gets a denial back, and
  does nothing with it. `PermissionRequestFlow` makes each outcome a state you have to render, or
  visibly choose not to.
- **The user can leave and come back.** Settings, a revocation, Android's auto-reset of permissions
  for unused apps — a permission granted when your screen opened may be gone when it resumes.
  `refresh()` is the one call that handles all of it.

## What this is **not**

- **Not a UI.** There is no dialog, no bottom sheet, no `@Composable` anywhere in this module — and
  no Compose dependency at all. It decides *that* a rationale should be shown; showing it is yours.
- **Not a rationale-copy provider.** It ships no strings, in any language. What "we need your
  microphone" should say — for your product, your tone, your locales — is not something a library
  can know, and a library that guessed would be wrong in every app that used it. See
  [`../01-architecture.md`](../01-architecture.md#no-user-facing-text).
- **Not a settings screen.** `openAppSettings()` hands the user to the OS's own page for your app.
  It cannot deep-link to a single permission toggle, it cannot tell you what the user did there, and
  it is not told when they come back. That is why `refresh()` exists.
- **Not a manifest.** It declares **no** Android permission of its own, deliberately and with a test
  asserting it against a real `PackageManager`. Every permission you request must be declared in
  *your* app's manifest and, on iOS, backed by an `Info.plist` usage string. See
  [`05-platform-notes.md`](05-platform-notes.md).
- **Not an open permission catalog.** `Permission` has eight entries — notifications, microphone,
  camera, location, background location, audio files, Bluetooth connection and Bluetooth scanning — and adding another is a change
  to this library, not a string you can pass in. See [the next section](#why-the-catalog-is-closed).
- **Not a multi-permission batch.** One flow drives one permission. The rationale you would show for
  the camera is not the one you would show for the microphone, and a batch would have to collapse
  two decisions into one answer.
- **Not thread-safe.** A handler and a flow are driven from one coroutine, normally the UI one,
  because neither platform will show two permission dialogs at once anyway.

## Why the catalog is closed

An open catalog — a `Permission` you construct from a raw Android string — is easy to build and
impossible to stand behind. This module's value is not the mapping table; it is the denial
bookkeeping and the state machine on top of it, and both give *wrong answers* for a permission whose
platform semantics do not fit `PermissionStatus`. The photo library is the clearest example: iOS's
`Limited` state — the user picked specific photos — is neither granted nor denied, and collapsing it
either way loses the one fact a photo picker cares about.

So an entry joins the enum only together with a contract that says how its platform distinctions
fold into the four cases. Location did, in 1.5.0: "while in use" is `LOCATION` granted, "always" is
`LOCATION_BACKGROUND` granted, and iOS's delegate-delivered answers are awaited inside `request`
rather than exposed. Bluetooth scanning did, in 1.8.0: below Android 12 its results need location, so
`BLUETOOTH_SCAN` *is* `LOCATION` there — status, dialog and remembered refusals — and from Android 12
it is `BLUETOOTH_SCAN` alone, declared with `neverForLocation`, rather than a fold of two permission
groups. Every mapping is exercised by a test. If you need a permission that is missing,
call the platform API in platform code — or ask for it here together with such a contract.

The enum can therefore grow in a minor release. That is binary-compatible, but an exhaustive `when`
over `Permission` in your code has to handle the new entry — prefer an `else` branch where you do not
care about every permission.

## Special access permissions

`SpecialPermission` and `SpecialPermissionHandler` are a second, deliberately separate mechanism for
a different category of Android grant: exact alarms, drawing over other apps, all-files storage
access, usage-stats access, battery-optimization exemption, notification-listener access, Do Not
Disturb access. None of these have an in-app request dialog — the user can only toggle them from a
dedicated system Settings screen — so they do not fit `PermissionHandler`'s check/request/rationale
shape at all: there is no "first refusal" to distinguish from a permanent one, only on or off.

```kotlin
val specialHandler: SpecialPermissionHandler = createSpecialPermissionHandler(context)

if (!specialHandler.isGranted(SpecialPermission.EXACT_ALARM)) {
    specialHandler.requestViaSettings(SpecialPermission.EXACT_ALARM)
    // no callback — re-check isGranted() when your screen resumes
}
```

Every entry is a no-op — always granted, nothing to open — on iOS, which has no equivalent concept
for any of them. See [`03-guide.md`](03-guide.md#special-access-permissions) and
[`04-api-reference.md`](04-api-reference.md#specialpermissionhandler).

## When to use it

Use it when the code that *knows* a permission is needed lives in shared Kotlin — a presenter, a
recorder's state machine, an onboarding step — and you want the decision about what the user sees
to be shared too, with only the words left to each platform.

If only your Android UI ever asks for a permission, use `registerForActivityResult` directly; the
indirection pays for itself once the decision is shared.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — wiring it up on both platforms
- [`03-guide.md`](03-guide.md) — the flow's states, the lifecycle, common mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — manifest entries, `Info.plist`, what maps and what does not
- [`06-testing.md`](06-testing.md) — `RecordingPermissionHandler` and what to assert with it

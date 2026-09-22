# kmptoolkit-biometric — Platform notes

## Android

### Permissions in your merged manifest

This module's own `AndroidManifest.xml` declares **nothing** — a repository-wide rule
([`docs/01-architecture.md`](../01-architecture.md#android-manifests)). But depending on it means
depending on `androidx.biometric`, whose manifest contributes three permissions that will appear in
your app:

| Permission        | Why it is there                                                        |
|-------------------|------------------------------------------------------------------------|
| `USE_BIOMETRIC`   | required by the framework's own `BiometricPrompt` from API 28 up       |
| `USE_FINGERPRINT` | its pre-28 predecessor, for the `minSdk 24`-to-27 range                |
| `REORDER_TASKS`   | used by the device-credential flow to bring the task back to the front |

All three are **install-time** permissions: they show up in your Play Store listing and app-info
screen, and none of them triggers a runtime prompt. This module never asks the user for a
permission.

They are deliberately **not** stripped by this module with `tools:node="remove"`. `androidx.biometric`
genuinely needs them; removing one trades a line in a listing for a `SecurityException` on somebody's
device. The module's `LibraryManifestTest` pins the set by name, so a new dependency — or an
`androidx.biometric` upgrade that starts asking for something new — fails the build rather than
appearing in your listing unannounced.

#### Keeping them out of a variant that never authenticates

An app with several build variants may authenticate in only one of them — a managed-device flavour,
say — while its public variant never builds a gate at all. The public listing then shows biometric
permissions for a feature it does not have. Strip them in the manifest of the variant that never
builds a gate, and **only** there:

```xml
<!-- src/main/AndroidManifest.xml — merged into every variant -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.USE_BIOMETRIC" tools:node="remove" />
    <uses-permission android:name="android.permission.USE_FINGERPRINT" tools:node="remove" />
</manifest>

<!-- src/managed/AndroidManifest.xml — the variant that does authenticate -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />
</manifest>
```

A removal marker applies to lower-priority manifests — libraries — while a flavour or build-type
manifest outranks `main`, so the managed variant's own declaration survives. Two rules keep this safe:

- **Never build a gate in a variant whose manifest lost the permission.** Bind a no-op implementation
  of `BiometricGate` there instead, so the code path that would need the permission does not exist.
- **Assert the merged manifest per variant** in a test, the same way this module asserts its own. The
  merge rules are easy to get wrong and silent when you do.

Leave `REORDER_TASKS` alone unless you are sure nothing else in the app needs it; it is not specific
to biometrics.

### Your activity must be a `FragmentActivity`

`androidx.biometric.BiometricPrompt` posts a fragment into the hosting activity's fragment manager.
`FragmentActivity` and its subclass `AppCompatActivity` qualify. `androidx.activity.ComponentActivity`
does **not**: it is `FragmentActivity`'s *superclass* and has no fragment manager — and it is what a
Compose project's `MainActivity` extends by default. A bare `android.app.Activity` does not qualify
either. Switching a Compose activity to `FragmentActivity` changes nothing else: `setContent`,
`enableEdgeToEdge` and every other `ComponentActivity` API are still there.

The module tracks the *currently resumed* activity internally and holds it weakly. When there is
none — the app is backgrounded, a configuration change is in flight — or when the resumed activity
is not a `FragmentActivity`, `authenticate` returns `BiometricResult.NoPromptHost` and nothing is
shown. It is deliberately not a `ClassCastException`, and deliberately not `Cancelled`.

**Create the gate before your activity resumes** — in `Application.onCreate`. The tracker learns which
activity is resumed from the resumed callback, and Android offers no way to ask afterwards. A gate
created lazily, on its first injection into a screen, is created after `MainActivity` resumed: it
answers `NoPromptHost` until the activity pauses and resumes again.

### Authenticator strength

`BIOMETRIC_ONLY` maps to `BIOMETRIC_STRONG` by default and never silently to `BIOMETRIC_WEAK`. The weak
tier includes sensors the platform will not let you gate a Keystore key with — typically camera-based
face unlock; accepting them silently would weaken what `Authenticated` claims. A device whose only
sensor is weak-tier therefore reports `NOT_ENROLLED` or `HARDWARE_UNAVAILABLE` rather than
authenticating.

Opt into the weak tier explicitly with `BiometricGateOptions(strength = BiometricStrength.WEAK)` — for
a supervised check on shared tablets whose only sensor is face unlock, where reaching the device
matters more than spoof resistance and nothing a Keystore key protects hangs on the result. Both
`availability()` and the prompt then ask for `BIOMETRIC_WEAK`. It cannot be combined with
`BIOMETRIC_OR_DEVICE_CREDENTIAL`; the factory throws `IllegalArgumentException`.

### One sensor attempt per call

By default the prompt stays up after an unrecognised biometric and the platform lets the user try
again, reporting `Rejected` only once it gives up — and after five failures on most devices it locks
the sensor, a lockout that only the device credential clears. With
`BiometricGateOptions(singleAttempt = true)` the first non-match ends the prompt: `authenticate`
returns `Rejected` and the sheet is dismissed. The cancellation that dismissal causes is not reported.
Use it when each attempt is an event your app counts and acts on itself. Under a policy that allows the
device credential, the first non-match dismisses the prompt before the user can switch to the PIN, so
`singleAttempt` belongs with `BIOMETRIC_ONLY`.

### Opening enrolment

`launchEnrollment()` hands one request to a `SystemScreenLauncher`, which starts the first screen the
device resolves:

| Situation | Candidates, in order |
|---|---|
| API 30+, nothing enrolled for the gate's tier | `Settings.ACTION_BIOMETRIC_ENROLL` with that tier |
| API 30+, already enrolled | `android.settings.COMBINED_BIOMETRICS_SETTINGS`, then the enrolment wizard |
| below API 30 | `Settings.ACTION_SECURITY_SETTINGS` |

The split exists because the enrolment wizard is *enrol-if-missing*: for a user who already has an
enrolment it finishes at once without showing anything. The management screen is not a documented
constant, hence the literal and the fallback. It also stays inside the Settings app, which matters
under lock-task mode, where only allowlisted packages can be started. Calls closer than
`BiometricGateOptions.enrollmentThrottle` (1 s by default) return `THROTTLED`, start nothing and never
reach the launcher.

#### Which task the screen lands in

Unless you pass a launcher of your own, the screen starts with `SystemScreenLauncher.SeparateTask`:
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, from the application context. Why both flags,
and what each preset does, is in
[`kmptoolkit-activity`'s platform notes](../kmptoolkit-activity/05-platform-notes.md#system-screens-and-tasks);
for enrolment specifically:

- **Never your task.** A settings screen can never end up at the root of your app's task — under
  lock-task mode, a settings record left there after a crash makes the task impossible to lock again.
- **Never a stale Settings task.** Up to 1.6.0 enrolment was started with `FLAG_ACTIVITY_NEW_TASK`
  alone, which reuses any background task with Settings' affinity. After the user had opened a
  Settings page from a deep link (a quick-settings long-press, say) and left it with Home, enrolment
  was pushed on top of that page, and Back — or the end of the wizard — landed on it instead of in
  your app. `FLAG_ACTIVITY_NEW_DOCUMENT` skips that lookup, so enrolment gets a fresh task.
  `FLAG_ACTIVITY_CLEAR_TASK` is deliberately not used: it would wipe the matched task, which on some
  API levels is the user's own Settings session opened from the launcher.
- **Two-pane Settings, the residual.** On a large screen, AOSP Settings (12L and later) hands a page
  started in a new task to its homepage (`DeepLinkHomepageActivity`, `singleTask`), whose task can
  be a stale one; flags cannot change that. If it matters to you and your app is not a kiosk, pass
  `SystemScreenLauncher.callerTask(activityAccess)` — the screen then opens in your task, in a single
  pane, and Back returns to your activity. OEM Settings apps implement large screens differently;
  check on the devices you ship to.
- **Lock-task (kiosk) apps** keep `SeparateTask` and allowlist `com.android.settings` only while the
  screen is open, from a launcher that matches `BiometricEnrollmentScreen` — the recipe is in
  [`03-guide.md`](03-guide.md#a-kiosk-lock-task-app). `callerTask` would put Settings inside the
  locked task, where the allowlist no longer confines it.

`LAUNCHED` means the screen was handed to the system, not that the user saw it: Android 14+ can
block a start from the background without telling the caller. Launch from a screen that is in the
foreground.

### The device credential and API 30

`BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is unsupported as an authenticator mask on API 28-29, and
`DEVICE_CREDENTIAL` alone is unsupported before API 30. `PromptInfo.Builder.build()` **throws** for
an unsupported combination rather than degrading — from inside a UI callback, on the API levels you
test least.

So `BIOMETRIC_OR_DEVICE_CREDENTIAL` takes two paths:

- **API 30+** — `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`.
- **API 24-29** — the deprecated `setDeviceCredentialAllowed(true)`, which is the only expression of
  the same intent at those levels.

Two consequences you can observe:

- **No negative button.** A credential-accepting prompt supplies its own, and `build()` rejects a
  prompt that sets both — hence `BiometricPromptText.cancelLabel` being documented as ignored in
  this mode.
- **`availability()` is pessimistic below API 30.** The availability query there asks about
  `BIOMETRIC_STRONG` alone, so a device with a PIN but no enrolled finger reports `NOT_ENROLLED`
  even though `authenticate` would succeed through the credential path. Below API 30, treat
  `NOT_ENROLLED` from a credential-accepting gate as "ask anyway" rather than "hide the feature".

### `canAuthenticate` status mapping

| `BiometricManager` status                    | `BiometricAvailability`                |
|----------------------------------------------|----------------------------------------|
| `BIOMETRIC_SUCCESS`                          | `Available`                            |
| `BIOMETRIC_ERROR_NO_HARDWARE`                | `Unavailable(NO_HARDWARE)`             |
| `BIOMETRIC_ERROR_HW_UNAVAILABLE`             | `Unavailable(HARDWARE_UNAVAILABLE)`    |
| `BIOMETRIC_ERROR_NONE_ENROLLED`              | `Unavailable(NOT_ENROLLED)`            |
| `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED`   | `Unavailable(SECURITY_UPDATE_REQUIRED)`|
| `BIOMETRIC_ERROR_UNSUPPORTED`                | `Unavailable(UNSUPPORTED)`             |
| `BIOMETRIC_STATUS_UNKNOWN`, anything unknown | `Unavailable(UNKNOWN)`                 |

A temporary lockout is **not** visible here — the platform reports it as `HW_UNAVAILABLE` at query
time. It only becomes `LOCKED_OUT` through an authentication attempt.

### `BiometricPrompt` error mapping

| `BiometricPrompt.ERROR_*`                      | `BiometricResult`                       |
|------------------------------------------------|-----------------------------------------|
| `USER_CANCELED`, `NEGATIVE_BUTTON`, `CANCELED` | `Cancelled`                             |
| `HW_NOT_PRESENT`                               | `Unavailable(NO_HARDWARE)`              |
| `HW_UNAVAILABLE`                               | `Unavailable(HARDWARE_UNAVAILABLE)`     |
| `NO_BIOMETRICS`, `NO_DEVICE_CREDENTIAL`        | `Unavailable(NOT_ENROLLED)`             |
| `LOCKOUT`                                      | `Unavailable(LOCKED_OUT)`               |
| `LOCKOUT_PERMANENT`                            | `Unavailable(PERMANENTLY_LOCKED_OUT)`   |
| `SECURITY_UPDATE_REQUIRED`                     | `Unavailable(SECURITY_UPDATE_REQUIRED)` |
| `TIMEOUT`, `UNABLE_TO_PROCESS`, `NO_SPACE`, `VENDOR`, anything unknown | `Failed(code)`   |

`ERROR_NEGATIVE_BUTTON` is a cancellation and not a "fallback requested": the button's label is
yours, so the library cannot know whether it said "Cancel" or "Use PIN", and guessing would put
words in your mouth.

`onAuthenticationFailed` — one unrecognised finger, sheet still up — produces **no** result. The
prompt keeps letting the user try; `Rejected` arrives only once the platform gives up — unless the gate
was built with `singleAttempt`, see above.

## iOS

### `NSFaceIDUsageDescription` is mandatory

Add it to your app's `Info.plist`, in your own words:

```xml
<key>NSFaceIDUsageDescription</key>
<string>Unlock your saved notes with Face ID.</string>
```

Without it, iOS **terminates the app** the first time a policy is evaluated on a Face ID device.
There is no error to catch and no `BiometricResult` to handle. Touch ID devices do not require it.

The library cannot supply it: it is user-facing copy, in your language, subject to App Store review.

### One reason string, no title

`LAContext` renders exactly one string of yours — `localizedReason`, which this module fills from
`BiometricPromptText.subtitle` — plus the cancel button's `localizedCancelTitle`. There is no title
slot; the OS shows your app's name. `BiometricPromptText.title` is therefore ignored on iOS, which
is why the subtitle should read as a complete explanation on its own.

### The fallback button

Under `BIOMETRIC_ONLY`, `localizedFallbackTitle` is set to an empty string, which hides the fallback
button. The library has no label from you to put on it, and the gate does not accept the passcode —
a button that can only end the prompt would be worse than none. If it appears anyway,
`LAErrorUserFallback` is reported as `Cancelled`.

Under `BIOMETRIC_OR_DEVICE_CREDENTIAL` the policy becomes `deviceOwnerAuthentication` and iOS
supplies and labels the passcode fallback itself.

### A fresh `LAContext` per call

Every `authenticate` allocates one. An `LAContext` caches its own successful evaluation, so reusing
one would let a later call succeed without asking the user anything. Cancelling the calling coroutine
calls `invalidate()`, which dismisses the sheet.

### `LAError` mapping

| `LAError`                                                   | Result                                    |
|-------------------------------------------------------------|-------------------------------------------|
| `UserCancel`, `UserFallback`, `SystemCancel`, `AppCancel`   | `Cancelled`                               |
| `AuthenticationFailed`                                      | `Rejected`                                |
| `BiometryNotEnrolled`, `PasscodeNotSet`                     | `Unavailable(NOT_ENROLLED)`               |
| `BiometryLockout`                                           | `Unavailable(PERMANENTLY_LOCKED_OUT)`     |
| `BiometryNotAvailable`                                      | see below                                 |
| `BiometryNotPaired`, `BiometryDisconnected`                 | `Unavailable(HARDWARE_UNAVAILABLE)`       |
| anything else                                               | `Failed(code)`                            |

Two mappings carry real judgement:

- **iOS has one lockout and it is the permanent kind.** `LAErrorBiometryLockout` clears only when the
  owner enters the device passcode — it never expires on its own. Reporting it as Android's
  transient `LOCKED_OUT` would tell your app to wait 30 seconds for something that never ends.
- **`LAErrorBiometryNotAvailable` means two different things.** No sensor, or a sensor the user has
  denied your app (the Face ID usage prompt) or that the OS has disabled. `LAContext.biometryType`
  separates them, and only after `canEvaluatePolicy` has run — so `availability()` reads it and
  reports `NO_HARDWARE` or `HARDWARE_UNAVAILABLE` accordingly. On the *authentication* path there is
  no such signal, so it is always the less committal `HARDWARE_UNAVAILABLE`.

### Targets

`androidTarget`, `iosArm64`, `iosSimulatorArm64`. No JVM/desktop target: the donor
implementation's desktop variant was a stub that reported "unavailable" for everything, which is
better expressed by not shipping the target.

## Simulators and emulators

- **iOS Simulator** — Features → Face ID/Touch ID → Enrolled, then "Matching Face" / "Non-matching
  Face" to drive success and rejection. Lockout is not reproducible.
- **Android emulator** — enrol a fingerprint in Settings, then `adb -e emu finger touch 1` to
  present it. `LOCKED_OUT` requires five consecutive failures and `PERMANENTLY_LOCKED_OUT` more than
  that; neither is convenient to reach on purpose.

Which is why the branches that matter are tested against `ScriptedBiometricGate` instead — see
[`06-testing.md`](06-testing.md).

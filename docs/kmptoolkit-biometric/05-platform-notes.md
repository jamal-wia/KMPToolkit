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

They are deliberately **not** stripped with `tools:node="remove"`. `androidx.biometric` genuinely
needs them; removing one trades a line in a listing for a `SecurityException` on somebody's device.
The module's `LibraryManifestTest` pins the set by name, so a new dependency — or an
`androidx.biometric` upgrade that starts asking for something new — fails the build rather than
appearing in your listing unannounced.

### Your activity must be a `FragmentActivity`

`androidx.biometric.BiometricPrompt` posts a fragment into the hosting activity's fragment manager.
`ComponentActivity` and `AppCompatActivity` both qualify; a bare `android.app.Activity` does not.

The module reaches an activity through `ActivityAccess` from `kmptoolkit-platform`, which hands out
the *currently resumed* one and holds it weakly. When there is none — the app is backgrounded, a
configuration change is in flight — or when the resumed activity is not a `FragmentActivity`,
`authenticate` returns `BiometricResult.NoPromptHost` and nothing is shown. It is deliberately not
a `ClassCastException`, and deliberately not `Cancelled`.

### Authenticator strength

`BIOMETRIC_ONLY` maps to `BIOMETRIC_STRONG` and never to `BIOMETRIC_WEAK`. The weak tier includes
sensors the platform will not let you gate a Keystore key with; accepting them silently would weaken
what `Authenticated` claims. A device whose only sensor is weak-tier therefore reports
`NOT_ENROLLED` or `HARDWARE_UNAVAILABLE` rather than authenticating.

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
prompt keeps letting the user try; `Rejected` arrives only once the platform gives up.

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

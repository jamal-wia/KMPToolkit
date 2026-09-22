# kmptoolkit-biometric — API reference

Package: `io.github.jamal_wia.kmptoolkit.biometric`

Everything public in the module. The `-testing` artifact's `ScriptedBiometricGate` is documented in
[`06-testing.md`](06-testing.md).

## `BiometricGate`

```kotlin
public interface BiometricGate {
    public suspend fun availability(): BiometricAvailability
    public suspend fun authenticate(prompt: BiometricPromptText): BiometricResult

    // since 1.5.0 — both have default bodies
    public suspend fun authenticate(prompt: BiometricPromptText, requireExplicitConfirmation: Boolean): BiometricResult
    public suspend fun launchEnrollment(): BiometricEnrollmentLaunch
}
```

The only type shared code should depend on.

**Contract:**

- Neither function throws for a platform condition — every device state and every user action comes
  back as a value. The only exception either raises is `CancellationException`.
- `authenticate` is cancellable: cancelling the calling coroutine dismisses the system prompt and
  produces **no** `BiometricResult`.
- `authenticate` does not pre-check `availability()`; it asks the OS, which is the only party that
  can answer at the moment the prompt would appear.
- Both are safe to call from any thread; implementations hop to the platform UI thread themselves.
- One prompt at a time. A second concurrent `authenticate` is undefined across platforms.
- It is a **gate, not a keystore** — see [`01-overview.md`](01-overview.md#what-this-is-not).

### `availability(): BiometricAvailability`

Whether the device can authenticate the owner **under this gate's configured policy**. A
credential-accepting gate can be `Available` where a biometric-only gate on the same device is not.

Use it to decide what UI to show. It is a snapshot, not a guard: see
[`03-guide.md`](03-guide.md#availability-is-for-deciding-what-to-show-not-for-guarding-the-call).

### `authenticate(prompt: BiometricPromptText): BiometricResult`

Shows the system prompt and suspends until the OS decides.

### `authenticate(prompt, requireExplicitConfirmation): BiometricResult`

As `authenticate(prompt)`, with the confirming tap for a passive biometric decided per call instead of
by `BiometricGateConfig.requireExplicitConfirmation`. Android only; iOS decides confirmation itself.
The interface default ignores the flag and calls `authenticate(prompt)`; a decorator should forward it.

### `launchEnrollment(): BiometricEnrollmentLaunch`

Opens the system screen to enrol a biometric, or to manage existing ones when something is enrolled.
`LAUNCHED`, `THROTTLED` (inside `BiometricGateOptions.enrollmentThrottle` of the previous launch) or
`UNAVAILABLE` (no such screen — always on iOS — or, on Android, the gate's `SystemScreenLauncher`
returned `false` or threw). The interface default returns `UNAVAILABLE`; a decorator should forward
it. On Android the screen opens through a `SystemScreenLauncher` — `SeparateTask` unless you passed
one to `createBiometricGateWithLauncher`; see [`Platform factories`](#platform-factories) and
[`05-platform-notes.md`](05-platform-notes.md#opening-enrolment).

## `BiometricGateOptions`

```kotlin
public class BiometricGateOptions(
    public val strength: BiometricStrength = BiometricStrength.STRONG,
    public val singleAttempt: Boolean = false,
    public val enrollmentThrottle: Duration = 1.seconds,
)

public enum class BiometricStrength { STRONG, WEAK }
public enum class BiometricEnrollmentLaunch { LAUNCHED, THROTTLED, UNAVAILABLE }
```

Since 1.5.0. A plain class with value equality, not a data class. All three fields are Android-only
and ignored on iOS.

| Field | Meaning |
|---|---|
| `strength` | `STRONG` = `BIOMETRIC_STRONG`; `WEAK` = `BIOMETRIC_WEAK`, only with `BIOMETRIC_ONLY` |
| `singleAttempt` | the first non-match ends the prompt with `Rejected` |
| `enrollmentThrottle` | minimum interval between two launches that start a screen; not negative; `ZERO` disables |

## `BiometricPromptText`

```kotlin
public data class BiometricPromptText(
    public val title: String,
    public val subtitle: String,
    public val cancelLabel: String,
)
```

The copy the OS renders. **No defaults, by design** — this library ships no wording, so there is no
string of ours a consumer can accidentally publish.

- `title` — Android headline; **ignored on iOS**, which shows the app's own name.
- `subtitle` — Android subtitle; on iOS it becomes `localizedReason`, the only string the OS
  renders. Write it to stand alone.
- `cancelLabel` — the dismiss button. **Ignored on Android under
  `BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL`**, where the system prompt supplies its own and
  `androidx.biometric` rejects a prompt that sets both.

**Throws `IllegalArgumentException`** if any field is blank — including whitespace-only, which is
what a missing localization key usually yields. The message names the offending parameter. `copy()`
re-validates.

## `BiometricGateConfig`

```kotlin
public data class BiometricGateConfig(
    public val policy: BiometricPolicy = BiometricPolicy.BIOMETRIC_ONLY,
    public val requireExplicitConfirmation: Boolean = true,
)
```

Fixed when the gate is built, not per call, so `availability()` and `authenticate()` always answer
for the same policy.

- `policy` — see [`BiometricPolicy`](#biometricpolicy). Defaults to the stricter option.
- `requireExplicitConfirmation` — whether a *passive* biometric (a face) needs a confirming tap
  before the prompt returns. Defaults to `true`, matching the platform default; without it, a phone
  held up to a sleeping user's face is enough. **Android only**; iOS decides this itself.

## `BiometricPolicy`

```kotlin
public enum class BiometricPolicy { BIOMETRIC_ONLY, BIOMETRIC_OR_DEVICE_CREDENTIAL }
```

- `BIOMETRIC_ONLY` — Android `BIOMETRIC_STRONG`, iOS `deviceOwnerAuthenticationWithBiometrics`. The
  weak Android tier is deliberately never accepted.
- `BIOMETRIC_OR_DEVICE_CREDENTIAL` — additionally the device PIN/pattern/passcode. Android
  `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (API 30+; the deprecated
  `setDeviceCredentialAllowed` flag below that), iOS `deviceOwnerAuthentication`. A weaker security
  claim with wider reach — see [`03-guide.md`](03-guide.md#the-device-credential-decision).

## `BiometricAvailability`

```kotlin
public sealed interface BiometricAvailability {
    public data object Available : BiometricAvailability
    public data class Unavailable(public val reason: BiometricUnavailability) : BiometricAvailability
}
```

## `BiometricResult`

```kotlin
public sealed interface BiometricResult {
    public data object Authenticated : BiometricResult
    public data object Cancelled : BiometricResult
    public data object Rejected : BiometricResult
    public data class Unavailable(public val reason: BiometricUnavailability) : BiometricResult
    public data object NoPromptHost : BiometricResult
    public data class Failed(public val platformCode: Int? = null) : BiometricResult
}
```

| Case            | Meaning                                                                                   |
|-----------------|-------------------------------------------------------------------------------------------|
| `Authenticated` | the OS confirmed the user. *How* is not reported — the policy already decided what counts. |
| `Cancelled`     | the sheet was dismissed: back gesture, negative button, system cancel, incoming call. Not an error. |
| `Rejected`      | a credential was presented and not recognised, enough times that the prompt gave up. A single unrecognised finger is **not** this — the platforms keep the sheet up and this module stays silent. |
| `Unavailable`   | the device cannot authenticate the owner; carries the same reasons `availability()` uses.  |
| `NoPromptHost`  | **Android only.** No resumed `FragmentActivity`, so nothing was shown. Distinct from `Cancelled` because the user never saw anything to cancel. |
| `Failed`        | timeout, vendor error, out-of-space, or a prompt the platform declined. Retryable. `platformCode` is the raw `BiometricPrompt.ERROR_*` (Android) or `LAError` (iOS) value, or `null` when the failure arose at this library's boundary. |

## `BiometricUnavailability`

```kotlin
public enum class BiometricUnavailability {
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NOT_ENROLLED,
    LOCKED_OUT,
    PERMANENTLY_LOCKED_OUT,
    SECURITY_UPDATE_REQUIRED,
    UNSUPPORTED,
    UNKNOWN,
}
```

| Reason                     | Permanent? | Meaning                                                                 |
|----------------------------|------------|-------------------------------------------------------------------------|
| `NO_HARDWARE`              | yes        | no sensor on this device                                                |
| `HARDWARE_UNAVAILABLE`     | no         | sensor busy, subsystem restarting, Face ID usage denied, accessory gone |
| `NOT_ENROLLED`             | user-fixable | no finger/face enrolled, and no passcode set for a credential gate    |
| `LOCKED_OUT`               | no         | too many failures; ~30-second cool-down                                 |
| `PERMANENTLY_LOCKED_OUT`   | user-fixable | clears only via the device credential                                 |
| `SECURITY_UPDATE_REQUIRED` | yes        | Android only; the OS distrusts its own sensor                           |
| `UNSUPPORTED`              | yes        | Android only; this API level cannot express the requested authenticators |
| `UNKNOWN`                  | —          | the OS declined to say, or an unrecognised platform code                |

### `BiometricUnavailability.isTransient: Boolean`

```kotlin
public val BiometricUnavailability.isTransient: Boolean
```

Whether waiting alone can clear the reason — i.e. whether "try again later" is honest. True for
`LOCKED_OUT` and `HARDWARE_UNAVAILABLE`, false for everything else including `UNKNOWN` (guessing
optimistically would build a retry loop that never terminates).

## Platform factories

```kotlin
// androidMain
public fun createBiometricGate(
    context: Context,
    config: BiometricGateConfig = BiometricGateConfig(),
): BiometricGate

public fun createBiometricGate(               // since 1.5.0
    context: Context,
    config: BiometricGateConfig,
    options: BiometricGateOptions,
): BiometricGate

public fun createBiometricGate(               // since 1.7.0
    context: Context,
    activityAccess: ActivityAccess,
    config: BiometricGateConfig = BiometricGateConfig(),
    options: BiometricGateOptions = BiometricGateOptions(),
): BiometricGate

public fun createBiometricGateWithLauncher(   // since 1.7.0
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    config: BiometricGateConfig = BiometricGateConfig(),
    options: BiometricGateOptions = BiometricGateOptions(),
    activityAccess: ActivityAccess? = null,
): BiometricGate

public object BiometricEnrollmentScreen : SystemScreenKind   // since 1.7.0

// iosMain
public fun createBiometricGate(
    config: BiometricGateConfig = BiometricGateConfig(),
): BiometricGate

public fun createBiometricGate(               // since 1.5.0; options ignored
    config: BiometricGateConfig,
    options: BiometricGateOptions,
): BiometricGate
```

Every factory taking `options` throws `IllegalArgumentException` for `BiometricStrength.WEAK`
with `BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL`.

Two signatures rather than one `expect fun`, per
[`docs/01-architecture.md`](../01-architecture.md#platform-factories-not-expect-fun): Android needs a
`Context` for `BiometricManager` and for tracking the activity that hosts the prompt fragment; iOS
needs neither.

- `context` — any `Context`; its application context is retained both for `BiometricManager` and to
  track the currently resumed activity. When no resumed `FragmentActivity` is available,
  `authenticate` returns `BiometricResult.NoPromptHost`.
- `activityAccess` — your app's activity tracker
  ([`kmptoolkit-activity`](../kmptoolkit-activity/04-api-reference.md)), which then hosts the prompt
  in place of a tracker the gate registers itself; its `isTracked` predicate is honoured, and the gate
  never releases it. It does not decide how enrolment opens. `null` on
  `createBiometricGateWithLauncher` (the default) means a tracker of the gate's own.
- `systemScreenLauncher` — how `launchEnrollment()` opens its screen; `SystemScreenLauncher` and
  `SystemScreenKind` are from [`kmptoolkit-activity`](../kmptoolkit-activity/04-api-reference.md),
  which this module exposes as an `api` dependency on Android. Every `createBiometricGate` overload
  uses `SystemScreenLauncher.SeparateTask` (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, from
  the application context). Each non-throttled `launchEnrollment()` calls it exactly once, on the
  calling thread, with one `SystemScreenRequest` of kind `BiometricEnrollmentScreen` carrying one or
  more candidates, most specific first, and no launch flags; a throttled call does not call it.
  `false` or any `Exception` it throws is reported as `BiometricEnrollmentLaunch.UNAVAILABLE`. For an
  ordinary (non-kiosk) app, `SystemScreenLauncher.callerTask(activityAccess)` opens the screen on your
  own task. See [`03-guide.md`](03-guide.md#how-the-screen-opens-on-android).
- `BiometricEnrollmentScreen` — the `kind` of that request: the enrolment wizard, the biometrics
  management screen, or the security settings below API 30. `toString()` is
  `"BiometricEnrollmentScreen"`.

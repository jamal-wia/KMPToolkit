# kmptoolkit-biometric — API reference

Package: `io.github.jamal_wia.kmptoolkit.biometric`

Everything public in the module. The `-testing` artifact's `ScriptedBiometricGate` is documented in
[`06-testing.md`](06-testing.md).

## `BiometricGate`

```kotlin
public interface BiometricGate {
    public suspend fun availability(): BiometricAvailability
    public suspend fun authenticate(prompt: BiometricPromptText): BiometricResult
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
    activityAccess: ActivityAccess,
    config: BiometricGateConfig = BiometricGateConfig(),
): BiometricGate

// iosMain
public fun createBiometricGate(
    config: BiometricGateConfig = BiometricGateConfig(),
): BiometricGate
```

Two signatures rather than one `expect fun`, per
[`docs/01-architecture.md`](../01-architecture.md#platform-factories-not-expect-fun): Android needs a
`Context` for `BiometricManager` and an `ActivityAccess` to host the prompt fragment; iOS needs
neither.

- `context` — any `Context`; only its application context is retained.
- `activityAccess` — from `kmptoolkit-platform`'s `createActivityTracker(application)`. When no
  resumed `FragmentActivity` is available, `authenticate` returns `BiometricResult.NoPromptHost`.

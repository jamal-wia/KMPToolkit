# kmptoolkit-biometric — Guide

## The words are yours

`BiometricPromptText` takes three strings and defaults none of them:

```kotlin
BiometricPromptText(
    title = strings.unlockTitle,
    subtitle = strings.unlockSubtitle,
    cancelLabel = strings.cancel,
)
```

This is deliberate, and it is the one piece of friction in the API that exists on purpose. The
operating system renders these strings verbatim, in a sheet you cannot restyle, at the moment your
user is deciding whether to trust your app. A library default — `"Authenticate"`, `"Cancel"` — would
be English, in your app's voice, shipped by someone who never saw your product. Defaults of that
kind survive to production precisely because nothing forces anyone to look at them.

So there is no string to fall back to. A blank one is refused at construction with an
`IllegalArgumentException` naming the parameter, because a missing localization key usually comes
back as `""` or `" "` and would otherwise reach the OS as a nameless prompt.

What each string does differs by platform, and neither is a translation of the other:

| Field         | Android                             | iOS                                                     |
|---------------|-------------------------------------|---------------------------------------------------------|
| `title`       | the prompt's headline               | **ignored** — the OS shows the app's own name           |
| `subtitle`    | the prompt's subtitle               | `LAContext.localizedReason`, the only string iOS renders |
| `cancelLabel` | the negative button                 | `LAContext.localizedCancelTitle`                        |

Two consequences worth designing around: **write `subtitle` so it stands alone**, because on iOS it
is the entire explanation; and expect `cancelLabel` to be ignored on Android when the gate accepts
the device credential (see below).

## The device-credential decision

`BiometricGateConfig.policy` chooses what counts as proof, and the two options make different
claims:

- **`BIOMETRIC_ONLY`** (the default) — a finger, a face or an iris, at Android's `BIOMETRIC_STRONG`
  tier. The claim is *this person's body*.
- **`BIOMETRIC_OR_DEVICE_CREDENTIAL`** — the above, or the PIN, pattern or passcode that unlocks the
  device. The claim is *someone who knows the device's unlock secret*.

The second is meaningfully weaker. It includes anyone who watched the user type their PIN on a bus,
and on a device already handed over unlocked it is often no barrier at all. Do not use it to gate
something you would not let a person holding the unlocked phone see.

It buys two things in return:

- **Reach.** It works on devices with no biometric hardware and on users who never enrolled — which,
  for a consumer app, is a larger fraction than most teams expect.
- **Recovery.** It is the only way out of `BiometricUnavailability.PERMANENTLY_LOCKED_OUT`: the
  credential prompt is exactly what clears a biometric lockout. A `BIOMETRIC_ONLY` gate on a
  locked-out device is stuck until the user unlocks their phone some other way.

The choice is explicit rather than an automatic fallback because a gate that quietly accepted the
PIN when the finger failed would be making that trade on your behalf, silently, at the worst moment.

If you want *both* — the strong claim normally, the credential as an escape hatch — build two gates
and pick between them yourself. They are stateless and cost nothing:

```kotlin
val strict: BiometricGate = createBiometricGate(context, activityAccess)
val recoverable: BiometricGate = createBiometricGate(
    context,
    activityAccess,
    BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
)

suspend fun unlock(text: BiometricPromptText): BiometricResult {
    val result: BiometricResult = strict.authenticate(text)
    val lockedOut: Boolean = result is BiometricResult.Unavailable &&
        result.reason == BiometricUnavailability.PERMANENTLY_LOCKED_OUT
    return if (lockedOut) recoverable.authenticate(text) else result
}
```

## Handling every outcome

`BiometricResult` is sealed so the compiler makes you decide. The useful default responses:

| Outcome                                 | What it means                                    | Reasonable response                                |
|-----------------------------------------|--------------------------------------------------|----------------------------------------------------|
| `Authenticated`                          | the OS vouched for the user                      | proceed                                            |
| `Cancelled`                              | the user (or the system) dismissed the sheet     | nothing — no error message                         |
| `Rejected`                               | credential presented and not recognised           | allow a retry; expect a lockout soon                |
| `Unavailable(LOCKED_OUT)`                | 30-second cool-down                              | your own fallback; retry later                     |
| `Unavailable(PERMANENTLY_LOCKED_OUT)`    | needs the device credential to clear             | your own fallback, or a credential-accepting gate  |
| `Unavailable(NOT_ENROLLED)`              | nothing enrolled                                 | offer a shortcut to system settings                |
| `Unavailable(NO_HARDWARE)`               | no sensor, ever                                  | hide the feature                                   |
| `Unavailable(HARDWARE_UNAVAILABLE)`      | sensor busy or biometry denied                   | fallback; it clears on its own                     |
| `NoPromptHost`                           | Android: nothing was shown                       | retry when your UI is back on screen               |
| `Failed(code)`                           | timeout, vendor error, platform refusal          | retryable; log the code                            |

`BiometricUnavailability.isTransient` answers the one question that decides your button: whether
"try again later" is honest. It is true for `LOCKED_OUT` and `HARDWARE_UNAVAILABLE` and false for
everything else — offering a retry for `NOT_ENROLLED` just walks the user into the same wall.

## Availability is for deciding what to show, not for guarding the call

```kotlin
// Right: decide what UI to render.
val showBiometricSwitch: Boolean = gate.availability() == BiometricAvailability.Available

// Wrong: a guard that is already stale.
if (gate.availability() == BiometricAvailability.Available) gate.authenticate(text)
```

The state can change between the two calls — the user enrols, unenrols, or locks themselves out —
and some conditions (a lockout in particular) are only discovered by trying. `authenticate` handles
every one of them and reports it as an `Unavailable` result, so the guard buys nothing and hides the
lockout. Re-query availability on screen resume instead of caching it from first composition.

## A grace period

The gate has no memory. "Do not ask again for five minutes" is app policy, and it is a decorator:

```kotlin
class GracePeriodGate(
    private val delegate: BiometricGate,
    private val window: Duration,
    private val now: () -> Instant,
) : BiometricGate {

    private var lastSuccess: Instant? = null

    override suspend fun availability(): BiometricAvailability = delegate.availability()

    override suspend fun authenticate(prompt: BiometricPromptText): BiometricResult {
        val last: Instant? = lastSuccess
        if (last != null && now() - last < window) return BiometricResult.Authenticated
        return delegate.authenticate(prompt).also { result ->
            if (result == BiometricResult.Authenticated) lastSuccess = now()
        }
    }
}
```

Note what this is: a UX affordance built on a value your own process holds. It is not a security
control, and neither is the gate underneath it — see
[`01-overview.md`](01-overview.md#what-this-is-not).

## Mistakes

- **Treating the return value as security.** `if (result == Authenticated) reveal(secret)` protects
  against a person holding the phone, not against an attacker with the phone and a debugger. Encrypt
  the secret; use the gate to decide when to decrypt it.
- **Caching `availability()` for the app's lifetime.** The user leaves to enrol a finger and comes
  back to a screen still telling them their device has no biometrics.
- **Retrying in a loop on `Rejected`.** Android escalates a repeated-failure device from
  `LOCKED_OUT` to `PERMANENTLY_LOCKED_OUT`; a retry loop drives users into a state only their PIN
  clears.
- **Showing an error for `Cancelled`.** The user said no. Nothing went wrong.
- **Calling `authenticate` from the background on Android.** You get `NoPromptHost`, not a prompt
  that appears later. Trigger it from a screen that is on-screen.
- **Two prompts at once.** Undefined across platforms. Serialize the calls.
- **Hardcoding the prompt copy at the call site.** It compiles, ships, and reaches a user in the
  wrong language. The strings are required parameters so that they come from your localization, not
  so they come from a literal two lines above.

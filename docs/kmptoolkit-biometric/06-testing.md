# kmptoolkit-biometric — Testing

## Why a fixture rather than a device

Almost every branch worth testing here is one a real device will not hand you on demand. An emulator
will not lock itself out for you, will not be simultaneously enrolled and unenrolled across two test
cases, and cannot be made to lack a sensor. Meanwhile the code you actually want to test is your
own: what your screen does when biometrics are unavailable.

So `kmptoolkit-biometric-testing` ships `ScriptedBiometricGate` — a `BiometricGate` that
authenticates nobody, records every prompt, and returns whatever you tell it to.

```kotlin
commonTest.dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-biometric-testing:<version>")
}
```

## `ScriptedBiometricGate`

```kotlin
public class ScriptedBiometricGate(
    public var availability: BiometricAvailability = BiometricAvailability.Available,
    public var resultFor: (prompt: BiometricPromptText, attempt: Int) -> BiometricResult =
        { _, _ -> BiometricResult.Authenticated },
) : BiometricGate {

    public val prompts: List<BiometricPromptText>
    public val availabilityChecks: Int
    public fun clear()
}
```

### Driving a single branch

```kotlin
@Test
fun `an unenrolled device offers to open system settings`() = runTest {
    val gate = ScriptedBiometricGate(
        availability = BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
    )

    assertEquals(LockState.OfferEnrolment, LockPresenter(gate).onOpen(strings))
}
```

Every `BiometricResult` and every `BiometricUnavailability` is reachable this way, including the
ones that are effectively untestable on hardware:

```kotlin
val gate = ScriptedBiometricGate(
    resultFor = { _, _ ->
        BiometricResult.Unavailable(BiometricUnavailability.PERMANENTLY_LOCKED_OUT)
    },
)
```

### Walking a whole flow

`resultFor` receives the 1-based attempt number, so one instance can play out a sequence:

```kotlin
@Test
fun `a lockout after a rejection switches to the password fallback`() = runTest {
    val gate = ScriptedBiometricGate(
        resultFor = { _, attempt ->
            when (attempt) {
                1 -> BiometricResult.Rejected
                else -> BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT)
            }
        },
    )
    val presenter = LockPresenter(gate)

    presenter.onUnlockPressed()
    presenter.onUnlockPressed()

    assertEquals(LockState.PasswordFallback, presenter.state)
}
```

`availability` is mutable for the same reason — a user who leaves for system settings and comes back
enrolled is an ordinary state transition, not two tests:

```kotlin
gate.availability = BiometricAvailability.Available
```

### Asserting your prompt copy

This is the assertion the library cannot make for you. It requires the three strings to be present
and non-blank; whether they came from your localization or from a literal typed at the call site is
something only your test can check:

```kotlin
@Test
fun `the unlock prompt uses localized copy`() = runTest {
    val gate = ScriptedBiometricGate()

    LockPresenter(gate, strings = russian).onUnlockPressed()

    assertEquals(russian.unlockTitle, gate.prompts.single().title)
}
```

**Prompts are recorded regardless of the scripted outcome** — the recording answers "did my code ask
for this, with these words?", not "did a sheet appear?" — so this works in the failure branches too.

`availabilityChecks` counts `availability()` calls, which is worth asserting when a screen is
supposed to re-query on resume rather than cache the answer from first composition.

`clear()` drops the recordings and resets the attempt counter, leaving `availability` and
`resultFor` alone: use it to separate an arrange phase that authenticated from the act phase you
want to assert.

**Not thread-safe**, deliberately. Drive it from one test coroutine.

## What the module tests itself

115 test functions across four source sets, derived from the contract in
[`01-overview.md`](01-overview.md) and [`04-api-reference.md`](04-api-reference.md) rather than from
the implementations. The donor module this was ported from had none, so every case here is new.

| Source set        | What it covers                                                                                                                              |
|-------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `commonTest`      | `BiometricPromptText` validation (empty, whitespace-only, per-parameter message, `copy` re-validation), `isTransient` pinned across the whole enum, config defaults |
| `androidUnitTest` | every `canAuthenticate` status and every `BiometricPrompt.ERROR_*` code; the gate's orchestration (authenticator mask, no-host, outcome pass-through, duplicate outcome, cancellation, no pre-check); `PromptInfo` at SDK 24, 29, 30 and 35; the merged-manifest permission set |
| `iosTest`         | every `LAError` code on both the availability and authentication paths, the `biometryType` split, and policy selection                        |
| `-testing`'s `commonTest` | the fixture's own contract, including that a scripted failure still records the prompt and that `clear()` does not un-script the gate  |

Two properties are asserted by sweeping rather than by example, because they are the failures that
would matter most and the ones a table of cases would not catch:

- **No error code, on either platform, ever maps to `Authenticated`.** A code falling through a
  `when` into success would be a silent authentication bypass; the sweep covers every value in a
  wide range.
- **No `canAuthenticate` status this version has never seen reads as `Available`.** A future Android
  adding a status must degrade to "unavailable", never to "go ahead".

The success path of the real Android port — actually showing a prompt — is not unit-testable:
Robolectric does not emulate the biometric service. What *is* tested there is the part that fails in
production for real reasons: no resumed activity, and an activity that is not a `FragmentActivity`.

## Running them

```bash
./gradlew :kmptoolkit-biometric:build :kmptoolkit-biometric-testing:build checkKotlinAbi
./gradlew :kmptoolkit-biometric:testDebugUnitTest :kmptoolkit-biometric:iosSimulatorArm64Test
./gradlew :kmptoolkit-biometric-testing:iosSimulatorArm64Test
```

`allTests` does **not** run the Android (Robolectric) unit tests — `testDebugUnitTest` is not
optional here, since the whole Android mapping table lives in it.

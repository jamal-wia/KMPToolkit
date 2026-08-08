# kmptoolkit-haptics — Testing

Haptics are the kind of thing that goes untested because "you have to feel it". You do not: the
decision *which* haptic to fire is ordinary logic, and that is the part worth testing.

## The fixture

`RecordingHapticFeedback` ships in a separate artifact so that nothing test-shaped ends up on your
app's runtime classpath ([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-haptics")
    testImplementation("io.github.jamal-wia:kmptoolkit-haptics-testing")
}
```

It works in `commonTest`, so one test covers both platforms.

## Asserting what your code fires

```kotlin
import io.github.jamal_wia.kmptoolkit.haptics.HapticType
import io.github.jamal_wia.kmptoolkit.haptics.testing.RecordingHapticFeedback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormPresenterTest {

    @Test
    fun `a rejected field buzzes once with the error pattern`() {
        val haptics = RecordingHapticFeedback()

        FormPresenter(haptics).submit(email = "not-an-email")

        assertEquals(listOf(HapticType.ERROR), haptics.events)
    }

    @Test
    fun `a valid submission does not buzz at all`() {
        val haptics = RecordingHapticFeedback()

        FormPresenter(haptics).submit(email = "user@example.com")

        assertTrue(haptics.events.isEmpty())
    }
}
```

`events` is an ordered list, so it answers three questions at once: *which* haptic, *how many*, and
*in what order*. Prefer `assertEquals(listOf(...), haptics.events)` over
`assertTrue(HapticType.ERROR in haptics.events)` — the weaker assertion passes when your code fires
four extra haptics along the way, which is exactly the bug you would want to catch.

## Asserting how your code copes when haptics fail

Set `result` and check that the surrounding flow still completes:

```kotlin
@Test
fun `submission still succeeds on a device with no vibrator`() {
    val haptics = RecordingHapticFeedback(result = HapticResult.UNAVAILABLE)

    val outcome: Outcome = FormPresenter(haptics).submit(email = "user@example.com")

    assertEquals(Outcome.Accepted, outcome)
}
```

A call is recorded even when `result` says it could not play — the recording answers "did my code
ask for this?", which is a different question from "did the motor run?".

`result` is a `var`, so one instance can change mid-test:

```kotlin
val haptics = RecordingHapticFeedback()
presenter.saveDraft()                            // records, PERFORMED
haptics.result = HapticResult.PERMISSION_DENIED
presenter.saveDraft()                            // records, PERMISSION_DENIED
```

Use `clear()` to separate arrange from act when your setup itself fires haptics.

## What the fixture will not do for you

- **It is not thread-safe.** The backing list is a plain `MutableList`. Drive it from one thread, or
  one test coroutine, and assert after the work under test has finished. If your code fires haptics
  from several coroutines at once, collect through your own synchronized wrapper — making the
  fixture concurrent would put an atomics dependency into an artifact whose value is being trivial.
- **It does not verify durations, amplitudes or patterns.** Those are the platform mapping, not your
  code's decision, and they are covered by this module's own tests.
- **It does not fake a platform.** `RecordingHapticFeedback` replaces `HapticFeedback` entirely; it
  never touches `Vibrator` or UIKit. That is why it runs on the JVM and on iOS with no device.

## Testing your own `HapticFeedback` implementation

If you wrote a decorator (a settings-aware wrapper, a rate limiter), test it by wrapping the
recording fixture — that gives you both the delegation check and the result pass-through:

```kotlin
@Test
fun `nothing is delegated while the user has haptics turned off`() {
    val delegate = RecordingHapticFeedback()
    val haptics = SettingsAwareHaptics(delegate, isEnabled = { false })

    val result: HapticResult = haptics.perform(HapticType.SUCCESS)

    assertTrue(delegate.events.isEmpty())
    assertEquals(HapticResult.UNAVAILABLE, result)
}
```

## How this module tests itself

Useful as a model, and as an answer to "is the Android mapping actually verified?":

| Suite | Where | What it pins |
|---|---|---|
| `NoOpHapticFeedbackTest`, `HapticContractTest` | `commonTest` (JVM + iOS) | The no-op contract and the declared shape of both enums |
| `RecordingHapticFeedbackTest` | `kmptoolkit-haptics-testing/commonTest` | The fixture's own contract — recording order, snapshot semantics, `clear` |
| `IosHapticFeedbackTest` | `iosTest` | Every type is accepted on iOS and reports `PERFORMED` |
| `AndroidHapticFeedbackTest` | `androidUnitTest`, no Robolectric | The decisions: no motor, denied permission, per-type mapping, nothing coalesced |
| `SystemVibratorPortTest` | `androidUnitTest`, Robolectric at SDK 24 / 30 / 34 | The framework calls on all three API branches, and a null vibrator service |

The split is deliberate: the framework seam (`VibratorPort`) exists so that "what should happen"
can be tested without an SDK sandbox, and only the thin translation layer needs Robolectric.

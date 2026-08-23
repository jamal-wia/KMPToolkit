# kmptoolkit-flashlight — Testing

The camera torch is the kind of thing that goes untested because "you have to watch it blink". You
do not: the decision *which* pattern to fire, and *when* to stop it, is ordinary logic, and that is
the part worth testing.

## The fixture

`RecordingFlashlight` ships in a separate artifact so that nothing test-shaped ends up on your
app's runtime classpath
([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-flashlight")
    testImplementation("io.github.jamal-wia:kmptoolkit-flashlight-testing")
}
```

It works in `commonTest`, so one test covers both platforms.

## Asserting what your code fires

```kotlin
import io.github.jamal_wia.kmptoolkit.flashlight.FlashPattern
import io.github.jamal_wia.kmptoolkit.flashlight.testing.RecordingFlashlight
import kotlin.test.Test
import kotlin.test.assertEquals

class IdleCuePresenterTest {

    @Test
    fun `going idle starts the blink pattern, and coming back stops it`() {
        val flashlight = RecordingFlashlight()
        val presenter = IdleCuePresenter(flashlight)

        presenter.onUserWentIdle()
        presenter.onUserBack()

        assertEquals(listOf(FlashPattern.Blink, null), flashlight.events)
    }
}
```

`events` is an ordered list of `FlashPattern?`, so it answers three questions at once: *which*
pattern, *how many starts and stops*, and *in what order* — a `null` entry is a `stop`. Prefer
`assertEquals(listOf(...), flashlight.events)` over asserting a single call is present; the weaker
assertion passes when your code fires an extra, unwanted `start` along the way.

## Asserting how your code copes with no torch

Set `isAvailable` and check that the surrounding flow still completes — the real implementations
go silent on a device with no flash unit, and `RecordingFlashlight` lets a test play that device
without one:

```kotlin
@Test
fun `the idle cue still fires on a device with no flash unit`() {
    val flashlight = RecordingFlashlight(isAvailable = false)

    IdleCuePresenter(flashlight).onUserWentIdle()

    // The call is still recorded — the fake answers "did my code ask for this?", not
    // "did the torch light up?" — but a caller that checks isAvailable first can also assert
    // the fallback it takes instead.
    assertEquals(listOf(FlashPattern.Blink), flashlight.events)
}
```

`isAvailable` is a `var`, so one instance can switch mid-test to simulate a torch becoming
unavailable partway through a scenario.

## Asserting the blink stopped

```kotlin
@Test
fun `coming back leaves nothing blinking`() {
    val flashlight = RecordingFlashlight()
    val presenter = IdleCuePresenter(flashlight)

    presenter.onUserWentIdle()
    presenter.onUserBack()

    assertFalse(flashlight.isBlinking)
}
```

`isBlinking` follows the last call, `start` or `stop` — useful when the assertion you care about is
"is anything still running", not the full event timeline.

## What the fixture will not do for you

- **It is not thread-safe.** The backing list is a plain `MutableList`. Drive it from one thread,
  or one test coroutine, and assert after the work under test has finished.
- **It does not verify timing.** Whether `Attention` really blinks at 120 ms on / 180 ms off is the
  platform mapping, not your code's decision, and is covered by this module's own
  `AndroidFlashlightTest` (Robolectric) and `IosFlashlightTest` suites.
- **It does not fake a platform.** `RecordingFlashlight` replaces `Flashlight` entirely; it never
  touches `CameraManager` or `AVCaptureDevice`. That is why it runs on the JVM and on iOS with no
  device.

## Testing your own `Flashlight` implementation

If you wrote a decorator (a settings-aware wrapper), test it by wrapping the recording fixture —
that gives you both the delegation check and the `isAvailable` pass-through:

```kotlin
@Test
fun `nothing is delegated while the user has the flash cue turned off`() {
    val delegate = RecordingFlashlight()
    val flashlight = SettingsAwareFlashlight(delegate, isEnabled = { false })

    flashlight.start(FlashPattern.Blink)

    assertTrue(delegate.events.isEmpty())
}
```

## How this module tests itself

Useful as a model, and as an answer to "is the platform mapping actually verified?":

| Suite | Where | What it pins |
|---|---|---|
| `NoOpFlashlightTest`, `FlashPatternContractTest` | `commonTest` (JVM + iOS) | The no-op contract and the declared shape of `FlashPattern` |
| `RecordingFlashlightTest` | `kmptoolkit-flashlight-testing/commonTest` | The fixture's own contract — recording order, snapshot semantics, `clear` |
| `AndroidFlashlightTest` | `androidUnitTest`, Robolectric's `ShadowCameraManager` | Hardware detection (flash present, absent, on a second camera, no camera at all) and that the torch always ends up off after `stop`, including after a re-arm |
| `IosFlashlightTest` | `iosTest` | The simulator's "no torch" contract: `isAvailable` reports `false`, and `start`/`stop`/re-arm all survive it — the same case the Robolectric suite covers on Android |

The split mirrors `kmptoolkit-haptics`'s: the platform-agnostic decision (which pattern, when to
stop) is tested without an SDK sandbox, and only the framework-facing half needs Robolectric or a
simulator.

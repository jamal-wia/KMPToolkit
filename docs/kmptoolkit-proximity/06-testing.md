# kmptoolkit-proximity — Testing

A proximity reading is exactly the kind of thing you cannot stage on an emulator on demand — there
is no virtual phone-to-ear gesture. The decision *what your code does* with a reading is ordinary
logic, and that is the part this fixture lets you test.

## The fixture

`FakeProximitySensor` ships in a separate artifact so nothing test-shaped ends up on your app's
runtime classpath ([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-proximity")
    testImplementation("io.github.jamal-wia:kmptoolkit-proximity-testing")
}
```

It works in `commonTest`, so one test covers both platforms.

## Asserting what your code does with a reading

```kotlin
import io.github.jamal_wia.kmptoolkit.proximity.testing.FakeProximitySensor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallPresenterTest {

    @Test
    fun `dims the screen once something is near`() = runTest {
        val proximity = FakeProximitySensor()

        proximity.emit(near = true)

        assertEquals(true, CallPresenter(proximity).observeScreenDimming().first())
    }

    @Test
    fun `does nothing on a device with no sensor`() = runTest {
        val proximity = FakeProximitySensor(isAvailable = false)

        assertEquals(emptyList(), CallPresenter(proximity).observeScreenDimming().toList())
    }
}
```

`emit` works before or after your code starts collecting — the fixture replays the most recent
value to a new collector, so you do not have to race a background coroutine against the test body to
land an emission before collection starts. See [What the fixture will not do for you](#what-the-fixture-will-not-do-for-you)
for what that convenience costs.

## Walking a call from unavailable to available

```kotlin
val proximity = FakeProximitySensor(isAvailable = false)
// ... assert your code's degraded behavior here ...

proximity.isAvailable = true
proximity.emit(near = true)
// ... assert your code's normal behavior here ...
```

`isAvailable` is a `var` for exactly this: a real device's answer to "do I have a working sensor"
does not change at runtime, but your code should not assume that — modeling the change is how you
prove it does not crash or wedge if it is wrong.

## What the fixture will not do for you

- **It is not a hardware simulation.** The real `ProximitySensor.observe()` is cold — the sensor is
  registered only while something collects, and a reading published with nothing collecting is lost.
  `FakeProximitySensor` instead replays the most recent `emit` to every new collector, so `emit`
  never races your test's collection. If your test genuinely needs to prove registration/release
  timing, that is a property of the real Android implementation, not of this fixture, and belongs in
  an `androidUnitTest` with Robolectric instead.
- **It does not hang on an absent sensor.** Collecting the real `observe()` on a device with no
  sensor never emits and never completes — correct, but unusable in a test that wants to assert
  "nothing came through" with something like `toList()`. `FakeProximitySensor.observe()` returns an
  already-completed empty `Flow` instead when `isAvailable` is `false`, so that assertion terminates.
- **It does not apply `ProximityRule` for you.** There is no raw-distance input to a fixture that
  only ever deals in the already-folded boolean — `ProximityRule.isNear` is pure and takes no
  fake at all; call it directly in a test if you are exercising it.
- **It is not thread-safe.** Drive it from one test coroutine and assert once the work under test has
  finished, the same caveat every fixture in this suite carries.

## How this module tests itself

Useful as a model, and as an answer to "is the Android sensor mapping actually verified?":

| Suite | Where | What it pins |
|---|---|---|
| `ProximityRuleTest` | `kmptoolkit-proximity/commonTest` (JVM + iOS) | The one subtle branch: comparing against the smaller of `NEAR_CM` and the sensor's own maximum |
| `AndroidProximitySensorTest` | `kmptoolkit-proximity/androidUnitTest`, Robolectric | `isAvailable`, including a zero-range sensor counting as absent |
| `IosProximitySensorTest` | `kmptoolkit-proximity/iosTest` | The documented always-absent contract |
| `FakeProximitySensorTest` | `kmptoolkit-proximity-testing/commonTest` | The fixture's own contract — replay, availability gating, `emitCount` |

The `SensorEventListener` registration and event-mapping wiring itself is not separately simulated
under Robolectric — the registration is thin plumbing, while the translation logic it drives
(`ProximityRule`) is pinned directly. What that wiring does with a `SensorEvent` is exactly
`ProximityRule.isNear`, already covered without any Android dependency at all.

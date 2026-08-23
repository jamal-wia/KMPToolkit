# kmptoolkit-accelerometer — Testing

Algorithms built on raw motion data are exactly the kind of logic that is easy to leave untested
because "you'd have to shake the device". You do not: `ScriptedAccelerometer` replays a canned
sequence of readings, so the algorithm under test sees deterministic input on every run.

## The fixture

`ScriptedAccelerometer` ships in a separate artifact so nothing test-shaped ends up on your app's
runtime classpath
([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-accelerometer")
    testImplementation("io.github.jamal-wia:kmptoolkit-accelerometer-testing")
}
```

It works in `commonTest`, so one test covers both platforms.

## Asserting on a scripted sequence

```kotlin
import io.github.jamal_wia.kmptoolkit.accelerometer.AccelerometerSample
import io.github.jamal_wia.kmptoolkit.accelerometer.testing.ScriptedAccelerometer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class PutDownDetectorTest {

    @Test
    fun `flips to face-down once the reading crosses the threshold`() = runTest {
        val faceUp = AccelerometerSample(x = 0f, y = 0f, z = 9.8f)
        val faceDown = AccelerometerSample(x = 0f, y = 0f, z = -9.8f)
        val accelerometer = ScriptedAccelerometer(samples = listOf(faceUp, faceDown))

        val readings = accelerometer.observe().take(2).toList()

        assertEquals(listOf(faceUp, faceDown), readings)
    }
}
```

`observe()` never completes on its own — that is the real contract, and the fixture keeps it — so
bound your collection with `take(n)`, or collect inside a coroutine you launch and cancel yourself.
A bare `.first()` works too, since it cancels upstream after one value; a bare `.toList()` with no
`take` hangs forever, exactly as it would against a real sensor.

## Asserting your code copes with a missing accelerometer

```kotlin
@Test
fun `shows the manual toggle when there is no accelerometer`() = runTest {
    val accelerometer = ScriptedAccelerometer(isAvailable = false)

    val screen = TiltScreen(accelerometer).load()

    assertTrue(screen.showsManualToggle)
}
```

`isAvailable` is a `var`, so a single instance can also model the (unrealistic, but sometimes
useful for a defensive-code test) case of a device that "grows" an accelerometer mid-test.

## Asserting your code releases the sensor

```kotlin
@Test
fun `stops collecting when the screen is torn down`() = runTest {
    val accelerometer = ScriptedAccelerometer()
    val job = launch { TiltPresenter(accelerometer).observeTilt().collect {} }
    advanceUntilIdle()
    assertEquals(1, accelerometer.activeCollectors)

    job.cancel()
    job.join()

    assertEquals(0, accelerometer.activeCollectors)
}
```

`registrations` counts every collection, active or not; `activeCollectors` only the ones still
running. A presenter that leaks a collection — starts one on every recomposition without cancelling
the last — shows up as `registrations` climbing while `activeCollectors` never drops, which is
exactly the failure mode that drains a real device's battery.

## What the fixture will not do for you

- **It is not thread-safe.** The backing counters are plain `Int`s. Drive it from one test
  coroutine and assert after the work under test has finished.
- **It does not model two concurrent collectors sharing one live stream.** Each collection of
  `observe()` gets its own replay of `samples` starting from the beginning, which is not how a real
  sensor behaves with two listeners registered at different times — see
  [`../01-architecture.md`](../01-architecture.md) and the fixture's own KDoc for the reasoning.
  Test one collector at a time.
- **It does not verify axis conventions, unit conversion, or sampling-interval handling.** Those
  are the platform mapping, not your code's decision, and are this module's own concern rather than
  something a fixture needs to re-prove.
- **It does not fake a platform.** `ScriptedAccelerometer` replaces `Accelerometer` entirely; it
  never touches `SensorManager` or Core Motion. That is why it runs on the JVM and on iOS with no
  device.

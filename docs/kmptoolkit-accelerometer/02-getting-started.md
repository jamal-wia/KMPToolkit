# kmptoolkit-accelerometer — Getting started

A minimal working example, start to finish. No manifest line is needed for the default sampling
rate — that is one thing this module does *not* ask you to remember.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-accelerometer")

    // Only if you want the ScriptedAccelerometer double (step 4)
    testImplementation("io.github.jamal-wia:kmptoolkit-accelerometer-testing")
}
```

The production module depends on nothing but `kotlinx-coroutines-core`. The test double lives in a
separate artifact so it never reaches your app's runtime classpath — see
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## 2. Depend on the interface in shared code

```kotlin
import io.github.jamal_wia.kmptoolkit.accelerometer.Accelerometer
import io.github.jamal_wia.kmptoolkit.accelerometer.AccelerometerSample
import kotlinx.coroutines.flow.Flow

class TiltPresenter(
    private val accelerometer: Accelerometer,
) {
    fun observeTilt(): Flow<AccelerometerSample> = accelerometer.observe()
}
```

`Accelerometer` lives in `commonMain`, so this class compiles on both targets and knows nothing
about how a reading is produced.

## 3. Build the real implementation in platform code

The factory is **not** an `expect`/`actual` pair, because Android needs a `Context` and iOS needs
nothing — an `expect fun` would have to pretend both platforms take the same parameters. Each
platform's factory lives in that platform's source set instead:

**Android** (`androidMain`, or your app's `Application`):

```kotlin
import io.github.jamal_wia.kmptoolkit.accelerometer.createAccelerometer

class MyApp : Application() {
    lateinit var accelerometer: Accelerometer

    override fun onCreate() {
        super.onCreate()
        accelerometer = createAccelerometer(context = this)
    }
}
```

**iOS** (`iosMain`, or wherever you assemble your object graph for iOS):

```kotlin
import io.github.jamal_wia.kmptoolkit.accelerometer.createAccelerometer

val accelerometer: Accelerometer = createAccelerometer()
```

Then hand the instance to your shared code: `TiltPresenter(accelerometer)`. There are no DI
bindings in this module and there never will be — wrap the factory in whatever container you
already use (see
[`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework)).

Both factories are cheap and hold nothing that needs releasing up front — the platform sensor is
registered per collection and released when that collection ends, so building one instance per app
and holding it is the obvious choice, but building one per screen is fine too.

## 4. Collect it — and check `isAvailable` first

```kotlin
if (accelerometer.isAvailable) {
    accelerometer.observe().collect { sample: AccelerometerSample ->
        println("x=${sample.x} y=${sample.y} z=${sample.z}")
    }
}
```

`observe()` is cold and never completes on its own: collecting starts the sensor, cancelling the
collecting coroutine stops it. On a device with no accelerometer it stays open and silent forever —
`isAvailable` is what tells you that in advance, not the absence of emissions.

## 5. Test it without a device

```kotlin
import io.github.jamal_wia.kmptoolkit.accelerometer.AccelerometerSample
import io.github.jamal_wia.kmptoolkit.accelerometer.testing.ScriptedAccelerometer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class TiltPresenterTest {

    @Test
    fun `emits the scripted readings in order`() = runTest {
        val faceUp = AccelerometerSample(x = 0f, y = 0f, z = 9.8f)
        val accelerometer = ScriptedAccelerometer(samples = listOf(faceUp))

        val readings = TiltPresenter(accelerometer).observeTilt().take(1).toList()

        assertEquals(listOf(faceUp), readings)
    }
}
```

That test runs on the JVM and on iOS, with no emulator and no simulator.

## Where to go next

- Choosing a sampling interval, and what changing it costs:
  [`03-guide.md`](03-guide.md#choosing-a-sampling-interval)
- Distinguishing "no accelerometer" from "nothing has moved yet":
  [`03-guide.md`](03-guide.md#reading-isavailable-correctly)
- The exact axis definitions and the sampling-rate permission on API 31+:
  [`05-platform-notes.md`](05-platform-notes.md)

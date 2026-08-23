# kmptoolkit-proximity — Getting started

A minimal working example, start to finish. Four steps — none of them a manifest line, for once.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-proximity")

    // Only if you want the FakeProximitySensor double (step 4)
    testImplementation("io.github.jamal-wia:kmptoolkit-proximity-testing")
}
```

The production module depends on nothing but `kotlinx-coroutines-core`. The test double lives in a
separate artifact so it never reaches your app's runtime classpath — see
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## 2. Depend on the interface in shared code

```kotlin
import io.github.jamal_wia.kmptoolkit.proximity.ProximitySensor
import kotlinx.coroutines.flow.Flow

class CallPresenter(private val proximity: ProximitySensor) {

    fun observeScreenDimming(): Flow<Boolean> = proximity.observe()
}
```

`ProximitySensor` lives in `commonMain`, so this class compiles on both targets and knows nothing
about `SensorManager` or the fact that iOS reports nothing at all.

## 3. Build the real implementation in platform code

The factory is **not** an `expect`/`actual` pair, because Android needs a `Context` and iOS needs
nothing — an `expect fun` would have to pretend both platforms take the same parameters. Each
platform's factory lives in that platform's source set instead:

**Android** (`androidMain`, or your app's `Application`):

```kotlin
import io.github.jamal_wia.kmptoolkit.proximity.createProximitySensor

class MyApp : Application() {
    lateinit var proximity: ProximitySensor

    override fun onCreate() {
        super.onCreate()
        proximity = createProximitySensor(context = this)
    }
}
```

**iOS** (`iosMain`, or wherever you assemble your object graph for iOS):

```kotlin
import io.github.jamal_wia.kmptoolkit.proximity.createProximitySensor

val proximity: ProximitySensor = createProximitySensor()
```

Then hand the instance to your shared code: `CallPresenter(proximity)`. There are no DI bindings in
this module and there never will be — wrap the factory in whatever container you already use (see
[`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework)).

Both instances are cheap to build and hold nothing that needs releasing at construction time —
`observe()` registers and unregisters its own listener per collection, so one instance per app is
the obvious choice, but building one per screen is not wrong either.

## 4. Test it without a device

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
}
```

That test runs on the JVM and on iOS, with no emulator and no simulator.

## Where to go next

- What a raw reading means and why a negative one is not proof of anything:
  [`03-guide.md`](03-guide.md#reacting-to-a-reading)
- Why iOS always reports absent, and what a tablet reports:
  [`05-platform-notes.md`](05-platform-notes.md)

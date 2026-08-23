# kmptoolkit-flashlight — Getting started

A minimal working example, start to finish. Four steps, and there is no manifest line to add — the
one that trips people up in `kmptoolkit-haptics`'s getting-started guide does not exist here.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-flashlight")

    // Only if you want the RecordingFlashlight double (step 4)
    testImplementation("io.github.jamal-wia:kmptoolkit-flashlight-testing")
}
```

The production module depends on nothing but the Kotlin standard library and
`kotlinx-coroutines-core`. The test double lives in a separate artifact so it never reaches your
app's runtime classpath — see
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## 2. Depend on the interface in shared code

```kotlin
import io.github.jamal_wia.kmptoolkit.flashlight.FlashPattern
import io.github.jamal_wia.kmptoolkit.flashlight.Flashlight

class IdleCuePresenter(
    private val flashlight: Flashlight,
) {
    fun onUserWentIdle() {
        flashlight.start(FlashPattern.Blink)
    }

    fun onUserBack() {
        flashlight.stop()
    }
}
```

`Flashlight` lives in `commonMain`, so this class compiles on both targets and knows nothing about
how the torch is driven.

## 3. Build the real implementation in platform code

The factory is **not** an `expect`/`actual` pair, because Android needs a `Context` and iOS needs
nothing — an `expect fun` would have to pretend both platforms take the same parameters. Each
platform's factory lives in that platform's source set instead:

**Android** (`androidMain`, or your app's `Application`):

```kotlin
import io.github.jamal_wia.kmptoolkit.flashlight.createFlashlight

class MyApp : Application() {
    lateinit var flashlight: Flashlight

    override fun onCreate() {
        super.onCreate()
        flashlight = createFlashlight(context = this)
    }
}
```

**iOS** (`iosMain`, or wherever you assemble your object graph for iOS):

```kotlin
import io.github.jamal_wia.kmptoolkit.flashlight.createFlashlight

val flashlight: Flashlight = createFlashlight()
```

Then hand the instance to your shared code: `IdleCuePresenter(flashlight)`. There are no DI
bindings in this module and there never will be — wrap the factory in whatever container you
already use (see
[`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework)).

Both instances hold nothing that needs releasing beyond stopping an in-flight blink, so building
one per screen is fine too — though one per app is the obvious choice.

## 4. Test it without a device

```kotlin
import io.github.jamal_wia.kmptoolkit.flashlight.FlashPattern
import io.github.jamal_wia.kmptoolkit.flashlight.testing.RecordingFlashlight
import kotlin.test.Test
import kotlin.test.assertEquals

class IdleCuePresenterTest {

    @Test
    fun `going idle starts the blink pattern`() {
        val flashlight = RecordingFlashlight()

        IdleCuePresenter(flashlight).onUserWentIdle()

        assertEquals(listOf(FlashPattern.Blink), flashlight.events)
    }
}
```

That test runs on the JVM and on iOS, with no emulator and no simulator.

## Where to go next

- Firing the cue only when the user wants it: [`03-guide.md`](03-guide.md#respecting-a-user-setting)
- What `isAvailable` actually tells you: [`03-guide.md`](03-guide.md#checking-availability)
- Why this needs no permission on either platform: [`05-platform-notes.md`](05-platform-notes.md)

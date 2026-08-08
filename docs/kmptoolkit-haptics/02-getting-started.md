# kmptoolkit-haptics — Getting started

A minimal working example, start to finish. Five steps, one of which is a manifest line you must
not skip.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-haptics")

    // Only if you want the RecordingHapticFeedback double (step 5)
    testImplementation("io.github.jamal-wia:kmptoolkit-haptics-testing")
}
```

The production module depends on nothing but the Kotlin standard library. The test double lives in
a separate artifact so it never reaches your app's runtime classpath — see
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## 2. Declare the Android permission — in *your* manifest

```xml
<manifest>
    <uses-permission android:name="android.permission.VIBRATE" />
</manifest>
```

This library declares no permission of its own, on purpose: a permission merged in from a library
appears in every consuming app silently, and the decision is yours to make. `VIBRATE` is a
*normal* permission — granted at install time, no runtime prompt, no Play Console justification.

Skip this line and nothing crashes: every call returns `HapticResult.PERMISSION_DENIED` instead.
Which is exactly why it is easy to skip by accident, so check it now rather than wondering later
why the device is quiet. iOS needs no counterpart — no entitlement, no `Info.plist` entry.

## 3. Depend on the interface in shared code

```kotlin
import io.github.jamal_wia.kmptoolkit.haptics.HapticFeedback
import io.github.jamal_wia.kmptoolkit.haptics.HapticType

class CheckoutPresenter(
    private val haptics: HapticFeedback,
) {
    fun onPaymentResult(succeeded: Boolean) {
        haptics.perform(if (succeeded) HapticType.SUCCESS else HapticType.ERROR)
    }
}
```

`HapticFeedback` lives in `commonMain`, so this class compiles on both targets and knows nothing
about how the buzz is produced.

## 4. Build the real implementation in platform code

The factory is **not** an `expect`/`actual` pair, because Android needs a `Context` and iOS needs
nothing — an `expect fun` would have to pretend both platforms take the same parameters. Each
platform's factory lives in that platform's source set instead:

**Android** (`androidMain`, or your app's `Application`):

```kotlin
import io.github.jamal_wia.kmptoolkit.haptics.createHapticFeedback

class MyApp : Application() {
    lateinit var haptics: HapticFeedback

    override fun onCreate() {
        super.onCreate()
        haptics = createHapticFeedback(context = this)
    }
}
```

**iOS** (`iosMain`, or wherever you assemble your object graph for iOS):

```kotlin
import io.github.jamal_wia.kmptoolkit.haptics.createHapticFeedback

val haptics: HapticFeedback = createHapticFeedback()
```

Then hand the instance to your shared code: `CheckoutPresenter(haptics)`. There are no DI bindings
in this module and there never will be — wrap the factory in whatever container you already use
(see [`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework)).

Both instances are cheap and hold nothing that needs releasing, so building one per screen is fine
too — though one per app is the obvious choice.

## 5. Test it without a device

```kotlin
import io.github.jamal_wia.kmptoolkit.haptics.HapticType
import io.github.jamal_wia.kmptoolkit.haptics.testing.RecordingHapticFeedback
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckoutPresenterTest {

    @Test
    fun `a failed payment buzzes with the error pattern`() {
        val haptics = RecordingHapticFeedback()

        CheckoutPresenter(haptics).onPaymentResult(succeeded = false)

        assertEquals(listOf(HapticType.ERROR), haptics.events)
    }
}
```

That test runs on the JVM and on iOS, with no emulator and no simulator.

## Where to go next

- Firing haptics only when the user wants them: [`03-guide.md`](03-guide.md#respecting-a-user-setting)
- What to do with the returned `HapticResult`: [`03-guide.md`](03-guide.md#reacting-to-the-result)
- Why the Android buzz feels different on an old device: [`05-platform-notes.md`](05-platform-notes.md)

# kmptoolkit-systembars — Testing

`SystemBarsController` needs no fixture of its own — see
[`04-api-reference.md`](04-api-reference.md#testing-fixtures) for the four-line fake. This page is
about the one part of the module that does ship one: `ScreenWakeLockController`.

## The fixture

`RecordingScreenWakeLockController` ships in a separate artifact so that nothing test-shaped ends up
on your app's runtime classpath ([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-systembars")
    testImplementation("io.github.jamal-wia:kmptoolkit-systembars-testing")
}
```

It works in `commonTest`, so one test covers every platform.

## Asserting when your code keeps the screen awake

```kotlin
import io.github.jamal_wia.kmptoolkit.systembars.testing.RecordingScreenWakeLockController
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingSessionPresenterTest {

    @Test
    fun `starting a recitation session keeps the screen on`() {
        val wakeLock = RecordingScreenWakeLockController()

        RecordingSessionPresenter(wakeLock).start()

        assertEquals(listOf(true), wakeLock.calls)
    }

    @Test
    fun `stopping the session releases the wake lock`() {
        val wakeLock = RecordingScreenWakeLockController()
        val presenter = RecordingSessionPresenter(wakeLock).apply { start() }

        presenter.stop()

        assertEquals(listOf(true, false), wakeLock.calls)
    }
}
```

`calls` is an ordered list of every value passed to `setKeepScreenOn`, so it also catches a
presenter that forgets to release the lock on teardown — the list ends in `true` instead of `false`.
`isKeptOn` is a shorthand for `calls.lastOrNull() ?: false` when a test only cares about the current
state, not the history that led there.

## What the fixture will not do for you

- **It is not thread-safe.** The backing list is a plain `MutableList`. Drive it from one thread, or
  one test coroutine, and assert after the work under test has finished.
- **It does not fake a platform.** `RecordingScreenWakeLockController` replaces
  `ScreenWakeLockController` entirely; it never touches `Window` or `UIApplication`. That is why it
  runs on the JVM and on iOS with no device.
- **It does not verify the real re-application-on-rotation behaviour.** That is `AndroidScreenWakeLockController`'s
  own contract, covered by `AndroidScreenWakeLockControllerTest` (Robolectric) in this module's own
  test suite — not something a fake can stand in for.

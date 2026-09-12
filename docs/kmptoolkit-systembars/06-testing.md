# kmptoolkit-systembars — Testing

Both of this module's controllers ship a double: `RecordingSystemBarsController` and
`RecordingScreenWakeLockController`.

## The fixtures

They ship in a separate artifact so that nothing test-shaped ends up on your app's runtime classpath
([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-systembars")
    testImplementation("io.github.jamal-wia:kmptoolkit-systembars-testing")
}
```

They work in `commonTest`, so one test covers every platform — this module publishes the same
targets as `kmptoolkit-systembars`, desktop included.

> `testImplementation` is the scope that matters here, and it is not negotiable: a `-testing`
> artifact on a runtime classpath is the exact thing the separate-artifact rule exists to prevent.
> The case that usually tempts people is a `@Preview` reaching a controller through a DI container —
> `@Preview` functions compile into your release source set, so they cannot see this module at all.
> Call `createHeadlessSystemBarsController()` from the main artifact there; it is in
> `kmptoolkit-systembars` precisely so that nobody has to hand-roll an empty controller or reach for
> this one. See [`04-api-reference.md`](04-api-reference.md) § "Common — headless".

## Asserting what a screen claims on the bars

```kotlin
import io.github.jamal_wia.kmptoolkit.systembars.testing.RecordingSystemBarsController
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoViewerPresenterTest {

    @Test
    fun `the viewer asks for light icons and gives them back on the way out`() {
        val controller = RecordingSystemBarsController()
        val presenter = PhotoViewerPresenter(controller)

        presenter.onEnter()
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        presenter.onLeave()
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
        assertEquals(0, controller.activeOverrideCount)
    }
}
```

`activeOverrideCount` back at zero is the assertion worth making in every screen teardown test: a
screen that leaks a layer looks fine on its own and breaks the *next* screen, which is the whole
class of bug this module exists to remove.

`applied` is an ordered list of every configuration that would have reached a window, and it records
nothing for a mutation that left the effective configuration unchanged — so it also catches a screen
that thrashes the bars on every recomposition.

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

## What the fixtures will not do for you

- **They are not thread-safe.** The backing lists are plain `MutableList`s, and
  `RecordingSystemBarsController` does not reproduce the real controller's compare-and-set retry
  loop — a fixture with its own concurrency bugs would prove nothing. Drive them from one thread, or
  one test coroutine, and assert after the work under test has finished.
- **They do not fake a platform.** They replace their interfaces entirely and never touch `Window`,
  `UIApplication` or a `UIViewController`. That is why they run on the JVM and on iOS with no device
  — and why they cannot tell you whether the bars *looked* right.
- **They do not verify platform-specific behaviour.** The real re-application on activity recreation
  belongs to `AndroidScreenWakeLockController` and `createSystemBarsController`, covered by
  Robolectric tests in this module's own suite, and the iOS status-bar and home-indicator pull
  belongs to `IosSystemBarsController`, covered by its `iosTest` suite. No fake stands in for those.

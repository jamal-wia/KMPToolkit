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
that thrashes the bars on every recomposition. After `release()` nothing more is recorded, as the real
controller never touches the window again, while `config` keeps following writes as it does there.

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

## How this module tests `AutoSystemBarsIconStyle`

The composable reads real pixels, so it is not something a consumer fakes (see
[`04-api-reference.md`](04-api-reference.md)). Its own suite splits the job by what each layer can
prove:

- **The sampling loop runs against a scriptable fake reader.**
  `AutoSystemBarsIconStyleReaderContractTest` (`androidHostTest`) drives the loop through the
  composable's internal overload with a reader that returns styles, returns `null`, throws, or
  suspends until told otherwise, and counts its calls. It pins the scheduling, the skips, the
  release after three consecutive attempts that read nothing, cancellation (a read parked forever
  while the host drops below `STARTED` or the composable leaves), a late completion after
  cancellation, and that reads never overlap. `AutoSystemBarsIconStyleLifecycleTest` keeps every
  assertion and takes the same kind of fake in its setup only; the churn, fuzz, insets and
  zero-interval probe classes run as before. The fake
  asserts that `onSampled` fires after a suspended read finished, never while it is parked; it does
  not assert a duration lower bound, because the production clock is the wall clock and cannot be
  injected.
- **The pure parts are plain functions with their own tests**: `BarStripRowsTest` (the geometry of
  the window copy, including a seeded sweep against an independent oracle), `SampleRowsTest`,
  `ScratchRowSourcesTest`, `LuminanceToStyleTest`, `PublishStylesTest`, `IdleTickTest`,
  `PeriodicSampleGateTest` and `SerialSamplerTest`.
- **One Robolectric test runs the real Android reader.** `PixelCopyBarStripReaderTest` goes through
  `ShadowPixelCopy` under `@GraphicsMode(NATIVE)`: a white top half and a black bottom half, with
  both insets injected, must give dark status icons and light navigation icons. It is pixel-true,
  not narrowed to plumbing. `PixelCopyBarStripReaderPlumbingTest` records every request through a
  copier double: the source rectangle (the whole decor), the `8 × R` destination, the main-looper
  handler, a fresh bitmap and rectangle per request, what every `PixelCopy` result code and an
  `IllegalArgumentException` become, a listener that fires after its caller was cancelled, the
  early exits (no window, a view that is not the window's root, a decor that was never laid out),
  and the window lookup.
- **A per-SDK test pins which reader each API level uses.** `BarStripReaderSelectionTest` (sdk 24,
  25, 26 and 35) is the CI guard against "simplifying" Android back to the layer readback. It
  cannot prove that a `WebView` stays intact.
- **The API 24-25 `WebView` guard has its own class.** `WebViewGuardTest` (sdk 24) puts a real
  `AndroidView(WebView)` in the probe (Robolectric's `ShadowWebView` builds one) around a counting
  inner reader, so it proves the inner reader is never asked, and checks the attached, nested,
  subclassed and detached cases of the walk.
- **Desktop** has a case in `DesktopNoOpsTest` (`jvmTest`): there is no reader, the content composes
  and nothing is claimed. iOS has no test of its own for this: its reader is the layer reader (the
  pre-6.0.0 sampler, moved unchanged), covered only through the common tests of its pure parts, and
  no `iosTest` class composes the probe.

What only a device shows, and what no test here proves: a `WebView` staying intact under the probe,
`backdrop-filter`, idle frame counts, `FLAG_SECURE`, the latency of a triggered sample, the cost of
a real `PixelCopy`, and luma agreement on real content. The tests prove code paths through a shadow
and fakes, nothing about a real `PixelCopy` or a real Chromium; RELEASING.md's "Systembars device
check" lists those rows, and the platform notes list them as
[not observed](05-platform-notes.md#what-has-not-been-observed-on-a-device). Also uncovered: the
guard's own draw hook delegating to the layer reader, a reader replaced while a read is in flight,
and a second real window (a `Popup` or a `Dialog`) through the real shadow; the dialog-window rule
of the window lookup is tested with a fake parent view only.

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

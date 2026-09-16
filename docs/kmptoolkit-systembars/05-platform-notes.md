# kmptoolkit-systembars — Platform notes

The platforms disagree about almost everything here: how many bars exist, who is allowed to
change them, and whether the change is pushed or pulled. The common API hides that, but it cannot
make the differences go away, and a few of them are visible from your code.

## What each platform actually supports

| | Android | iOS | Desktop (`jvm`) |
|---|---|---|---|
| Status bar icon style | Yes | Yes | **No** — no such bar |
| Navigation bar icon style | Yes | **No** — there is no navigation bar | **No** — no such bar |
| Hide the status bar | Yes | Yes | **No** — no such bar |
| Hide the navigation bar | Yes | No such bar — the axis drives `prefersHomeIndicatorAutoHidden` instead | **No** — no such bar |
| `HiddenBarBehavior` | Yes | **No** — a hidden status bar simply stays hidden | **No** |
| How it is applied | Pushed onto the window's insets controller | Pulled by UIKit from a view controller | Nowhere — every platform call is a no-op |

Axes a platform cannot honour are still tracked, still visible on `config`, and simply have no
effect there. Nothing throws and nothing warns: an app sharing one configuration across platforms is
the normal case, and a navigation-bar style is meaningful on one of them.

Desktop is the limit case of that rule rather than an exception to it. The target exists so a UI
tree shared with desktop compiles at all — see [`docs/01-architecture.md`](../01-architecture.md) —
and everything above the final platform push behaves identically: overrides layer, release restores
what was underneath, and `config` always holds the configuration the tree asked for.

## Permissions

**None.** This module declares no permission in its `AndroidManifest.xml`, and there is nothing for
you to declare either. Everything goes through the window's own insets controller, which is
available to any app for its own window. A test asserts this against a real package manager
(`LibraryManifestTest`) — see [`docs/01-architecture.md`](../01-architecture.md) on why a
library never merges a permission into its consumers.

## Android

### Edge-to-edge is your call, not this module's

This module sets icon appearance and bar visibility. It does **not** call `enableEdgeToEdge()`.

That is deliberate. Going edge-to-edge changes how your whole app is measured — content draws under
the bars, and every screen becomes responsible for consuming the right insets. Flipping that from
inside a "set the status bar icons" call would silently change the layout of screens that never
asked. It is one line in your activity, next to the rest of your window setup:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        setContent { App(systemBars) }
    }
}
```

On Android 15 (API 35) and above edge-to-edge is enforced for apps targeting that level, so this is
increasingly not a choice anyway.

**Use exactly this, not the no-argument `enableEdgeToEdge()`.** The no-argument form installs a
translucent navigation-bar scrim — `0xE6FFFFFF` or `0x801B1B1B`, chosen by the *system* night mode
rather than your theme on API 26–28 — and on API 29+ its `SystemBarStyle.auto` turns navigation-bar
contrast enforcement back **on**, which the runtime setter wins over any `enforceNavigationBarContrast`
theme attribute. On a device with three-button navigation the result is a faint band behind the bar
for the life of the activity. The transparent scrims remove the first; the explicit
`isNavigationBarContrastEnforced = false` after the call removes the second.

Nothing in this module re-applies either, by design — so an app that previously relied on a
controller calling `enableEdgeToEdge` on every write (a common hand-rolled pattern) loses that
side effect when it moves here, and has to state it once in `onCreate` as above.

### Create the controller before the first activity resumes

Create it in `Application.onCreate`. Not lazily, and not from composition.

The controller reaches a window through the currently resumed activity, which it learns about from
`Application.ActivityLifecycleCallbacks.onActivityResumed`. Android has no public way to ask which
activity resumed *before* those callbacks were registered, so a controller created later cannot
reach the window already on screen until the next resume — the user sees the wrong icon style until
they leave and come back, or rotate.

This bites dependency injection in particular. A lazy singleton is first resolved by whatever needs
it first, which is usually your theme — during composition. On Android the first composition runs
*after* `onResume`: the decor view is attached to the window in `handleResumeActivity`, after the
resume callbacks have already fired. So resolve it eagerly, right after the container starts:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { androidContext(this@MyApplication); modules(appModules) }
        // Registers the activity tracker now, before any activity can resume.
        get<SystemBarsController>()
    }
}
```

The same applies to `createScreenWakeLockController`, and to any `ActivityAccess` you create
yourself for either. `ControllerCreationOrderTest` pins this down, including the late-created case.

Insets themselves are Compose's job, not this module's: `WindowInsets.statusBars`,
`WindowInsets.safeDrawing`, `Modifier.windowInsetsPadding`.

### Bar background colours are gone

`Window.statusBarColor` and `Window.navigationBarColor` are deprecated, and from API 35 they do
nothing at all. That is why `SystemBarsConfig` has no colour: the colour behind a bar is whatever
your own UI draws there, and drawing it is a layout concern.

### Activity recreation

A rotation, a theme change, a font-size change and a multi-window resize all destroy the activity
and build a new window at platform defaults. The controller subscribes to its activity tracker's
resume callback and re-applies its current configuration to every activity that resumes after the
tracker exists — so both the base and every live claim survive, because they live in the controller
and not in the window. An activity that had *already* resumed when the tracker was created is not
among them; that is the reason for creating the controller in `Application.onCreate`, above.

This is also why the factory takes a `Context` rather than an `Activity`: holding an activity
across a configuration change is a leak, and the controller solves the problem internally with a
weakly-held, lifecycle-driven tracker instead.

### Dialogs, popups and bottom sheets

A `Dialog`, `Popup` or `ModalBottomSheet` renders into a **separate window** with its own insets
controller. Whatever the controller set on the activity's window does not reach it, and the bars
over an open sheet fall back to the platform default — dark icons, which are invisible on a light
app bar and unreadable in dark mode.

`DialogWindowSystemBarsEffect(controller)` at the top of the dialog's content lambda fixes it. It
detects the dialog window through Compose's `DialogWindowProvider` and does nothing when there is
none, so it is harmless if you call it somewhere that turns out not to be a dialog.

### Behaviour of a hidden bar

`HiddenBarBehavior.SwipeToReveal` maps to `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; `StayHidden`
maps to `BEHAVIOR_DEFAULT`. `StayHidden` really does mean the user has no gesture that brings the
bar back — only use it where your own UI offers a way out.

## iOS

### The status bar is pulled, not pushed

Nothing on iOS can set the status bar directly. A view controller *declares* what it wants through
`preferredStatusBarStyle`, `prefersStatusBarHidden` and `prefersHomeIndicatorAutoHidden`, and UIKit
re-reads those values when it is told they changed. So `IosSystemBarsController` supplies the three
answers and invalidates the host when the configuration changes; the host has to be the controller
UIKit actually asks.

With a plain `ComposeUIViewController` that is the host itself:

```kotlin
private val systemBars = createSystemBarsController()

fun MainViewController(): UIViewController {
    val host = ComposeUIViewController { App(systemBars) }
    systemBars.hostViewController = host
    return host
}
```

If your app wraps Compose in a Swift view controller of its own, that wrapper is the one UIKit asks
— point `hostViewController` at it and return the values from its overrides:

```swift
class ComposeHostingController: UIHostingController<ContentView> {
    override var preferredStatusBarStyle: UIStatusBarStyle { SystemBars.shared.preferredStatusBarStyle }
    override var prefersStatusBarHidden: Bool { SystemBars.shared.prefersStatusBarHidden }
    override var prefersHomeIndicatorAutoHidden: Bool { SystemBars.shared.prefersHomeIndicatorAutoHidden }
}
```

A parent view controller overrides its children, so if Compose is embedded inside another
controller, that parent is the one that has to answer — or it must return its child from
`childForStatusBarStyle` / `childForStatusBarHidden` / `childForHomeIndicatorAutoHidden`.

### SwiftUI apps: make your host the window's root, and keep the scene lifecycle

This is the setup that most often silently ignores everything above. Under a SwiftUI `App`, the
window's root is a `UIHostingController` the app does not own, and Compose embedded through a
`UIViewControllerRepresentable` is a child it never forwards the status-bar questions to. Your
overrides compile, your host is attached, and UIKit never asks it.

Make the host the root yourself, from a scene delegate:

```swift
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = ComposeHostingController()
        window.makeKeyAndVisible()
        self.window = window
    }
}
```

and name it from `application(_:configurationForConnecting:options:)` in your `@main` app delegate
(or in `Info.plist`, if you do not generate the scene manifest).

Do **not** reach for the older app-delegate-owned `window` instead. It works today, but it leaves the
UIScene lifecycle, which Apple is making mandatory for apps built with current SDKs; and it creates
the window — and so the whole Compose tree — on every launch, including background ones such as a
`BGTaskScheduler` wake or a silent push, where a scene would not have connected at all.

### `Info.plist`

`UIViewControllerBasedStatusBarAppearance` must stay at its default of `true`, which means **not
present in `Info.plist`**. Setting it to `false` tells UIKit to ignore view controllers entirely and
use a single app-wide appearance, and nothing this module does will have any effect.

### `hostViewController` is held strongly

It is a plain settable property with no weak reference behind it, because the host outlives the
controller in every normal setup and a weak reference would only hide a wiring mistake. If your host
is torn down while the controller lives on, clear it — `systemBars.hostViewController = null` — or
`release()` the controller, which clears it for you.

### No navigation bar, but there is a home indicator

`navigationBarIcons` is tracked and ignored — there is no bar to style.

`SystemBarsVisibility.isNavigationBarVisible` is *not* ignored: it drives
`prefersHomeIndicatorAutoHidden`. The home indicator is not a navigation bar, but it is the nearest
thing a cross-platform "hide the bottom bar" claim can mean here, and a screen that goes fullscreen
on Android expects the same on iOS rather than a stray indicator left glowing over its content. So
`SystemBarsVisibility.Immersive` hides both bars on Android and hides the status bar and the home
indicator on iOS.

Two things it is not. It cannot be styled — there is no iOS equivalent of an icon style for it. And
"hidden" means *auto-hidden*: iOS fades the indicator out after a moment of no interaction near the
bottom edge and brings it straight back on the next touch there. That is the whole of what the
platform offers; it is not a bar that stays gone.

### Threading

`setNeedsStatusBarAppearanceUpdate()` is UIKit and therefore main-thread only. The controller
dispatches to the main queue when it is called from anywhere else, and calls through inline when it
is already there, so you never have to think about it.

## `ScreenWakeLockController`

### Android

Backed by `Window.FLAG_KEEP_SCREEN_ON`, applied through the same internal activity tracker
`SystemBarsController` uses — `createScreenWakeLockController` builds its own tracker instance, so
using both controllers registers two lightweight `ActivityLifecycleCallbacks`, not one shared one.

The controller remembers whether it last set the flag on the *specific* activity it wrote to
(a weak reference), which is what makes rotation safe in both directions: a `true` held by the
controller is re-applied to the freshly recreated activity's window (which otherwise starts with the
flag unset), and a `false` that arrived while no activity was resumed is still delivered to that same
window the next time it resumes — it does not linger and keep the screen awake indefinitely. A
different activity — a different screen, not a recreation of the same one — never carried this
controller's flag and is left untouched either way.

No permission is required; `FLAG_KEEP_SCREEN_ON` needs none.

### iOS

Backed by `UIApplication.idleTimerDisabled`. No activity-tracker equivalent is needed: iOS hosts one
process-stable `UIWindow` for the app's whole lifetime, and the property is not reset by the OS on
its own. The write is dispatched to the main queue, the same way status-bar updates are.

## `AutoSystemBarsIconStyle`

### What a sample costs, and why the shape matters

A full-screen `GraphicsLayer.toImageBitmap()` followed by `toPixelMap()` copies the *entire* rendered
frame back to the CPU — on a 1080 × 2400 screen that is roughly 10 MB per copy, on the main thread.
`AutoSystemBarsIconStyle` never does this: it re-records the display list into a scratch layer only a
few rows tall (two per bar, by default), clipped and translated so each scratch row shows the source
row it wants, and reads *that* back instead. The display list is replayed, not re-executed, so no
composable's draw lambda runs again for a sample. Reading a strip a few rows tall instead of the
whole screen is most of the difference between an imperceptible per-sample cost and a dropped frame
every cycle — do not "simplify" this back to a full-screen readback.

### Sampling only while visible (both platforms)

`repeatOnLifecycle(Lifecycle.State.STARTED)` means the sampling coroutines are cancelled the moment
the host drops below `STARTED` and restarted from scratch — first-tick rule included — on return to
the foreground. Without this, a `delay`-driven loop keeps ticking in a backgrounded app and rasterises
a scratch bitmap on every tick for no one to see; this is the actual origin of the module's
lifecycle-runtime-compose dependency, not a nice-to-have.

### Animation detection differs by platform

The periodic sample defers while `rememberIsAnimating()` reports `true`, up to a jittered cap so an
endless animation is still sampled occasionally. Android answers this from `Recomposer.hasPendingWork`
— true while anything awaits a frame, including a child animating only its own layer transform, which
never redraws the probe's root. iOS exposes no equivalent public signal, so `rememberIsAnimating()`
returns `null` there and the periodic gate falls back to its draw clock alone (`sinceLastDrawMs`,
written from the same `drawWithContent` that records the sampling layer). In practice this means a
purely layer-driven animation (translation, alpha, scale with no recomposition) can defer a sample
longer on iOS than on Android before the jittered cap forces one anyway — bounded, never unbounded.

### Both bars are read from the same captured frame

The scratch layer holds the status bar's rows followed by the navigation bar's, both replayed from
the *same* recorded frame in one `stripLayer.record` call, so the two bars' decisions are always
about a single consistent moment — never one bar's decision from one frame and the other's from a
frame recorded a tick later.

# kmptoolkit-systembars — Platform notes

The two platforms disagree about almost everything here: how many bars exist, who is allowed to
change them, and whether the change is pushed or pulled. The common API hides that, but it cannot
make the differences go away, and a few of them are visible from your code.

## What each platform actually supports

| | Android | iOS |
|---|---|---|
| Status bar icon style | Yes | Yes |
| Navigation bar icon style | Yes | **No** — there is no navigation bar |
| Hide the status bar | Yes | Yes |
| Hide the navigation bar | Yes | No such bar. The home indicator is not one and is not controllable this way |
| `HiddenBarBehavior` | Yes | **No** — a hidden status bar simply stays hidden |
| How it is applied | Pushed onto the window's insets controller | Pulled by UIKit from a view controller |

Axes a platform cannot honour are still tracked, still visible on `config`, and simply have no
effect there. Nothing throws and nothing warns: an app sharing one configuration across both
platforms is the normal case, and a navigation-bar style is meaningful on one of them.

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(systemBars) }
    }
}
```

On Android 15 (API 35) and above edge-to-edge is enforced for apps targeting that level, so this is
increasingly not a choice anyway.

Insets themselves are Compose's job, not this module's: `WindowInsets.statusBars`,
`WindowInsets.safeDrawing`, `Modifier.windowInsetsPadding`.

### Bar background colours are gone

`Window.statusBarColor` and `Window.navigationBarColor` are deprecated, and from API 35 they do
nothing at all. That is why `SystemBarsConfig` has no colour: the colour behind a bar is whatever
your own UI draws there, and drawing it is a layout concern.

### Activity recreation

A rotation, a theme change, a font-size change and a multi-window resize all destroy the activity
and build a new window at platform defaults. The controller subscribes to its internal activity
tracker's resume callback and re-applies its current configuration to every activity that resumes,
including the one that was already resumed when it was created — so both the base and every live
claim survive, because they live in the controller and not in the window.

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
`preferredStatusBarStyle` and `prefersStatusBarHidden`, and UIKit re-reads those values when it is
told they changed. So `IosSystemBarsController` supplies the two answers and invalidates the host
when the configuration changes; the host has to be the controller UIKit actually asks.

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
— point `hostViewController` at it and return the two values from its overrides:

```swift
class ComposeHostingController: UIHostingController<ContentView> {
    override var preferredStatusBarStyle: UIStatusBarStyle { SystemBars.shared.preferredStatusBarStyle }
    override var prefersStatusBarHidden: Bool { SystemBars.shared.prefersStatusBarHidden }
}
```

A parent view controller overrides its children, so if Compose is embedded inside another
controller, that parent is the one that has to answer — or it must return its child from
`childForStatusBarStyle` / `childForStatusBarHidden`.

### `Info.plist`

`UIViewControllerBasedStatusBarAppearance` must stay at its default of `true`, which means **not
present in `Info.plist`**. Setting it to `false` tells UIKit to ignore view controllers entirely and
use a single app-wide appearance, and nothing this module does will have any effect.

### `hostViewController` is held strongly

It is a plain settable property with no weak reference behind it, because the host outlives the
controller in every normal setup and a weak reference would only hide a wiring mistake. If your host
is torn down while the controller lives on, clear it — `systemBars.hostViewController = null` — or
`release()` the controller, which clears it for you.

### No navigation bar

`navigationBarIcons` and `SystemBarsVisibility.isNavigationBarVisible` are tracked and ignored. The
home indicator is not a navigation bar; hiding it is
`UIViewController.prefersHomeIndicatorAutoHidden`, which is a different decision and outside this
module.

### Threading

`setNeedsStatusBarAppearanceUpdate()` is UIKit and therefore main-thread only. The controller
dispatches to the main queue when it is called from anywhere else, and calls through inline when it
is already there, so you never have to think about it.

# kmptoolkit-systembars — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.systembars`.

## Model

### `SystemBarIconStyle`

```kotlin
public enum class SystemBarIconStyle { DarkIcons, LightIcons }
```

The colour of a bar's icons and text — **not** the colour of the bar. Named for what you see,
because every platform API in this area is named for the background it expects and therefore means
the opposite (`isAppearanceLightStatusBars = true` produces dark icons).

Pick `DarkIcons` over light content, `LightIcons` over dark content.

### `HiddenBarBehavior`

```kotlin
public enum class HiddenBarBehavior { SwipeToReveal, StayHidden }
```

| Value | Behaviour |
|---|---|
| `SwipeToReveal` | A hidden bar reappears translucently on a swipe from its edge, then hides again |
| `StayHidden` | A hidden bar stays hidden; the user cannot bring it back |

Only meaningful for a bar that is hidden. Android only — iOS has no equivalent control.

### `SystemBarsVisibility`

```kotlin
public data class SystemBarsVisibility(
    val isStatusBarVisible: Boolean = true,
    val isNavigationBarVisible: Boolean = true,
    val hiddenBarBehavior: HiddenBarBehavior = HiddenBarBehavior.SwipeToReveal,
)
```

One axis of a configuration, moved and claimed as a unit. `isNavigationBarVisible` is ignored on
iOS, which has no navigation bar.

| Constant | Meaning |
|---|---|
| `SystemBarsVisibility.Visible` | Both bars on screen (the default) |
| `SystemBarsVisibility.Immersive` | Both hidden, `SwipeToReveal` |
| `SystemBarsVisibility.Hidden` | Both hidden, `StayHidden` |

### `SystemBarsConfig`

```kotlin
public data class SystemBarsConfig(
    val statusBarIcons: SystemBarIconStyle = SystemBarIconStyle.DarkIcons,
    val navigationBarIcons: SystemBarIconStyle = SystemBarIconStyle.DarkIcons,
    val visibility: SystemBarsVisibility = SystemBarsVisibility.Visible,
)
```

The complete state of the bars, as three independently ownable axes.

| Constant | Meaning |
|---|---|
| `SystemBarsConfig.ForLightBackground` | Dark icons on both bars, both visible |
| `SystemBarsConfig.ForDarkBackground` | Light icons on both bars, both visible |

There is no bar background colour: on an edge-to-edge app the bars are transparent and your own UI
paints behind them, and the platform properties that used to set one are deprecated and inert on
current Android.

### `SystemBarsOverride`

```kotlin
public data class SystemBarsOverride(
    val statusBarIcons: SystemBarIconStyle? = null,
    val navigationBarIcons: SystemBarIconStyle? = null,
    val visibility: SystemBarsVisibility? = null,
) {
    public val isEmpty: Boolean

    public companion object {
        public val None: SystemBarsOverride
        public fun icons(style: SystemBarIconStyle): SystemBarsOverride
    }
}
```

A claim on some of the three axes. `null` means "not mine" — the layer underneath shows through
untouched, which is what lets two overrides coexist.

| Member | Contract |
|---|---|
| `isEmpty` | `true` when no axis is claimed, so applying it changes nothing |
| `None` | Claims nothing. A useful "not decided yet" value |
| `icons(style)` | Claims **both** icon axes, leaves visibility alone |

## Controller

### `SystemBarsController`

```kotlin
public interface SystemBarsController {
    public val config: StateFlow<SystemBarsConfig>
    public val currentConfig: SystemBarsConfig                       // = config.value
    public fun setBaseConfig(config: SystemBarsConfig)
    public fun updateBaseConfig(transform: (SystemBarsConfig) -> SystemBarsConfig)
    public fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle
    public fun release()
}
```

The single owner of the bars for one app process. Create one, hold it, pass it. Two controllers on
one window would fight, which is what this module exists to prevent.

The effective configuration is a **base** with a stack of overrides folded on top, newest last.

| Member | Contract |
|---|---|
| `config` | The effective configuration. Distinct values only — a change that leaves it identical does not emit. Always in sync with the last completed mutation |
| `currentConfig` | Snapshot of the above |
| `setBaseConfig(config)` | Replaces the base. The theme's call, not a screen's. A base equal to the current one is a no-op |
| `updateBaseConfig(transform)` | Reads and replaces the base atomically; retries against the winner if another writer got there first. `transform` must be pure — it may run more than once. Use this over `setBaseConfig(currentConfig.copy(...))` when more than one writer exists |
| `applyOverride(override)` | Pushes a layer on top and returns its handle. Position is fixed at call time. The caller must release it |
| `release()` | Drops every override, resets the base to defaults, detaches from the platform. Idempotent; the controller is unusable afterwards |

**Thread safety.** Every method is safe from any thread; transitions are a compare-and-set over the
whole layer stack, so concurrent writers cannot lose an axis. Platform work is dispatched to the
main thread by the implementation. Atomicity is not ordering: two writers racing to set the *same*
axis have an undefined winner, which is a design problem to avoid rather than a guarantee to want.

### `SystemBarsOverrideHandle`

```kotlin
public interface SystemBarsOverrideHandle {
    public fun update(override: SystemBarsOverride)
    public fun release()
}
```

| Member | Contract |
|---|---|
| `update(override)` | Replaces this layer's override **in place**, keeping its position in the stack. No-op after `release()`. Never release-then-re-apply instead: that moves the layer to the top |
| `release()` | Removes the layer. The axes it claimed fall back to what the layers underneath say *now*. Idempotent |

A handle that is dropped without being released pins its layer for the controller's lifetime.

## Compose

### `SystemBarsEffect`

```kotlin
@Composable
public fun SystemBarsEffect(controller: SystemBarsController, override: SystemBarsOverride)

@Composable
public fun SystemBarsEffect(
    controller: SystemBarsController,
    statusBarIcons: SystemBarIconStyle? = null,
    navigationBarIcons: SystemBarIconStyle? = null,
    visibility: SystemBarsVisibility? = null,
)
```

Claims `override`'s axes for as long as the composable is in composition, and releases on leaving.
The intended way to use the controller from a screen.

- Ordering between two live effects is composition order: the later one wins any shared axis, and
  releases it back to the earlier one when it leaves.
- Changing the override across recompositions updates the layer **in place** and does not re-order
  it.
- The claim is created inside a `DisposableEffect`, so a composition that is started and then
  abandoned cannot leave a layer pinned.

### `DialogWindowSystemBarsEffect`

```kotlin
@Composable
public fun DialogWindowSystemBarsEffect(controller: SystemBarsController)
```

Applies the controller's current configuration to a Compose surface rendering into its own platform
window — `Dialog`, `Popup`, `ModalBottomSheet`, `BasicAlertDialog`. Call it once at the top of the
dialog's content lambda.

On Android such a surface has an insets controller of its own that the activity-level configuration
never reaches, so without this the bars over an open sheet revert to the platform default. Outside
a dialog window it does nothing. No-op on iOS.

## Factories

### Android

```kotlin
public fun createSystemBarsController(
    activityAccess: ActivityAccess,
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): SystemBarsController
```

`activityAccess` comes from [`kmptoolkit-platform`](../kmptoolkit-platform/01-overview.md)'s
`createActivityTracker(application)`. It takes the tracker rather than an `Activity` because the
window changes identity on every configuration change; the controller re-applies itself to each new
one automatically.

### iOS

```kotlin
public interface IosSystemBarsController : SystemBarsController {
    public var hostViewController: UIViewController?
    public val preferredStatusBarStyle: UIStatusBarStyle
    public val prefersStatusBarHidden: Boolean
}

public fun createSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): IosSystemBarsController
```

iOS pulls status-bar appearance from a view controller rather than accepting a push, so the
controller supplies the two values UIKit asks for and your host returns them. `hostViewController`
is held strongly and is what gets `setNeedsStatusBarAppearanceUpdate()` on every change; clear it
(or `release()` the controller) when the host goes away. See
[`05-platform-notes.md`](05-platform-notes.md).

## Testing fixtures

None. This module ships no `-testing` artifact, and deliberately: the part worth faking in a test
is `SystemBarsController`, which is a five-method interface over three enums with no platform
types in its signatures — a fake is shorter than the import that would bring one in.

```kotlin
class FakeSystemBarsController : SystemBarsController {
    private val state = MutableStateFlow(SystemBarsConfig())
    override val config: StateFlow<SystemBarsConfig> = state
    override fun setBaseConfig(config: SystemBarsConfig) { state.value = config }
    override fun updateBaseConfig(transform: (SystemBarsConfig) -> SystemBarsConfig) { state.update(transform) }
    override fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle = TODO()
    override fun release() = Unit
}
```

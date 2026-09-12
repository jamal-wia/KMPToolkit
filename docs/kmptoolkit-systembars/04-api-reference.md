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

### `AutoSystemBarsIconStyle`

```kotlin
@Composable
public fun AutoSystemBarsIconStyle(
    controller: SystemBarsController,
    probe: StatusBarLuminanceProbe,
    enabled: Boolean = true,
    intervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    onSampled: ((durationNanos: Long) -> Unit)? = null,
    content: @Composable () -> Unit,
)
```

Wraps `content` with an automatic icon-style probe for both bars: samples the pixels drawn under
each on a timer (and on demand via `probe`), derives a contrasting `SystemBarIconStyle` per bar, and
publishes both as a single [`SystemBarsOverride`](#systembarsoverride) it pushes once and updates in
place — a layer like any other, so a screen's own `SystemBarsEffect` composed later still wins any
axis it claims. See [`03-guide.md`](03-guide.md#automatic-icon-styling).

| Parameter | Contract |
|---|---|
| `controller` | Receives the derived styles |
| `probe` | The on-demand trigger source — see [`createStatusBarLuminanceProbe`](#statusbarluminanceprobe) |
| `enabled` | `false` disables sampling and pushes no override; `content` still composes and is still recorded into the internal layer every frame |
| `intervalMs` | Periodic sample cadence — [`DEFAULT_SAMPLE_INTERVAL_MS`] (300 ms) by default |
| `onSampled` | Diagnostics hook called after every sample with how long it took, in nanoseconds. `null` (the default) costs nothing |

Sampling runs only while the host's `Lifecycle` is at least `STARTED`. Place this once, near the
root, and not under a layer-introducing modifier or composable — see the guide's placement section.

### `StatusBarLuminanceProbe`

```kotlin
public interface StatusBarLuminanceProbe {
    public fun triggerRecalculation()
    public val triggers: SharedFlow<Unit>
}

public fun createStatusBarLuminanceProbe(): StatusBarLuminanceProbe
```

| Member | Contract |
|---|---|
| `triggerRecalculation()` | Asks `AutoSystemBarsIconStyle` to re-sample on the next composed frame. Bursts are conflated to at most one extra sample |
| `triggers` | Consumed internally by `AutoSystemBarsIconStyle`. Call `triggerRecalculation()` instead of collecting this yourself |

`createStatusBarLuminanceProbe()` is platform-independent — no `Context` needed on either target.
Create one per `AutoSystemBarsIconStyle`, hold it, and pass it to both the wrapper and every screen
that needs to nudge it.

### `DEFAULT_SAMPLE_INTERVAL_MS`

```kotlin
public const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 300L
```

The default `intervalMs` for `AutoSystemBarsIconStyle`.

## Factories

### Android

### Android

```kotlin
public fun createSystemBarsController(
    context: Context,
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): SystemBarsController
```

`context`'s application context is retained to track the currently resumed activity, rather than
taking an `Activity` directly, because the window changes identity on every configuration change;
the controller re-applies itself to each new one automatically.

### iOS

```kotlin
public interface IosSystemBarsController : SystemBarsController {
    public var hostViewController: UIViewController?
    public val preferredStatusBarStyle: UIStatusBarStyle
    public val prefersStatusBarHidden: Boolean
    public val prefersHomeIndicatorAutoHidden: Boolean
}

public fun createSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): IosSystemBarsController
```

`prefersHomeIndicatorAutoHidden` is `!visibility.isNavigationBarVisible`. iOS has no navigation bar,
so that axis would otherwise be inert; the home indicator is the nearest thing a cross-platform
"hide the bottom bar" claim can mean here. It is auto-hiding only — the indicator returns on the
next touch near the bottom edge — and it cannot be styled.

iOS pulls this appearance from a view controller rather than accepting a push, so the controller
supplies the values UIKit asks for and your host returns them. `hostViewController` is held strongly
and is what gets `setNeedsStatusBarAppearanceUpdate()` and
`setNeedsUpdateOfHomeIndicatorAutoHidden()` on every change; clear it (or `release()` the controller)
when the host goes away. See
[`05-platform-notes.md`](05-platform-notes.md).

## Wake lock

### `ScreenWakeLockController`

```kotlin
public interface ScreenWakeLockController {
    public fun setKeepScreenOn(enabled: Boolean)
}
```

Suppresses, or restores, the OS's screen-idle timer — unrelated to the bars, and shipped in this
module because both are thin wrappers over a single per-window platform flag. See
[`03-guide.md`](03-guide.md#screenwakelockcontroller-a-different-shape-on-purpose).

| Member | Contract |
|---|---|
| `setKeepScreenOn(enabled)` | Idempotent: calling it with the value it already holds is a no-op. There is no `release()` — callers must call `setKeepScreenOn(false)` themselves when the reason to stay awake ends |

### Factories

```kotlin
// Android
public fun createScreenWakeLockController(context: Context): ScreenWakeLockController

// iOS
public fun createScreenWakeLockController(): ScreenWakeLockController
```

The Android implementation tracks the currently resumed activity internally, the same way
`createSystemBarsController` does, and re-applies a held `true` to each newly resumed activity
across configuration changes. The iOS implementation needs no such tracking — see
[`05-platform-notes.md`](05-platform-notes.md).

## Testing fixtures

Both controllers ship a double in `kmptoolkit-systembars-testing`.

```kotlin
public class RecordingSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
) : SystemBarsController {
    public val applied: List<SystemBarsConfig>
    public val activeOverrideCount: Int
    public fun clear()
}
```

It layers overrides exactly as the real controller does — newest wins a shared axis, an override
claims only the axes it names, releasing one restores whatever is underneath it at that moment —
and records every configuration that would have reached a window in `applied`, skipping mutations
that changed nothing. `activeOverrideCount` is there to assert a screen leaves no layer behind. Not
thread-safe, deliberately: see [`06-testing.md`](06-testing.md).

If all you need is somewhere for a configuration to go, the interface is small enough to fake
inline — no import required:

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

`ScreenWakeLockController`'s double is `RecordingScreenWakeLockController`, which records every
`setKeepScreenOn` call. See [`06-testing.md`](06-testing.md).

`StatusBarLuminanceProbe` ships none either, for the same reason as `SystemBarsController`:

```kotlin
class FakeStatusBarLuminanceProbe : StatusBarLuminanceProbe {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val triggers: SharedFlow<Unit> = flow
    override fun triggerRecalculation() { flow.tryEmit(Unit) }
}
```

`AutoSystemBarsIconStyle` itself is not something to fake — it is a composable that exercises real
`GraphicsLayer` pixel sampling, and testing it means testing that Compose is drawing what you think
it is. Test the *decision* it makes by testing your own code against `FakeSystemBarsController` /
`FakeStatusBarLuminanceProbe`, and trust this module's own test suite (`LuminanceToStyleTest`,
`ScratchRowSourcesTest`, `PeriodicSampleGateTest`, `IdleTickTest`, `SampleRowsTest`,
`PublishStylesTest`) for the sampling logic itself.

# kmptoolkit-hardware-keys — API reference

Package: `io.github.jamal_wia.kmptoolkit.hardwarekeys`

## Common

### `DialogWindowHardwareKeyEffect`

```kotlin
@Composable
public expect fun DialogWindowHardwareKeyEffect()
```

Installs the registered `DialogWindowHardwareKeyPolicy` on the dialog-class window this composable is
composed in, for as long as it stays in composition.

| Platform | Behaviour |
|---|---|
| Android, inside a `Dialog` / `ModalBottomSheet` / `DatePickerDialog` / other window with a `DialogWindowProvider` | Wraps the window's `Window.Callback` unless its outermost callback is already one of the effect's wrappers; key dispatch goes to the interceptor first, and the policy runs once per key however many wrappers the chain holds. When the last effect leaves, its wrappers on top are removed; one under another library's decorator stays, inert. |
| Android API 28+, inside a focusable `Popup` or the Activity's own content | Adds a platform `View.OnUnhandledKeyEventListener` on the composition's view (one per view, shared by every effect composed on it); removes it when the last of them leaves. The policy consulted is the one read by the most recently composed effect still on that view. |
| Android below API 28, inside a `Popup` or the Activity's own content | No-op. |
| Android, no policy installed | No-op. |
| iOS | No-op. |
| Desktop (`jvm`) | No-op. |

The effect reads the policy when it enters composition. Installing or uninstalling later does not
affect a window already composed.

## Android

### `DialogWindowHardwareKeyPolicy`

```kotlin
public object DialogWindowHardwareKeyPolicy {
    public fun install(logger: Logger = NoopLogger, interceptor: (KeyEvent) -> Boolean)
    public fun uninstall()
}
```

The process-wide registration read by `DialogWindowHardwareKeyEffect`. Main thread only.

#### `install(logger, interceptor)`

| Parameter | Meaning |
|---|---|
| `interceptor` | Called on the main thread for every `android.view.KeyEvent` delivered to a window running the effect, before the window's own dispatch. `true` consumes the event; `false` passes it through unchanged. Retained for the process lifetime — capture only application-scoped objects. |
| `logger` | A `kmptoolkit-logging` `Logger`. Receives one error-level event when `install` replaces a policy that was already installed. Defaults to `NoopLogger`. |

Call once from `Application.onCreate`. A second call replaces the first.

#### `uninstall()`

Removes the policy. Windows composed afterwards get stock key handling; windows already composed keep
the interceptor they captured until they leave composition. To switch a policy off at runtime, keep it
installed and return `false` from the lambda instead.

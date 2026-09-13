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
| Android, inside a `Dialog` / `ModalBottomSheet` / `DatePickerDialog` / other window with a `DialogWindowProvider` | Wraps the window's `Window.Callback`; key dispatch goes to the interceptor first. On dispose, restores the original callback if the wrapper is still installed. |
| Android, inside a focusable `Popup` or the Activity's own content | Adds a `ViewCompat` unhandled-key listener on the composition's view; removes it on dispose. |
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
the interceptor they captured until they leave composition.

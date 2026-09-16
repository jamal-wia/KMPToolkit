# kmptoolkit-hardware-keys — Platform notes

## Android

### Permissions

None. The module intercepts keys delivered to the app's own windows, which needs nothing declared.
`LibraryManifestTest` asserts that the module's manifest contributes no permission.

### How each window type is hooked

Android routes a key event to the focused window's `ViewRootImpl`, which offers it to the window's
`Window.Callback` — normally the `Dialog` or `Activity` that owns the window — and, if nothing
consumes it, to the `PhoneWindow` fallback that implements system behaviour such as the volume panel.

- **Windows with a `Window.Callback`** (`Dialog`, `ModalBottomSheet`, `DatePickerDialog`,
  `BasicAlertDialog`, and anything else backed by `android.app.Dialog`). Compose exposes the window
  through the composition view's parent, a `DialogWindowProvider`. The effect replaces
  `window.callback` with a wrapper whose `dispatchKeyEvent` asks the interceptor first and delegates
  otherwise. This is the only point that runs ahead of the `PhoneWindow` fallback: an
  `OnUnhandledKeyEventListener` runs too late, because `DecorView` lets `PhoneWindow` handle the key
  and reports it handled, and an `OnKeyListener` fires only while its view holds focus.
- **Windows without a callback** (a focusable `Popup`, which is a bare view added to the
  `WindowManager`). There is no callback to wrap, and here `ViewRootImpl` does offer unhandled keys
  to `View.OnUnhandledKeyEventListener` before falling back. The effect adds one through
  `ViewCompat.addOnUnhandledKeyEventListener` — **on API 28 and higher only**. Below 28 the platform
  does not dispatch these listeners; androidx emulates them only inside a `ComponentActivity` or
  `ComponentDialog`, where a `Popup` never reaches them. The effect is a no-op in a `Popup` there.
- **The Activity's own window.** No `DialogWindowProvider` is present, so the effect takes the
  listener path. On API 28+ it is harmless there: whatever the Activity's own handling consumed never
  reaches the listener. Below 28 the androidx emulation would run the listener *before*
  `Activity.onKeyDown`, so the effect installs nothing.

### One interception per window

Calling the effect twice in the same dialog window — an app-wide dialog wrapper plus a shared component
that calls it too — installs one wrapper, not two, so the interceptor runs once per key event.

### The back key

With predictive back (the default for apps targeting API 36 on Android 16+), the back gesture reaches
`OnBackInvokedCallback` without passing through `Window.Callback` as a `KEYCODE_BACK` event, so the
interceptor cannot consume it. Handle back with Compose's `BackHandler` inside the dialog instead.

### The wrapper forwards the Java default methods by hand

`Window.Callback` has two Java `default` methods — `onProvideKeyboardShortcuts` and
`onPointerCaptureChanged`. Kotlin's `by` interface delegation does not generate forwarders for Java
default methods, so a delegating wrapper would silently answer with the interface's empty defaults
instead of the dialog's implementations. The wrapper overrides both explicitly and delegates.

### Threading

`install`, `uninstall` and every interceptor call happen on the main thread. The policy field is not
synchronized; calling `install` from another thread while a window is being composed is unsupported.

## iOS

`DialogWindowHardwareKeyEffect` is a no-op. The hardware volume buttons are handled by the system and
are not delivered to apps as key events, and Compose sheets and dialogs on iOS share the root view
controller rather than opening separate windows with separate dispatch. There is no
`DialogWindowHardwareKeyPolicy` on iOS.

## Desktop (`jvm`)

`DialogWindowHardwareKeyEffect` is a no-op. The target exists under the exception in
[`../01-architecture.md`](../01-architecture.md#desktop-targets): the effect is called from inside
dialog content, dialog content is typical shared UI, and without the target that shared code would
not compile for desktop at all.

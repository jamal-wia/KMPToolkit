# kmptoolkit-hardware-keys — Guide

## Install once

`DialogWindowHardwareKeyPolicy` holds **one** interceptor for the whole process. `install` is meant
to be called once, from `Application.onCreate`, and the rules follow from that:

- **Install before any window opens.** A dialog window reads the policy when the effect enters
  composition. A window already on screen when you call `install` keeps what was in force when it was
  composed — it does not pick the new policy up until it is composed again.
- **Capture only application-scoped objects.** The lambda lives as long as the process. Capturing an
  `Activity`, a `View`, a `Window`, a composition, or a presenter holding any of those leaks it across
  every configuration change for the rest of the process.
- **A second `install` replaces the first**, and is reported through the `logger` you pass at error
  level. It is almost always a sign that a screen, rather than the application, is registering the
  policy — which is precisely the leak above.

```kotlin
DialogWindowHardwareKeyPolicy.install(logger = logger, interceptor = ::consumeVolumeKey)
```

## A policy that changes at runtime

Do not install and uninstall the policy as a feature turns on and off: install it unconditionally
and let the lambda ask an application-scoped source whether it applies right now.

```kotlin
class KioskState {                     // application-scoped
    @Volatile var locked: Boolean = false
}

DialogWindowHardwareKeyPolicy.install { event ->
    kioskState.locked && event.isVolumeKey()
}
```

This keeps windows that are already open correct: they hold the lambda, and the lambda reads the
current state on every event. Swapping the lambda itself would leave them on the old one.

Call `uninstall()` only when the policy is permanently gone for this process — for example, when a
device is being decommissioned from kiosk mode and the app is about to restart. After it, newly
composed windows get stock key handling.

## One wrapper for every dialog

The effect has to be called in every dialog window, and a missed one is silent: the policy simply
does not apply in that window. The dependable way to not miss one is to have no raw dialog calls at
all — route every dialog-class composable through one wrapper that calls the effect together with
anything else each dialog window needs:

```kotlin
@Composable
fun AppDialogContent(content: @Composable () -> Unit) {
    DialogWindowHardwareKeyEffect()
    // e.g. DialogWindowSystemBarsEffect(controller) from kmptoolkit-systembars — the same
    // separate-window problem, for the system bars.
    content()
}

Dialog(onDismissRequest = onDismiss) {
    AppDialogContent { DialogBody() }
}
```

A source-scanning unit test that fails the build when a file calls `Dialog(`, `ModalBottomSheet(` or
`Popup(` without going through the wrapper turns "remember to call it" into something the build
enforces.

## What the interceptor sees

- **Both `ACTION_DOWN` and `ACTION_UP`**, and repeats. Consume both halves of a key you care about;
  consuming only the down leaves an unpaired up for the dialog.
- **Only keys delivered to the window that runs the effect.** A key pressed while the Activity's own
  window has focus goes to the Activity first; on API 28 and higher the effect in Activity content
  sees only what the Activity and its views left unhandled.
- **Before the dialog's content, in a dialog window.** In a window with a `DialogWindowProvider` (a
  `Dialog`, `ModalBottomSheet`, `DatePickerDialog`) a consumed key never reaches a `Modifier.onKeyEvent`
  inside the dialog, nor the dialog's own back handling if you consume `KEYCODE_BACK`. In a focusable
  `Popup` and in Activity content (API 28+) the interceptor runs after the view tree, so a
  `Modifier.onKeyEvent` has already seen the key and consuming it stops only the platform's own fallback.
  Return `false` for the
  back key unless you intend to disable dismissing by back — and do not rely on consuming it: with
  predictive back on Android 16+ the gesture never arrives as a key event (see
  [`05-platform-notes.md`](05-platform-notes.md#the-back-key)).
- **In a `Popup`, only on API 28 and higher.** Below 28 the effect does nothing there.

## Dialog windows other code has decorated

The effect wraps the window's existing `Window.Callback` and forwards everything it does not consume.
When the effect enters a window whose outermost callback is another library's decorator, it puts a
fresh wrapper on top; all of the effect's wrappers in one window consult one shared record, so however
many the chain holds, the policy runs once per key, and the ones below only forward. When the last
effect leaves, the effect removes its own wrappers from the top and leaves any decorator it does not
own - and anything under it - in place; a wrapper left under such a decorator is inert (it forwards
every key and no longer consults the policy). A decorator that puts back a callback it saved before the
effect's wrapper drops that wrapper; the policy then acts again from the next effect that enters the
window.

If two effects in one window or view read different policies (possible only when `install` replaced the
policy between their compositions, which it reports as an error), the most recently composed one still
in composition decides, and an effect that leaves takes its policy with it. A key dispatched to the same
window from inside the handling of another key is not offered to the policy again.

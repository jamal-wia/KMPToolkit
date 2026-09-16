# kmptoolkit-hardware-keys — Overview

Keeps an app's hardware-key policy in force inside Compose dialog-class windows on Android — a
`Dialog`, a `ModalBottomSheet`, a `DatePickerDialog`, a focusable `Popup` — where the Activity's own
key handling cannot reach.

## The problem it solves

An app that handles hardware keys usually does it in one place: `Activity.onKeyDown` /
`onKeyUp`, or `dispatchKeyEvent`. A kiosk swallows the volume keys so the device's sound cannot be
changed; a reader turns pages with them; a recorder maps them to start and stop. That works until a
bottom sheet opens.

Every Compose dialog-class composable renders in its **own platform window**, and each window has its
own key dispatch. A key pressed while a sheet is up is delivered to the sheet's window, not the
Activity's. If nothing in that window consumes it, it falls back to the window's `PhoneWindow`, which
does what the system does with an unhandled volume key: it shows the volume panel and changes the
volume. The Activity is never asked. The policy silently stops applying for exactly as long as the
sheet is on screen, and nothing in the app is aware that it did.

Fixing it per dialog means reaching for `Window.Callback`, `DialogWindowProvider`, and — for a
`Popup`, which has no `Window` at all — `ViewCompat`'s unhandled-key listener, and getting the
restore order right when the dialog leaves composition. That is what this module does, once.

## How this module answers it

Two pieces, registered in two different places on purpose:

```kotlin
// Application.onCreate — the policy, once, for the whole process (Android only).
DialogWindowHardwareKeyPolicy.install { event ->
    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}

// Inside every dialog window's content — commonMain.
Dialog(onDismissRequest = onDismiss) {
    DialogWindowHardwareKeyEffect()
    DialogBody()
}
```

- **The policy is yours.** The interceptor decides which keys to consume; the module decides nothing.
  Returning `false` leaves an event exactly as it would have been without the module.
- **The effect is per window**, because the problem is per window. It installs the policy on the
  window it is composed in and restores that window's original callback when it leaves.
- **Nothing registered means nothing happens.** Calling the effect with no policy installed is a
  no-op, so shared dialog code can call it unconditionally.

## What this is **not**

- **Not a global key interceptor.** It sees keys delivered to the app's own windows while they are
  focused. Keys pressed while another app, the lock screen, or a system dialog is in front are out of
  reach, and intercepting those needs an accessibility service or device-owner APIs — deliberately
  not this module's business.
- **Not a key-mapping policy.** It ships no rule about which keys matter. There is no "kiosk mode"
  here, only the seam a kiosk mode needs.
- **Not the Activity half.** Keys delivered to the Activity's own window are handled where you
  already handle them. The module only closes the gap the dialog windows open.
- **Not an iOS or desktop feature.** iOS does not deliver the hardware volume buttons to apps and
  sheets share the root view controller; desktop has no equivalent per-window routing. On both, the
  effect compiles and does nothing — see [`05-platform-notes.md`](05-platform-notes.md).

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — install the policy and add the effect.
- [`03-guide.md`](03-guide.md) — policies that change at runtime, one wrapper for every dialog, and
  what the interceptor must not capture.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
- [`05-platform-notes.md`](05-platform-notes.md) — how each window type is hooked, and iOS/desktop.
- [`06-testing.md`](06-testing.md) — testing a policy without a device.

package io.github.jamal_wia.kmptoolkit.hardwarekeys

import androidx.compose.runtime.Composable

/**
 * Installs the app's hardware-key policy, if one is registered, on the Compose dialog-class window
 * this composable runs inside — `Dialog`, `ModalBottomSheet`, `DatePickerDialog`, `BasicAlertDialog`,
 * a focusable `Popup`, and so on.
 *
 * **Why a per-window hook is needed at all.** Each of those composables renders in its own platform
 * window with its own key dispatch. On Android an unhandled key there falls back to that window's
 * `PhoneWindow` — which is what pops up the system volume panel — so the Activity's own
 * `onKeyDown`/`onKeyUp` never sees it. A policy the Activity enforces (a kiosk that swallows the
 * volume keys, a reader that turns pages with them) silently stops applying the moment a sheet opens.
 *
 * **Every dialog window has to call this itself.** Nothing propagates from the Activity into a
 * separate window, so the call goes inside the dialog's content lambda. An app that routes all of its
 * dialogs through one wrapper composable calls it once, there.
 *
 * On Android the policy is registered through `DialogWindowHardwareKeyPolicy`. Where nothing is
 * registered — and always on iOS and desktop, which have no such key dispatch to intercept — this is
 * a no-op. It needs no controller and no DI, so it is safe to call from a low-level shared dialog.
 */
@Composable
public expect fun DialogWindowHardwareKeyEffect()

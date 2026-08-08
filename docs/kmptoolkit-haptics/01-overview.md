# kmptoolkit-haptics — Overview

Three types: a `HapticFeedback` your shared code calls, a `HapticType` describing *what happened*,
and a `HapticResult` telling you whether the platform took the request.

```kotlin
class SaveButtonPresenter(private val haptics: HapticFeedback) {

    fun onSaveFinished(succeeded: Boolean) {
        haptics.perform(if (succeeded) HapticType.SUCCESS else HapticType.ERROR)
    }
}
```

That call site is the whole point: it does not know about `VibrationEffect`, about the three
Android API levels where the vibrator API changed shape, about `UIImpactFeedbackGenerator`, or
about the fact that UIKit refuses to be touched off the main thread.

## The problem it solves

A tap on save should feel different from a tap on failure, and expressing that in shared code
normally costs you two platform implementations plus a seam between them. The platform-specific
parts are individually small and collectively annoying:

- **Android** changed its vibration API twice inside this library's supported range. `minSdk 24` has
  only the deprecated `vibrate(long)` and `vibrate(long[], int)`; API 26 adds `VibrationEffect` and
  amplitude control; API 31 deprecates the `VIBRATOR_SERVICE` lookup in favour of
  `VibratorManager.defaultVibrator`. All three branches are live code, not history.
- **Android throws** where you would least like it to: a missing `android.permission.VIBRATE`
  surfaces as a `SecurityException` from inside whatever click handler asked for the buzz.
- **iOS** has no vibration API at all in the Android sense — it has two *feedback generators* with
  different type vocabularies, both of which must be used on the main thread.

This module collapses that into one interface and one enum, and turns the failure modes into a
returned value instead of an exception.

## What this is **not**

- **Not a vibration API.** You cannot ask it for "180 ms at amplitude 90", and there is no way to
  pass a custom pattern. The vocabulary is six semantic types, because that is the vocabulary iOS
  has and the one that survives being mapped onto both platforms. If you need arbitrary waveforms,
  you want `android.os.Vibrator` directly, in Android code.
- **Not Core Haptics.** No `.ahap` files, no continuous or parametric haptics, no audio-coupled
  haptics, no `CHHapticEngine` lifecycle. Those are a different problem with a different API shape
  and a real engine to keep alive; this module allocates nothing that needs releasing.
- **Not a "did the user feel it" oracle.** `HapticResult.PERFORMED` means the platform accepted the
  request. Neither Android nor iOS reports back whether the motor actually ran, and both silently
  drop haptics when the user turned them off system-wide. See
  [`05-platform-notes.md`](05-platform-notes.md).
- **Not a permission requester.** It declares no Android permission (that is
  [a deliberate repository-wide rule](../01-architecture.md#android-manifests)) and it will not
  prompt for one. `VIBRATE` is an install-time permission anyway — there is nothing to prompt for,
  only a manifest line for *your* app to add.
- **Not a settings store.** It does not remember that your user turned haptics off. Wrap it, or
  swap in `noOpHapticFeedback()`; [`03-guide.md`](03-guide.md#respecting-a-user-setting) shows both.
- **Not tied to Compose or any UI framework.** It is plain Kotlin. Compose's own
  `LocalHapticFeedback` is a fine alternative if you are Compose-only on both platforms and happy
  with its two-value vocabulary; this module exists for shared code that is not Compose, or that
  needs the notification types Compose does not expose everywhere.
- **Not asynchronous, and not queued.** `perform` returns immediately and does not wait for the
  pulse. Nothing is coalesced: three calls in a row are three requests, and how they overlap is the
  platform's business.

## When to use it

Use it when shared Kotlin code — a presenter, a state machine, a validation flow — is the place
that *knows* something succeeded or failed, and you want that knowledge to reach the user's
fingertips without leaking platform types into shared code.

If only your Android UI layer ever fires haptics, call `Vibrator` (or `View.performHapticFeedback`)
directly. The indirection pays for itself when the decision lives in common code.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — user settings, decorators, handling `HapticResult`, mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — the `VIBRATE` permission, API levels, iOS limits
- [`06-testing.md`](06-testing.md) — `RecordingHapticFeedback` and what to assert with it

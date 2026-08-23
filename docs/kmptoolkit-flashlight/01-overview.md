# kmptoolkit-flashlight — Overview

Two types: a `Flashlight` your shared code calls, and a `FlashPattern` describing the on/off
rhythm to blink it in.

```kotlin
class AttentionCue(private val flashlight: Flashlight) {

    fun onUserWentIdle() = flashlight.start(FlashPattern.Blink)
    fun onUserBack() = flashlight.stop()
}
```

That call site is the whole point: it does not know about `CameraManager.setTorchMode`, about
`CameraCharacteristics.FLASH_INFO_AVAILABLE`, about `AVCaptureDevice.lockForConfiguration`, or
about the fact that both platforms can refuse the torch to another app mid-blink.

## The problem it solves

A cue that still works when the screen is off, or the device is lying face-down on a table, needs
the camera torch — and driving it directly costs two platform implementations plus a repeating
coroutine loop on each:

- **Android** requires finding the right camera first: `CameraManager.getCameraIdList()` returns
  every camera, and only some devices report a flash unit at all. `setTorchMode` also throws
  `CameraAccessException` when another app holds the camera.
- **iOS** has no dedicated torch API — the torch lives on `AVCaptureDevice`, guarded by
  `lockForConfiguration`/`unlockForConfiguration`, which AVFoundation requires around every torch
  change and which can fail if something else is configuring the device.
- **Both** need a loop, not a single call: blinking is on/off/on/off until told to stop, and a
  cancellation landing mid-cycle must never leave the torch lit.

This module collapses that into one interface and one enum, and makes every failure mode a silent
no-op instead of an exception.

## What this is **not**

- **Not a flashlight app.** There is no "turn it on and leave it on" mode. `start` always blinks a
  `FlashPattern`; a caller that wants the torch held continuously is not this module's use case.
- **Not a camera API.** No preview, no capture session, no photo or video. Both implementations
  reach only for the torch, which is exactly why they never touch the microphone or disturb a
  capture session another part of the app is running.
- **Not a "did the torch actually light" oracle.** Neither platform reports back whether the LED
  physically turned on; `start` and `stop` return `Unit`, and the only synchronous fact this module
  can offer is `isAvailable` — see [`05-platform-notes.md`](05-platform-notes.md).
- **Not a permission requester.** Neither platform needs one for torch mode; see
  [`05-platform-notes.md`](05-platform-notes.md#permissions).
- **Not a settings store.** It does not remember that your user turned this cue off. Wrap it, or
  swap in `noOpFlashlight()`; [`03-guide.md`](03-guide.md#respecting-a-user-setting) shows both.
- **Not tied to Compose or any UI framework.** It is plain Kotlin.

## When to use it

Use it when shared Kotlin code needs a cue that reaches the user without depending on sound (which
a muted device suppresses) or a lit screen (which a face-down device has none of). It is one of
several cues a well-designed reminder system offers; `kmptoolkit-haptics` is the tactile one, and
this module is deliberately built as its mirror — same shape, same best-effort contract, same
shipped test fake.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — user settings, decorators, availability, mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — permissions (there are none), camera lookup,
  the blink loop
- [`06-testing.md`](06-testing.md) — `RecordingFlashlight` and what to assert with it

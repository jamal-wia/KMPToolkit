# kmptoolkit-flashlight — API reference

Package: `io.github.jamal_wia.kmptoolkit.flashlight`

This file mirrors the committed ABI dumps at `kmptoolkit-flashlight/api/` and
`kmptoolkit-flashlight-testing/api/`. If they disagree, the dumps are authoritative and this file
is a bug.

The whole public surface is three declarations in the production artifact — one interface, one
enum, one common factory — plus one platform factory per target, and one class in the `-testing`
artifact.

## `interface Flashlight`

The seam. Depend on this in shared code; never on a platform camera API directly.

| Member | Signature | Contract |
|---|---|---|
| `isAvailable` | `val isAvailable: Boolean` | Whether this device has a torch at all. Fixed for the instance's lifetime |
| `start` | `fun start(pattern: FlashPattern)` | Starts blinking `pattern` and keeps going until `stop`. Returns immediately. A second call replaces the running pattern rather than layering a second one on top |
| `stop` | `fun stop()` | Stops the blinking and leaves the torch off. Safe to call when nothing is running |

Neither `start` nor `stop` throws: a device with no flash unit, or a torch another app holds, makes
every call a silent no-op. Implementations are safe to call from any thread.

Implementing it yourself is expected — a settings-aware decorator is the common case
([`03-guide.md`](03-guide.md#respecting-a-user-setting)).

## `enum class FlashPattern`

Two on/off rhythms, declared in this order:

| Constant | `on` | `off` | Use it for |
|---|---|---|---|
| `Attention` | 120 ms | 180 ms | A quick flash to accompany a louder cue |
| `Blink` | 600 ms | 400 ms | A device left face-down: seen from across a room |

`on` and `off` are both `kotlin.time.Duration`. Neither pattern has a fixed repeat count or total
duration — both loop until `stop`.

## `fun noOpFlashlight(): Flashlight`

Returns a stateless implementation that does nothing: `isAvailable` is always `false`, and both
`start` and `stop` are no-ops. The same instance every time; comparing two results with `===` is
true.

Use it as the injected instance when the user turned this cue off, or on a target where you have
not wired a real one, so shared call sites stay unconditional.

## `fun createFlashlight(context: Context): Flashlight` — Android only

Builds the Android implementation on top of `android.hardware.camera2.CameraManager`.

- Lives in `androidMain`. There is deliberately **no** `expect`/`actual` pair: Android needs a
  `Context` and iOS needs nothing, so a common signature could only be a lie
  ([`02-getting-started.md`](02-getting-started.md#3-build-the-real-implementation-in-platform-code)).
- Retains `context.applicationContext`, so passing an `Activity` does not leak it.
- Resolves the torch-capable camera once, at construction — a device cannot grow a flash unit at
  runtime.
- Holds nothing that requires releasing beyond an in-flight blink; there is no `close()`. Call
  `stop()` before discarding an instance that might be mid-blink.

## `fun createFlashlight(): Flashlight` — iOS only

Builds the iOS implementation on top of `AVCaptureDevice`'s torch. Lives in `iosMain`. Holds no
device reference between calls — it looks the torch-capable camera up fresh on every `start` and
`stop` — and there is nothing to release.

## `class RecordingFlashlight` — artifact `kmptoolkit-flashlight-testing`

Package: `io.github.jamal_wia.kmptoolkit.flashlight.testing`

```kotlin
public class RecordingFlashlight(
    override var isAvailable: Boolean = true,
) : Flashlight
```

| Member | Signature | Contract |
|---|---|---|
| `isAvailable` | `var isAvailable: Boolean` | What `Flashlight.isAvailable` reports. Mutable so one instance can play a device losing its torch mid-test |
| `events` | `val events: List<FlashPattern?>` | Every `start`/`stop` call, oldest first: a `FlashPattern` per `start`, `null` per `stop`. A snapshot — it does not change as more calls arrive |
| `isBlinking` | `val isBlinking: Boolean` | Whether a pattern is running right now, following the last `start`/`stop` call |
| `start` | `fun start(pattern: FlashPattern)` | Records `pattern` and sets `isBlinking = true`. Recording happens regardless of `isAvailable` |
| `stop` | `fun stop()` | Records `null` and sets `isBlinking = false` |
| `clear` | `fun clear()` | Drops the recording and resets `isBlinking`; leaves `isAvailable` untouched |

**Not thread-safe**, by design — see [`06-testing.md`](06-testing.md).

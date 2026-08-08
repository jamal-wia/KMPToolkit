# kmptoolkit-haptics — API reference

Package: `io.github.jamal_wia.kmptoolkit.haptics`

This file mirrors the committed ABI dumps at `kmptoolkit-haptics/api/` and
`kmptoolkit-haptics-testing/api/`. If they disagree, the dumps are authoritative and this file is a
bug.

The whole public surface is four declarations in the production artifact — one interface, two
enums, one common factory — plus one platform factory per target, and one class in the `-testing`
artifact.

## `interface HapticFeedback`

The seam. Depend on this in shared code; never on a platform vibration API directly.

| Member | Signature | Contract |
|---|---|---|
| `perform` | `fun perform(type: HapticType): HapticResult` | Requests one haptic event. Never throws. Returns as soon as the platform accepted the request — not when the pulse ends. |

Thread-safety: implementations shipped here are safe to call from any thread; the iOS one hops to
the main thread internally because UIKit requires it. A custom implementation should hold that same
guarantee, since callers in shared code have no way to know which thread they are on.

Implementing it yourself is expected — a settings-aware decorator is the common case
([`03-guide.md`](03-guide.md#respecting-a-user-setting)).

## `enum class HapticType`

Six semantic intensities, declared in this order:

| Constant | Meaning |
|---|---|
| `LIGHT` | Lightest tap — selection changes, ticking through a picker |
| `MEDIUM` | Moderate tap — a control committing, a toggle flipping |
| `HEAVY` | Strongest single tap — something landing with weight |
| `SUCCESS` | Operation completed — a short two-pulse pattern |
| `WARNING` | Completed with a caveat — two even pulses |
| `ERROR` | Operation failed — three pulses |

Every constant is supported on every target: there is no type that silently has no mapping
somewhere. The rendering differs per platform — see [`05-platform-notes.md`](05-platform-notes.md)
for the exact durations, amplitudes and generator calls.

The declaration order is part of the contract (impacts first, ascending; notifications second) and
is pinned by a test, so `entries` is safe to iterate for, say, a debug screen.

## `enum class HapticResult`

What `perform` made of the request.

| Constant | Meaning | Typical reaction |
|---|---|---|
| `PERFORMED` | The platform accepted the request | None. Not a promise that anything was felt |
| `UNAVAILABLE` | No vibration hardware (Android: no vibrator service, or `hasVibrator() == false`) | Stop offering a haptics preference — it will not change while the app runs |
| `PERMISSION_DENIED` | Android only: the app did not declare `android.permission.VIBRATE` | Fix the manifest. It is a build defect, not a runtime condition |

Ignoring the return value is a legitimate default — the type exists so that failure is *available*,
not so that every call site must branch on it.

## `fun noOpHapticFeedback(): HapticFeedback`

Returns a stateless implementation that does nothing and answers `UNAVAILABLE` for every type. The
same instance every time; comparing two results with `===` is true.

Use it as the injected instance when the user turned haptics off, or on a target where you have not
wired a real one, so shared call sites stay unconditional.

## `fun createHapticFeedback(context: Context): HapticFeedback` — Android only

Builds the Android implementation on top of the device's default vibrator.

- Lives in `androidMain`. There is deliberately **no** `expect`/`actual` pair: Android needs a
  `Context` and iOS needs nothing, so a common signature could only be a lie
  ([`02-getting-started.md`](02-getting-started.md#4-build-the-real-implementation-in-platform-code)).
- Retains `context.applicationContext`, so passing an `Activity` does not leak it.
- Holds nothing that requires releasing; there is no `close()` and no lifecycle to observe.
- Resolves the vibrator once, at construction. A device cannot grow a motor at runtime, so this is
  not re-checked per call — but the permission is, because a manifest change ships with a new build.

## `fun createHapticFeedback(): HapticFeedback` — iOS only

Builds the iOS implementation on top of `UIImpactFeedbackGenerator` and
`UINotificationFeedbackGenerator`. Lives in `iosMain`. Stateless, nothing to release, and every call
returns `PERFORMED` — see [`05-platform-notes.md`](05-platform-notes.md#ios) for why iOS cannot say
anything more truthful than that.

## `class RecordingHapticFeedback` — artifact `kmptoolkit-haptics-testing`

Package: `io.github.jamal_wia.kmptoolkit.haptics.testing`

```kotlin
public class RecordingHapticFeedback(
    public var result: HapticResult = HapticResult.PERFORMED,
) : HapticFeedback
```

| Member | Signature | Contract |
|---|---|---|
| `result` | `var result: HapticResult` | What `perform` returns. Mutable so one instance can change behavior mid-test |
| `events` | `val events: List<HapticType>` | Every requested type, oldest first. A snapshot — it does not change as more calls arrive |
| `perform` | `fun perform(type: HapticType): HapticResult` | Records `type`, then returns `result`. Recording happens regardless of `result` |
| `clear` | `fun clear()` | Drops the recording; leaves `result` untouched |

**Not thread-safe**, by design — see [`06-testing.md`](06-testing.md).

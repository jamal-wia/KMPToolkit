# kmptoolkit-haptics — Platform notes

What differs behind `HapticFeedback`, and the one thing your app must do that this library will not
do for you.

## Permissions and manifest entries

### Android — you must declare `android.permission.VIBRATE`

```xml
<manifest>
    <uses-permission android:name="android.permission.VIBRATE" />
</manifest>
```

**This library does not declare it.** Not an oversight — a repository-wide rule
([`../01-architecture.md`](../01-architecture.md#android-manifests)): a permission declared in a
library manifest is merged into every consuming app silently, showing up in a listing the library
author never sees and cannot justify. The decision belongs to the app.

Facts about this particular permission:

- It is a **normal** permission: granted at install time. There is no runtime prompt to show, no
  `requestPermissions` call to make, and no user-visible dialog. One manifest line is the entire
  task.
- The user cannot revoke it, so it cannot disappear mid-session. If it is missing, it is missing for
  the whole install.

**Behavior when it is missing:** every `perform` returns `HapticResult.PERMISSION_DENIED` and
nothing throws. The framework's `Vibrator.vibrate` throws `SecurityException`; the module catches it
at the exact call site and converts it. A decorative buzz must never be the thing that crashes a
checkout screen — but do treat the result as the build defect it is, and log it in debug builds
([`03-guide.md`](03-guide.md#reacting-to-the-result)).

### iOS — nothing

No permission, no entitlement, no `Info.plist` key, no user consent. `UIFeedbackGenerator` is
available to every app.

## Android

### Which vibrator

| API level | How the vibrator is resolved |
|---|---|
| 31+ (`S`) | `VibratorManager.defaultVibrator` — `VIBRATOR_SERVICE` is deprecated there |
| 24–30 | `getSystemService(VIBRATOR_SERVICE)` |

Resolution happens once, when you call `createHapticFeedback(context)`. If the service is missing
entirely — which happens on stripped-down system images and some emulators — every call reports
`UNAVAILABLE` rather than failing.

### How each type is rendered

| `HapticType` | Effect | Timings (ms) | Amplitude |
|---|---|---|---|
| `LIGHT` | one-shot | 20 | 128 (half) |
| `MEDIUM` | one-shot | 40 | device default |
| `HEAVY` | one-shot | 60 | device default |
| `SUCCESS` | waveform | 0, 20, 60, 40 | device default |
| `WARNING` | waveform | 0, 40, 80, 40 | device default |
| `ERROR` | waveform | 0, 60, 80, 60, 80, 60 | device default |

Waveform timings alternate **off, on, off, on…**, exactly as the platform reads them, which is why
every pattern starts with a `0`.

### The API-26 split — why the same type can feel different on two devices

`VibrationEffect` (and with it amplitude control) arrived in API 26. This library's `minSdk` is 24,
so both paths are live:

| API level | Call used | Consequence |
|---|---|---|
| 26+ | `vibrate(VibrationEffect)` — `createOneShot` / `createWaveform` | Amplitude is honored; `LIGHT` is genuinely softer |
| 24–25 | deprecated `vibrate(long)` / `vibrate(long[], int)` | Durations are honored, **amplitude is not expressible** — `LIGHT` differs from `MEDIUM` only by being shorter |

Nothing degrades to silence, and no call throws on the old path — the pulse is simply less
expressive. Both paths are covered by Robolectric tests at SDK 24, 30 and 34.

### Other Android facts worth knowing

- **`hasVibrator()` is checked before every request.** A device that reports no motor gets
  `UNAVAILABLE` rather than a successful-looking no-op, because `Vibrator.vibrate` on such a device
  returns quietly and would otherwise be indistinguishable from a real pulse.
- **The system can still swallow the vibration.** Do-not-disturb, "touch vibration" turned off in
  system settings, battery saver on some OEM builds — none of these are reported back, so
  `PERFORMED` remains the honest answer the platform gives.
- **A second request replaces the first.** Android does not queue vibrations; the newest call wins.
- **Amplitude is a request, not a command.** Many devices quantize it, and the user's own
  intensity setting scales it.

## iOS

### How each type is rendered

| `HapticType` | Generator | Style / type |
|---|---|---|
| `LIGHT` | `UIImpactFeedbackGenerator` | `.light` |
| `MEDIUM` | `UIImpactFeedbackGenerator` | `.medium` |
| `HEAVY` | `UIImpactFeedbackGenerator` | `.heavy` |
| `SUCCESS` | `UINotificationFeedbackGenerator` | `.success` |
| `WARNING` | `UINotificationFeedbackGenerator` | `.warning` |
| `ERROR` | `UINotificationFeedbackGenerator` | `.error` |

The exact feel of each is Apple's to define and changes between device generations. That is the
point of a semantic vocabulary: `SUCCESS` keeps meaning "success" when the hardware changes.

### Threading

UIKit's feedback generators must be used on the main thread, so `perform` dispatches to the main
queue itself and returns immediately. Callers in shared code therefore need no dispatcher and no
`suspend`. The consequence is that `PERFORMED` means "handed to UIKit", not "already felt" — by the
time `perform` returns, nothing has happened yet.

A generator is allocated per call. Apple recommends keeping one alive and calling `prepare()` ahead
of a *tight sequence* of haptics; these fire on discrete user actions, so a cached generator would
be main-thread-confined shared state for no measurable gain.

### What iOS cannot tell you

There is no API to ask whether the device has a Taptic Engine, whether the user disabled system
haptics, or whether a request was honored. Every call therefore returns `PERFORMED` — including on
the simulator, where nothing can possibly be felt. `UNAVAILABLE` and `PERMISSION_DENIED` are
**Android-only outcomes** in practice; a `when` over `HapticResult` in shared code still has to
handle them, and doing nothing there is a perfectly good answer.

## Behavior identical on both platforms

The type vocabulary, the "never throws" guarantee, callability from any thread, the absence of
queueing, and the fact that `perform` returns before the pulse ends.

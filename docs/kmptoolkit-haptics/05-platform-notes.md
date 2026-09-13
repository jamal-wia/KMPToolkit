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

### Attribution — touch feedback, or none

What a vibration is declared to be *for* decides how the user's settings treat it. Each instance
has one attribution, chosen in the factory: `createHapticFeedback(context)` is `TOUCH`,
`createHapticFeedback(context, HapticAttribution.NONE)` is none.

| API level | `TOUCH` | `NONE` |
|---|---|---|
| 33+ (`TIRAMISU`) | `VibrationAttributes` with `USAGE_TOUCH` | `vibrate(VibrationEffect)` — the framework fills in empty attributes, `USAGE_UNKNOWN` |
| 26–32 | `AudioAttributes` with `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` | `vibrate(VibrationEffect)` with no attributes |
| 24–25 | the same `AudioAttributes` on the deprecated overloads | the deprecated overloads with no attributes |

**`TOUCH`** — the default. The platform scales the vibration by the user's touch-feedback intensity
slider and silences it when they turn touch feedback off: a user who asked for no touch vibration
gets none. `VibrationAttributes` is the modern classification and did not exist before API 33; the
`AudioAttributes` pair is the closest equivalent the older overloads accept.

**`NONE`** — an unattributed vibration, classified `USAGE_UNKNOWN`. The touch-feedback switch and
slider do not apply to it, and Do Not Disturb filters it differently. Choose it for a vibration
that is not a reaction to the user's touch and must reach them regardless of that switch — an
attention cue for someone who put the device down — or when adopting this module in an app that
has been calling `Vibrator.vibrate(effect)` directly, whose vibrations then behave exactly as
before.

An app whose vibrations mean both things builds two instances. Every cell of the table is asserted
by a Robolectric test.

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

- **`isAvailable` is `hasVibrator()`**, read live, so it answers without playing anything. It says
  nothing about the permission or the user's settings — those only surface from `perform`.
- **`hasVibrator()` is checked before every request.** A device that reports no motor gets
  `UNAVAILABLE` rather than a successful-looking no-op, because `Vibrator.vibrate` on such a device
  returns quietly and would otherwise be indistinguishable from a real pulse.
- **The system can still swallow the vibration.** Do-not-disturb, "touch vibration" turned off in
  system settings, battery saver on some OEM builds — none of these are reported back, so
  `PERFORMED` remains the honest answer the platform gives.
- **A second request replaces the first.** Android does not queue vibrations; the newest call wins.
- **Amplitude is a request, not a command.** Many devices quantize it, and the user's own
  intensity setting scales it — see attribution above, which is what makes that scaling apply.
- **The framework throws more than `SecurityException`.** An effect the service will not accept
  raises `IllegalArgumentException`, and OEM builds surface a dead vibrator-service binder as a
  `RuntimeException`. Both come back as `HapticResult.FAILED`; neither escapes `perform`. `Error`
  (an `OutOfMemoryError`, a linkage failure) is deliberately **not** caught — that is not a haptics
  problem to hide.

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
queue itself and returns immediately — a call from a background thread is covered by a test that
really originates off-main and then pumps the main runloop. Callers in shared code therefore need no dispatcher and no
`suspend`. The consequence is that `PERFORMED` means "handed to UIKit", not "already felt" — by the
time `perform` returns, nothing has happened yet.

A generator is allocated per call. Apple recommends keeping one alive and calling `prepare()` ahead
of a *tight sequence* of haptics; these fire on discrete user actions, so a cached generator would
be main-thread-confined shared state for no measurable gain.

### What iOS cannot tell you

There is no API to ask whether the device has a Taptic Engine, whether the user disabled system
haptics, or whether a request was honored. `isAvailable` is therefore always `true`, and every call
returns `PERFORMED` — including on
the simulator, where nothing can possibly be felt. `UNAVAILABLE`, `PERMISSION_DENIED` and `FAILED`
are **Android-only outcomes** in practice; a `when` over `HapticResult` in shared code still has to
handle them, and doing nothing there is a perfectly good answer.

## Behavior identical on both platforms

The type vocabulary, the "never throws" guarantee, callability from any thread, the absence of
queueing, and the fact that `perform` returns before the pulse ends.

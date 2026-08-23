# kmptoolkit-accelerometer — Platform notes

What differs behind `Accelerometer`, and the manifest decisions this library deliberately leaves to
your app.

## Permissions and manifest entries

**This library declares neither a permission nor a `<uses-feature>` entry of its own.** Not an
oversight — the same repository-wide rule that governs every other module
([`../01-architecture.md`](../01-architecture.md#android-manifests)): anything declared in a
library manifest merges into every consuming app silently, on a decision that belongs to the app,
not the library.

### At the default sampling interval — nothing to declare

No Android permission and no iOS entitlement or `Info.plist` entry is needed to read the
accelerometer at the default 200 ms interval, or at any interval down to 5 ms (200 Hz). Most
consumers add nothing beyond the dependency itself.

### `HIGH_SAMPLING_RATE_SENSORS` (Android 31+)

Starting with API 31, requesting sensor data faster than 200 Hz requires
`android.permission.HIGH_SAMPLING_RATE_SENSORS` in the consuming app's manifest:

```xml
<manifest>
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
</manifest>
```

It is a **normal** permission — granted at install time, no runtime prompt. Without it, the
platform silently caps delivery at 200 Hz regardless of the `samplingInterval` you request; nothing
throws, and this module does not detect or report the cap. Only relevant if you pass a
`samplingInterval` below 5 ms — the default (200 ms) and most reasonable use cases never approach
this limit.

### Should you declare `<uses-feature android:name="android.hardware.sensor.accelerometer">`?

This library never will — see above — but you might want to, in **your own** manifest, if you
publish to the Play Store and want it to hide your app from devices that have no accelerometer at
all (some low-end tablets, some Android TV and Wear builds):

```xml
<manifest>
    <uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />
</manifest>
```

Set `android:required="false"` unless the accelerometer is truly load-bearing for your app —
`required="true"` (or omitting the attribute, which defaults to `true`) removes your app from the
Play Store listing entirely on devices without the sensor. Either way, this module's `isAvailable`
is the runtime check that actually matters: a `<uses-feature>` entry only affects store filtering,
never what `observe()` does at runtime.

iOS needs no counterpart: `CMMotionManager` is available in every app; `accelerometerAvailable`
is the runtime check.

## Axes

Both platforms report the same axis convention, hardware-fixed rather than tied to the interface
orientation:

| Axis | Direction |
|---|---|
| `x` | Right, along the short side of the device |
| `y` | Up, along the long side of the device |
| `z` | Out of the screen, toward the user |

A device lying flat, screen up, and still reads approximately `(0, 0, +9.8)` — standard gravity,
since neither implementation removes it. Screen down reads approximately `(0, 0, -9.8)`. Rotating
the device does **not** rotate these axes; they are fixed to the chassis, not to what is currently
"up" on screen.

## Unit conversion (iOS)

Core Motion's `CMAcceleration` reports in **g** (1 g ≈ 9.81 m/s²), not m/s². Every sample this
module produces on iOS is scaled by a fixed constant (9.81) before it reaches `AccelerometerSample`
— Android already reports in m/s² and needs no conversion. The two platforms therefore report
numerically comparable values for the same physical motion, at the cost of a small, fixed rounding
difference from Apple's more precise standard-gravity constant (9.80665); that difference is far
smaller than sensor noise on any consumer device.

## Sensor lifecycle

| | Android | iOS |
|---|---|---|
| Registered by | `SensorManager.registerListener` | `CMMotionManager.startAccelerometerUpdatesToQueue` |
| Released by | `SensorManager.unregisterListener` | `CMMotionManager.stopAccelerometerUpdates` |
| Delivery queue/thread | The thread `registerListener` was called from (this module supplies no `Handler`, so the caller's own looper) | `NSOperationQueue.mainQueue`, explicitly |
| Registration scope | Per collection of `observe()` — two concurrent collections register two listeners | Per collection of `observe()` — two concurrent collections each call `startAccelerometerUpdatesToQueue`, and the second call replaces the first's handler internally, per Core Motion's own documented behavior |

The Android/iOS asymmetry in the last row is a Core Motion limitation, not a choice this module
makes: `CMMotionManager` supports only one active update handler at a time. If your app needs two
independent collectors on iOS, share one `Accelerometer` instance and fan its `Flow` out with
`shareIn` rather than calling `observe()` twice.

## Behavior identical on both platforms

`isAvailable` resolved once at construction, `observe()` never completing on its own, and no
values being filtered, smoothed, or interpreted before they reach `AccelerometerSample`.

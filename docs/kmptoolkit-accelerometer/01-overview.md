# kmptoolkit-accelerometer — Overview

Two types: an `AccelerometerSample` — one reading, in m/s² — and an `Accelerometer` your shared
code calls to get a live stream of them.

```kotlin
class TiltPresenter(private val accelerometer: Accelerometer) {

    fun observeTilt(): Flow<AccelerometerSample> = accelerometer.observe()
}
```

That call site is the whole point: it does not know about `SensorManager`, about the three
different Android sensor-delay constants, about `CMMotionManager`, or about the fact that Core
Motion reports in g while Android reports in m/s².

## The problem it solves

Reading raw acceleration is small per platform but genuinely different in shape:

- **Android** registers a `SensorEventListener` on a `SensorManager`, unregisters it later, and
  reports values already in m/s².
- **iOS** starts a `CMMotionManager` update loop onto a queue, stops it later, and reports values
  in **g** — a different unit that must be scaled before it means the same thing as the Android
  number.
- Both are push-based APIs with a manual register/unregister lifecycle, which maps naturally onto
  a cold `Flow`: collecting starts the sensor, cancelling stops it, and nothing has to be wired up
  or torn down by hand at the call site.

This module collapses both into one interface and one sample type, with one unit.

## What this is **not**

- **Not an orientation, motion, or gesture detector.** It emits raw acceleration and interprets
  nothing — no "face down", no "shaken", no "still", no step counting. What a reading *means* is
  domain logic that belongs to your app, not to a sensor wrapper; build it on top of `observe()`.
- **Not a gyroscope, magnetometer, or fused rotation-vector API.** If you need orientation, look at
  `TYPE_ROTATION_VECTOR` (Android) or `CMDeviceMotion` (iOS) directly — a different sensor with a
  different fusion story on each platform, out of scope here.
- **Not a permission or feature requester.** It declares neither an Android permission nor a
  `<uses-feature>` entry of its own — a deliberate repository-wide rule
  ([`../01-architecture.md`](../01-architecture.md#android-manifests)). See
  [`05-platform-notes.md`](05-platform-notes.md) for what that means in practice (short answer:
  usually nothing to add).
- **Not calibrated or filtered.** No low-pass filter, no bias correction, no gravity removal.
  Values pass through as the platform reports them; smoothing or isolating linear acceleration from
  gravity is your call to make, at whatever cutoff your use case needs.
- **Not tied to Compose or any UI framework.** It is plain Kotlin.

## When to use it

Use it when shared Kotlin code needs raw motion data — a step-detection algorithm, a shake gesture,
a "device put face down" heuristic, a game control scheme — and you want that algorithm to run
identically on both platforms without hand-writing the sensor plumbing twice.

If only one platform's UI layer ever reads the accelerometer, calling `SensorManager` or
`CMMotionManager` directly there is simpler. The indirection pays for itself when the logic that
interprets the readings lives in common code.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — choosing a sampling interval, availability, mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, `<uses-feature>`, axes, sampling
- [`06-testing.md`](06-testing.md) — `ScriptedAccelerometer` and what to assert with it

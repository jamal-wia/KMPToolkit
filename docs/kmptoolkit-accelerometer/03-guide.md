# kmptoolkit-accelerometer — Guide

Scenarios in order of how often you will need them, then the mistakes worth naming.

## Choosing a sampling interval

Both factories take a `samplingInterval: Duration`, defaulting to 200 ms (~5 Hz) on both
platforms — the cheapest rate each platform offers, and the one the donor implementation this
module was ported from hardcoded. Pass a shorter interval when your algorithm needs finer-grained
data:

```kotlin
import kotlin.time.Duration.Companion.milliseconds

// ~50 Hz — closer to what a step-detection algorithm typically wants.
val accelerometer = createAccelerometer(context, samplingInterval = 20.milliseconds)
```

A shorter interval means more wakeups and more battery, on both platforms — pick the loosest
interval your algorithm tolerates, not the tightest one available. On Android, going below 5 ms
(above 200 Hz) needs a manifest permission your app must declare; see
[`05-platform-notes.md`](05-platform-notes.md#high-sampling-rate-sensors-android-31).

The interval is a *request*, not a guarantee, on both platforms — the OS may deliver faster or
slower depending on what else is registered for the same sensor and what the hardware batches.

## Reading `isAvailable` correctly

A device with no accelerometer does not throw and does not report an error through `observe()` —
it registers nothing and the returned `Flow` simply never emits, forever, until you cancel the
collecting coroutine. That is indistinguishable, from inside `collect { }`, from a device that has
an accelerometer sitting still with no data queued yet at process start.

```kotlin
val accelerometer: Accelerometer = createAccelerometer(context)

if (!accelerometer.isAvailable) {
    // Show a fallback control — a manual toggle, a message — instead of waiting for readings
    // that will never arrive.
}
```

Check `isAvailable` once, before you decide whether to collect at all. Do not use "no emission
yet" as a substitute — on a slow-starting sensor that path is also silent for a moment, and there
is no timeout in this module that would let you tell the two apart.

## Converting a sample into something meaningful

This module stops at the raw reading on purpose ([`01-overview.md`](01-overview.md#what-this-is-not)).
A magnitude check is typically the first step in anything built on top of it:

```kotlin
import kotlin.math.sqrt

fun AccelerometerSample.magnitude(): Float = sqrt(x * x + y * y + z * z)

// Roughly free-fall: magnitude collapses toward zero.
fun AccelerometerSample.isFreeFalling(threshold: Float = 2f): Boolean = magnitude() < threshold
```

A device at rest reads a magnitude close to standard gravity (~9.8), regardless of orientation —
gravity is always present in the raw signal on both platforms, since neither implementation
subtracts it. Isolating linear acceleration from gravity (a low-pass or high-pass filter over the
stream) is your algorithm's job.

## Testing without a device

`ScriptedAccelerometer`, from `kmptoolkit-accelerometer-testing`, replays a canned list of samples
to every collector and lets you flip `isAvailable`. See [`06-testing.md`](06-testing.md) for the
full picture, including the trade-off it makes to stay a simple, cold, single-script fixture rather
than a live multi-collector simulator.

## Mistakes worth naming

- **Collecting without checking `isAvailable` first**, then treating silence as "still loading". A
  device with no accelerometer is silent forever, not eventually.
- **Forgetting that `observe()` never completes.** A `first()` or `single()` call on it will hang
  on a device that reports slowly (or never, if unavailable) — use `take(n)` or collect in a
  coroutine you own and can cancel.
- **Assuming both platforms deliver at exactly the same rate.** `samplingInterval` is a request on
  both; treat differences of a few milliseconds between platforms as normal, not as a bug.
- **Reading gravity out of the signal as if it were linear motion.** Every sample includes gravity
  unless you filter it out yourself — see above.
- **Holding an `Activity` to build the instance later.** Build it once with the application context
  (the Android factory takes the application context out of whatever you hand it) and inject it.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — the exact contract of every public symbol
- [`06-testing.md`](06-testing.md) — asserting on accelerometer readings without a device

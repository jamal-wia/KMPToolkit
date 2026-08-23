# kmptoolkit-proximity — Overview

Two types: a `ProximitySensor` your shared code observes, and `ProximityRule`, the pure fold from a
raw distance reading to the boolean the sensor is really answering.

```kotlin
class CallPresenter(private val proximity: ProximitySensor) {

    fun observeScreenDimming(): Flow<Boolean> = proximity.observe()
}
```

That call site is the whole point: it does not know about `Sensor.TYPE_PROXIMITY`, about
`SensorEventListener`, or about the fact that iOS exposes nothing equivalent at all.

## The problem it solves

An app that wants to know "is the phone against the user's ear" normally has to write a
`SensorEventListener`, register and unregister it around a lifecycle, and interpret whatever the
hardware reports — which is where it gets subtle:

- **Most proximity sensors are binary.** They report either `0` or their own maximum range, not a
  real centimetre figure. That maximum differs per device, so "near" has to be judged against the
  sensor's own range, not a flat threshold — the one piece of logic in this module worth testing on
  its own, in [`ProximityRule`](04-api-reference.md#object-proximityrule).
- **The sensor lies in one direction.** It is optical and built for the phone-to-ear case: dark or
  matte surfaces reflect almost no infrared, some devices only service it during calls, and a real
  phone lying flat on a table has been seen reporting "far". See
  [Trusting the answer](#trusting-the-answer) below — this is a real property of the hardware, not
  a bug this module works around.
- **iOS has nothing to call.** Core Motion exposes no proximity API, and `UIDevice`'s own proximity
  monitoring only exists on iPhone and is coupled to blanking the screen — not a seam this library
  can build a `Flow<Boolean>` on top of.

This module collapses the Android side into one interface plus the one testable decision, and is
honest on iOS that there is nothing to report.

## Trusting the answer

- **`true` is decisive** — only something physically near the screen produces it, and it keeps
  holding while the device vibrates.
- **`false` proves nothing.** Treat a negative reading as "no reading", not as "confirmed far". See
  [`05-platform-notes.md`](05-platform-notes.md) for why, and for what a tablet without any sensor
  reports.

## What this is **not**

- **Not a distance sensor.** `observe()` reports a boolean, because that is all most hardware really
  answers. If you need the raw value, you want `android.hardware.SensorManager` directly, in Android
  code — `ProximityRule.isNear` is what this module offers in its place, and it is a plain function
  you can call yourself with your own reading.
- **Not a lifecycle-aware sensor.** `observe()` is a cold `Flow` — the sensor is registered when
  collection starts and released when it ends. Tying that to an Android `Activity`/`Lifecycle` is
  your call site's job, the same way any other cold `Flow` is.
- **Not a permission requester.** `TYPE_PROXIMITY` needs no Android permission, so there is nothing
  to request and nothing declared in this module's manifest either way.
- **Not tied to Compose or any UI framework.** It is plain Kotlin.
- **Not available on iOS in any real sense.** `createProximitySensor()` exists there so shared code
  compiles unconditionally, but `isAvailable` is always `false` and `observe()` never emits. See
  [`05-platform-notes.md`](05-platform-notes.md).

## When to use it

Use it when shared Kotlin code needs to react to "something is against the screen" — dimming the
display during a call, muting a speaker, pausing a video call's camera — without writing the sensor
plumbing itself and without silently assuming the feature exists on both platforms.

If only your Android UI layer ever reads the sensor, calling `SensorManager` directly there is a
reasonable choice too. The indirection pays for itself when the decision lives in common code, or
when you want the [testing fixture](06-testing.md) to drive it without a device.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — reacting to readings, tablets, mistakes worth naming
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — hardware reality on Android, why iOS is absent
- [`06-testing.md`](06-testing.md) — `FakeProximitySensor` and what to assert with it

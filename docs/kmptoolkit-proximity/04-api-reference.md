# kmptoolkit-proximity — API reference

Package: `io.github.jamal_wia.kmptoolkit.proximity`

This file mirrors the committed ABI dumps at `kmptoolkit-proximity/api/` and
`kmptoolkit-proximity-testing/api/`. If they disagree, the dumps are authoritative and this file is
a bug.

The whole public surface is one interface, one object, and one platform factory per target, plus
one class in the `-testing` artifact.

## `interface ProximitySensor`

The seam. Depend on this in shared code; never on `SensorManager` or any other platform sensor API
directly.

| Member | Signature | Contract |
|---|---|---|
| `isAvailable` | `val isAvailable: Boolean` | Whether the device has a usable proximity sensor. `false` on every iOS device and on a sensor reporting `maximumRange == 0` |
| `observe` | `fun observe(): Flow<Boolean>` | Emits whether something is near the screen, only on change. Cold: registers on collection start, releases on collection end. Never emits when `isAvailable` is `false` |

Thread-safety: implementations shipped here are safe to collect from any coroutine context; the
Android one hops nothing itself since `SensorEventListener` callbacks arrive on whichever thread the
registering `Looper` runs on, which for the default `SENSOR_DELAY_NORMAL` registration used here is
the calling thread's own looper.

## `object ProximityRule`

The fold from a raw distance reading to the boolean `ProximitySensor` reports. Pure, platform-free,
directly testable.

| Member | Signature | Contract |
|---|---|---|
| `NEAR_CM` | `const val NEAR_CM: Float = 5.0f` | The distance, in centimetres, above which a reading counts as "far". Matches the figure platforms use for the call-screen blank |
| `isNear` | `fun isNear(distanceCm: Float, maxRangeCm: Float): Boolean` | `distanceCm < minOf(NEAR_CM, maxRangeCm)` — compared against the smaller of the two so a sensor whose own maximum is below five centimetres is not misread |

`isNear` is what the Android implementation calls internally per `SensorEvent`; it is public
precisely so you can call it yourself against a raw reading you obtained some other way, and so it
is testable without any hardware or fake — it needs neither `ProximitySensor` nor
`FakeProximitySensor` to exercise.

## `fun createProximitySensor(context: Context): ProximitySensor` — Android only

Builds the Android implementation over `Sensor.TYPE_PROXIMITY`.

- Lives in `androidMain`. There is deliberately **no** `expect`/`actual` pair: Android needs a
  `Context` and iOS needs nothing, so a common signature could only be a lie
  ([`02-getting-started.md`](02-getting-started.md#3-build-the-real-implementation-in-platform-code)).
- Retains `context.applicationContext`, so passing an `Activity` does not leak it.
- Resolves the sensor once, at construction — `isAvailable` reflects that single lookup for the
  life of the instance, since a device cannot grow a proximity sensor at runtime.
- No permission is required or declared; see [`05-platform-notes.md`](05-platform-notes.md).

## `fun createProximitySensor(): ProximitySensor` — iOS only

Builds the iOS implementation. Lives in `iosMain`. Returns the same stateless instance every time —
comparing two results with `===` is true. `isAvailable` is always `false` and `observe()` always
returns an empty, already-completed `Flow` — see
[`05-platform-notes.md`](05-platform-notes.md#ios) for why.

## `class FakeProximitySensor` — artifact `kmptoolkit-proximity-testing`

Package: `io.github.jamal_wia.kmptoolkit.proximity.testing`

```kotlin
public class FakeProximitySensor(
    isAvailable: Boolean = true,
) : ProximitySensor
```

| Member | Signature | Contract |
|---|---|---|
| `isAvailable` | `override var isAvailable: Boolean` | What `ProximitySensor.isAvailable` reports. Mutable, so one instance can change mid-test |
| `emitCount` | `val emitCount: Int` | How many times `emit` has been called, regardless of `isAvailable` |
| `emit` | `fun emit(near: Boolean)` | Publishes `near` as the next reading. Always succeeds, never suspends |
| `observe` | `override fun observe(): Flow<Boolean>` | Replays the most recent `emit`-ed value to a new collector while `isAvailable` is `true`; an already-completed empty `Flow` while it is `false` |

Not a hardware simulation — see [`06-testing.md`](06-testing.md) for the two documented divergences
from the real, cold, per-collection sensor registration.

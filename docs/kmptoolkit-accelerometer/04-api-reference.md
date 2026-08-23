# kmptoolkit-accelerometer — API reference

Package: `io.github.jamal_wia.kmptoolkit.accelerometer`

This file mirrors the committed ABI dumps at `kmptoolkit-accelerometer/api/` and
`kmptoolkit-accelerometer-testing/api/`. If they disagree, the dumps are authoritative and this
file is a bug.

The whole public surface of the production artifact is one data class, one interface, and one
platform factory per target, plus one class in the `-testing` artifact.

## `data class AccelerometerSample`

One reading: acceleration along the device's three hardware-fixed axes, in m/s².

| Property | Type | Meaning |
|---|---|---|
| `x` | `Float` | Acceleration along the short side of the device, right-positive |
| `y` | `Float` | Acceleration along the long side of the device, up-positive |
| `z` | `Float` | Acceleration out of the screen, toward the user, positive |

A device lying still face-up reads `z ≈ +9.8`; face-down, `z ≈ -9.8`. See
[`05-platform-notes.md`](05-platform-notes.md#axes) for the exact per-platform axis contract.

## `interface Accelerometer`

The seam. Depend on this in shared code; never on a platform sensor API directly.

| Member | Signature | Contract |
|---|---|---|
| `isAvailable` | `val isAvailable: Boolean` | Whether the device has an accelerometer at all. Resolved once, at construction — a device cannot grow a motion sensor at runtime |
| `observe` | `fun observe(): Flow<AccelerometerSample>` | Cold: registers the platform sensor when collection starts, releases it when the collecting coroutine is cancelled. Never completes on its own. Emits nothing, forever, when `isAvailable` is `false` |

Thread-safety: the returned `Flow` is safe to collect from any coroutine context; the platform
implementations do their own hop to whatever thread the underlying API requires.

## `fun createAccelerometer(context: Context, samplingInterval: Duration = 200.milliseconds): Accelerometer` — Android only

Builds the Android implementation on top of `SensorManager` and `TYPE_ACCELEROMETER`.

- Lives in `androidMain`. There is deliberately **no** `expect`/`actual` pair: Android needs a
  `Context` and iOS needs nothing, so a common signature could only be a lie
  ([`../01-architecture.md`](../01-architecture.md#platform-factories-not-expect-fun)).
- Retains `context.applicationContext`, so passing an `Activity` does not leak it.
- Resolves the `Sensor` once, at construction. `samplingInterval` is converted to microseconds and
  passed to `SensorManager.registerListener` on every collection — it can differ between two
  concurrent collections of the same instance's `observe()`, since each registers its own listener.
- No permission is required at the default interval. Requesting faster than 200 Hz needs
  `android.permission.HIGH_SAMPLING_RATE_SENSORS` on API 31+ — see
  [`05-platform-notes.md`](05-platform-notes.md#high-sampling-rate-sensors-android-31).

## `fun createAccelerometer(samplingInterval: Duration = 200.milliseconds): Accelerometer` — iOS only

Builds the iOS implementation on top of `CMMotionManager`. Lives in `iosMain`.

- `samplingInterval` is converted to seconds and assigned to
  `CMMotionManager.accelerometerUpdateInterval` before each collection starts updates.
- Every sample is scaled from g (what Core Motion reports) to m/s² before it reaches
  `AccelerometerSample`, so both platforms speak the same unit — see
  [`05-platform-notes.md`](05-platform-notes.md#unit-conversion-ios).
- No permission, entitlement, or `Info.plist` entry is involved.

## `class ScriptedAccelerometer` — artifact `kmptoolkit-accelerometer-testing`

Package: `io.github.jamal_wia.kmptoolkit.accelerometer.testing`

```kotlin
public class ScriptedAccelerometer(
    public var isAvailable: Boolean = true,
    public var samples: List<AccelerometerSample> = emptyList(),
) : Accelerometer
```

| Member | Signature | Contract |
|---|---|---|
| `isAvailable` | `var isAvailable: Boolean` | What `Accelerometer.isAvailable` reports, and whether `observe()` emits anything at all |
| `samples` | `var samples: List<AccelerometerSample>` | What every collection of `observe()` replays, oldest first |
| `registrations` | `val registrations: Int` | How many times `observe()` has been collected, active or not |
| `activeCollectors` | `val activeCollectors: Int` | How many of those registrations are still collecting right now |
| `observe` | `fun observe(): Flow<AccelerometerSample>` | Replays `samples` to a fresh collector, then suspends until cancelled — never completing, matching the real contract |

**Not thread-safe**, by design — see [`06-testing.md`](06-testing.md).

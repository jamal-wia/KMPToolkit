# kmptoolkit-proximity — Platform notes

What differs behind `ProximitySensor`, and the hardware reality that shapes both implementations.

## Permissions and manifest entries

Neither platform needs anything. `Sensor.TYPE_PROXIMITY` is not a runtime- or install-time-gated
sensor on Android, so this module declares no permission and there is nothing for your app to add
either — unlike `kmptoolkit-haptics`' `VIBRATE`, there is no missing-permission failure mode to
document here.

## Android

### Sensor resolution

`createProximitySensor(context)` resolves `Sensor.TYPE_PROXIMITY` once, at construction, via
`SensorManager.getDefaultSensor`. A sensor reporting `maximumRange == 0` — which happens on some
low-end and virtual devices — is treated as absent rather than taken at its word, because it can
never produce a "near" reading and reporting it as available would hand consumers a permanent,
silently misleading `false`.

### Why the sensor is mostly binary

Most `TYPE_PROXIMITY` implementations are optical, not a real rangefinder: they answer either `0`
(near) or their own `maximumRange` (far), with nothing meaningful in between. `ProximityRule.isNear`
is written for that shape — compared against the smaller of `NEAR_CM` and the sensor's own maximum,
so a unit whose maximum happens to be below five centimetres is not misread as reporting "far" when
it is really answering at the top of its own scale. A handful of devices do report real centimetre
distances; the same comparison handles that case correctly too, since the two bounds simply
coincide less often.

### Event delivery

`observe()` registers a `SensorEventListener` at `SensorManager.SENSOR_DELAY_NORMAL` when
collection starts and unregisters it when collection ends — nothing is registered between
collections, and nothing is left registered if a collector forgets to cancel its scope (it is,
however, still registered as long as that scope is open; see
[`03-guide.md`](03-guide.md#mistakes-worth-naming)). The platform reports only on change, so a
registered listener that never sees a transition costs nothing beyond holding the registration —
readings can legitimately be minutes apart.

`distinctUntilChanged` is applied internally, so two consecutive identical readings from the
hardware do not produce two emissions.

### Tablets

Proximity sensing exists for the phone-to-ear case; a tablet built to never be held to an ear
commonly ships no `TYPE_PROXIMITY` sensor at all. `isAvailable` reports `false` there — the exact
same signal a phone with a broken or zero-range sensor produces. There is no way to distinguish "no
sensor exists" from "the sensor exists but reports unusable data" from this API, and no useful
reason for a consumer to want to.

## iOS

### Why this module reports absent

There is no proximity API this library can build a `Flow<Boolean>` on:

- **Core Motion** exposes accelerometer, gyroscope, magnetometer and device-motion data, but no
  proximity sensor API at all.
- **`UIDevice.proximityMonitoringEnabled`** exists, but only on iPhone (not iPad), and it is coupled
  to the system automatically blanking the screen while a call is active rather than to a general
  "something is near" signal an app can read independently. Adopting it would mean a feature that
  silently does nothing on iPad and behaves differently in kind, not just in accuracy, from the
  Android implementation — worse than reporting absent honestly.

`createProximitySensor()` therefore always returns an instance with `isAvailable == false` and an
`observe()` that never emits. This is permanent, not a placeholder for a future iOS API this module
might adopt later — see [`03-guide.md`](03-guide.md#mistakes-worth-naming).

## Behavior identical on both platforms

The interface shape, the "cold, event-driven" contract, and the fact that a `false`/absent reading
is never proof of anything — see [`01-overview.md`](01-overview.md#trusting-the-answer).

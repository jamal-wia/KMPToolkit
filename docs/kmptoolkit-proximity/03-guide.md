# kmptoolkit-proximity — Guide

Scenarios in order of how often you will need them, then the mistakes worth naming.

## Checking availability before observing

```kotlin
if (proximity.isAvailable) {
    proximity.observe().onEach { near -> /* ... */ }.launchIn(scope)
}
```

Not required — `observe()` on an unavailable sensor simply never emits, so collecting it
unconditionally is safe — but checking first lets you skip building UI state nobody will ever
receive, or fall back to a different signal (a hardware mute button, a manual toggle) on a device
that has none.

## Reacting to a reading

```kotlin
proximity.observe()
    .onEach { near -> if (near) dimScreen() else undimScreen() }
    .launchIn(scope)
```

Two things worth keeping in mind, both from [`01-overview.md`](01-overview.md#trusting-the-answer):

- **Act on `true` immediately.** It is the trustworthy half of the signal.
- **Do not treat `false` as "confirmed far away".** A dark case, a matte surface, or a device that
  only services the sensor during calls can all produce it while something is genuinely near. If
  your feature has a real cost to getting this wrong (muting audio, say), bias toward the version
  that is annoying when wrong rather than the version that is unsafe when wrong.

## Combining with call state

The sensor answers "is something near", not "is the user on a call" — that correlation is yours to
build:

```kotlin
combine(callState, proximity.observe()) { call, near -> call.isActive && near }
    .distinctUntilChanged()
    .onEach { shouldDim -> if (shouldDim) dimScreen() else undimScreen() }
    .launchIn(scope)
```

`observe()` already applies its own `distinctUntilChanged` internally on Android — repeated
identical readings from the sensor are not re-emitted — but the combined stream above can still
repeat once `callState` changes on its own, which is what the second `distinctUntilChanged` is for.

## Tablets and other devices with no sensor at all

`TYPE_PROXIMITY` exists for the phone-to-ear case; a device built to never be held to an ear often
has no such sensor. `isAvailable` reports `false` there, the same as it does for a phone sensor
whose own maximum range came back as zero — from a consumer's point of view the two cases are
indistinguishable and do not need to be. See [`05-platform-notes.md`](05-platform-notes.md#tablets).

## Testing your own code that depends on `ProximitySensor`

Use `FakeProximitySensor` from `kmptoolkit-proximity-testing` rather than a stub you write
yourself — see [`06-testing.md`](06-testing.md) for what it can and cannot stand in for.

## Mistakes worth naming

- **Treating `false` as "the user is not on a call".** See
  [Reacting to a reading](#reacting-to-a-reading) — a negative reading is not a confirmed negative.
- **Assuming iOS behaves like an Android device with no sensor "for now".** It always will; there is
  no iOS API this module could adopt later that would change that. Design the iOS experience around
  `isAvailable == false` being permanent, not a gap waiting to be filled.
- **Collecting `observe()` without ever unsubscribing.** It is a cold `Flow` precisely so that
  scoping it to a lifecycle (`viewModelScope`, a `Job` you cancel) actually stops the underlying
  `SensorEventListener` registration. A collector that never cancels keeps the sensor registered for
  the life of the process.
- **Calling `ProximityRule.isNear` with a threshold you invented.** The five-centimetre constant is
  not arbitrary — it matches the figure platforms themselves use for the call-screen blank. If your
  feature genuinely needs a different distance, that is a product decision to make explicitly, not
  a number to tune until a demo looks right.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — the exact contract of every public symbol
- [`06-testing.md`](06-testing.md) — asserting on proximity without a device

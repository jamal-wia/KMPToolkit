# kmptoolkit-flashlight — Guide

Scenarios in order of how often you will need them, then the mistakes worth naming.

## Choosing a `FlashPattern`

The vocabulary is deliberately small — two rhythms, picked by *purpose*, not by "how noticeable
should this be":

| Pattern | Use it for | Rhythm |
|---|---|---|
| `Attention` | A quick flash to accompany a louder cue — noticed, then over | 120 ms on, 180 ms off |
| `Blink` | A device left face-down: seen from across a room, read as deliberate | 600 ms on, 400 ms off |

Both patterns keep looping until `stop`; neither is a fixed-length animation. A caller that wants
"flash three times and stop" starts the pattern and calls `stop` itself after the interval it
wants — this module has no built-in duration or repeat count.

## Checking availability

```kotlin
if (flashlight.isAvailable) {
    flashlight.start(FlashPattern.Blink)
} else {
    fallBackToAnotherCue()
}
```

Checking first is optional, not required: every call is already a silent no-op on a device with no
flash unit. `isAvailable` exists for callers that want to choose a different cue up front rather
than fire one that does nothing. It is a property of the device, checked once at construction — a
phone cannot grow a torch at runtime.

## Respecting a user setting

The module does not remember preferences. Two ways to honor one, both fine:

**Swap the instance** — simplest when the setting is read once, at startup:

```kotlin
val flashlight: Flashlight =
    if (settings.flashCueEnabled) createFlashlight(context) else noOpFlashlight()
```

**Decorate** — when the setting can change while the app is running:

```kotlin
class SettingsAwareFlashlight(
    private val delegate: Flashlight,
    private val isEnabled: () -> Boolean,
) : Flashlight {

    override val isAvailable: Boolean get() = isEnabled() && delegate.isAvailable

    override fun start(pattern: FlashPattern) {
        if (isEnabled()) delegate.start(pattern)
    }

    override fun stop() = delegate.stop()
}
```

`Flashlight` is a plain interface with three members precisely so that a decorator costs a handful
of lines. `stop` always delegates unconditionally — a setting flipped off mid-blink must still be
able to turn the torch off, which is the one call this module promises never fails silently in a
way that matters.

## Re-arming instead of stacking

```kotlin
flashlight.start(FlashPattern.Blink)
// ... later, without calling stop() first:
flashlight.start(FlashPattern.Attention)
```

A second `start` replaces the running pattern; it does not layer a second blink on top of the
first. A caller reacting to a fast sequence of events — the user going idle, then an urgent
reminder arriving before they came back — can call `start` freely without tracking whether
something is already running.

## Firing from shared code that is not on the main thread

Just call it. Both implementations launch their own coroutine internally and return before the
first cycle runs; there is no `suspend` modifier and no dispatcher parameter, so there is nothing
for a caller to get wrong. `start` returning is not the same as the torch having lit — do not use
it as a timing signal.

## Mistakes worth naming

- **Calling `start` on every emitted state.** A `Flow` that re-emits on every tick and a
  `flashlight.start` in its collector restarts the pattern from cycle zero each time, which reads
  as flickering rather than a steady rhythm. Fire on the *transition* into the state that warrants
  the cue, not on every emission of it.
- **Treating a missing `stop` as harmless.** The torch does turn itself off on cancellation and on
  process death it is off by construction, but while the process is alive an un-stopped blink loop
  keeps running and keeps draining battery. Pair every `start` with a `stop` the same way you would
  pair a lock with an unlock.
- **Polling `isAvailable` in a loop hoping a torch appears.** It is a hardware fact, fixed for the
  instance's lifetime; check it once.
- **Assuming a rear-camera device always has a torch.** Plenty of tablets have a camera and no
  flash unit — that is the normal case this module is built to degrade gracefully on, not an edge
  case to special-case away.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — the exact contract of every public symbol
- [`06-testing.md`](06-testing.md) — asserting on the flashlight cue without a device

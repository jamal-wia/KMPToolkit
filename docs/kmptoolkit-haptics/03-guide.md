# kmptoolkit-haptics — Guide

Scenarios in order of how often you will need them, then the mistakes worth naming.

## Choosing a `HapticType`

The vocabulary is deliberately small. Pick by *meaning*, never by "how strong should this feel" —
the strength is the platform's business and differs between devices anyway.

| Type | Use it for | Feels like |
|---|---|---|
| `LIGHT` | A selection changing, a picker ticking, a value crossing a snap point | One very short tap |
| `MEDIUM` | A control committing: a toggle flipping, an item added | One short tap |
| `HEAVY` | Something landing with weight: a drag docking, a card locking in | One firmer tap |
| `SUCCESS` | An operation finished as the user hoped | Two quick pulses |
| `WARNING` | It finished, but with a caveat worth noticing | Two even pulses |
| `ERROR` | It failed, or input was rejected | Three pulses |

A rule that keeps this honest: if you cannot describe the event in words, do not fire a haptic for
it. Haptics that fire on everything become noise the user turns off system-wide, which costs you
the ones that mattered.

## Respecting a user setting

The module does not remember preferences. Two ways to honor one, both fine:

**Swap the instance** — simplest when the setting is read once, at startup:

```kotlin
val haptics: HapticFeedback =
    if (settings.hapticsEnabled) createHapticFeedback(context) else noOpHapticFeedback()
```

**Decorate** — when the setting can change while the app is running:

```kotlin
class SettingsAwareHaptics(
    private val delegate: HapticFeedback,
    private val isEnabled: () -> Boolean,
) : HapticFeedback {

    override fun perform(type: HapticType): HapticResult =
        if (isEnabled()) delegate.perform(type) else HapticResult.UNAVAILABLE
}
```

`HapticFeedback` is a plain interface with one method precisely so that a decorator costs six
lines. There is no `setEnabled` on the module's own types, because a mutable flag inside a shared
object is state two callers can race over — and your app already has a place where settings live.

## Reacting to the result

Most call sites should ignore the return value: a decorative tap that did not happen is not an
error path worth writing. Three cases where reading it pays off:

```kotlin
when (haptics.perform(HapticType.SUCCESS)) {
    HapticResult.PERFORMED -> Unit
    HapticResult.UNAVAILABLE -> hideHapticsPreference()      // no motor: the setting is pointless
    HapticResult.PERMISSION_DENIED -> logger.e { "VIBRATE missing from the manifest" }
}
```

- `UNAVAILABLE` is a **hardware** fact and will not change while the app runs. Use it to hide a
  "vibration" switch in your settings screen rather than offering a toggle that does nothing.
- `PERMISSION_DENIED` is a **build configuration** defect — your manifest is missing
  `android.permission.VIBRATE`. It is not something to recover from at runtime; log it loudly in
  debug builds so it never reaches a release. See [`05-platform-notes.md`](05-platform-notes.md).
- `PERFORMED` is not proof that anything was felt. The user may have haptics disabled system-wide,
  and neither platform tells you.

## Firing a haptic from shared code that is not on the main thread

Just call it. The iOS implementation dispatches to the main queue itself because UIKit's feedback
generators require it; the Android one has no thread affinity. This is why the interface has no
`suspend` modifier and no dispatcher parameter — there is nothing for a caller to get wrong.

The flip side: `perform` returning is not the same as the pulse having played. Do not use it as a
timing signal, and do not `delay()` after it hoping to sequence two haptics — see below.

## Sequencing two haptics

Don't. On Android a second `vibrate()` cancels the first; on iOS two generator calls in the same
runloop turn may be merged by the system. If you need a compound feel, that is one `HapticType`
with a pattern — and if none of the six fits, this module is not the right tool
([`01-overview.md`](01-overview.md#what-this-is-not) names the alternative).

## Mistakes worth naming

- **Firing a haptic per emitted state.** A `Flow` that re-emits on every keystroke and a
  `haptics.perform` in its collector is a buzzing phone. Fire on the *transition* you care about,
  usually with `distinctUntilChanged` upstream or an explicit event rather than a state.
- **Treating `PERFORMED` as "the user felt it".** See above; it means the request was accepted.
- **Forgetting the manifest line.** Silent on Android, works on iOS — the most confusing possible
  failure mode, and the reason [`02-getting-started.md`](02-getting-started.md) makes it step 2.
- **Holding an `Activity` to build the instance later.** Build it once with the application context
  (the factory takes the application context out of whatever you hand it) and inject it. There is
  nothing to release, so there is no reason to defer construction.
- **Reaching for `HEAVY` to "make it more noticeable".** Amplitude is a device- and
  user-controlled property; escalating the type to compensate for a device the user turned down is
  fighting the platform.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — the exact contract of every public symbol
- [`06-testing.md`](06-testing.md) — asserting on haptics without a device

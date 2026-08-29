# kmptoolkit-biometric — Overview

One interface your shared code calls, `BiometricGate`, and two typed answers: a
`BiometricAvailability` saying whether this device can authenticate its owner at all, and a
`BiometricResult` saying what came of asking.

```kotlin
class UnlockPresenter(private val gate: BiometricGate, private val strings: Strings) {

    suspend fun unlock(): UnlockState = when (val result = gate.authenticate(
        BiometricPromptText(
            title = strings.unlockTitle,
            subtitle = strings.unlockSubtitle,
            cancelLabel = strings.cancel,
        ),
    )) {
        BiometricResult.Authenticated -> UnlockState.Unlocked
        BiometricResult.Cancelled -> UnlockState.Locked
        BiometricResult.Rejected -> UnlockState.Locked
        BiometricResult.NoPromptHost -> UnlockState.Locked
        is BiometricResult.Failed -> UnlockState.Locked
        is BiometricResult.Unavailable -> UnlockState.NeedsPassword(result.reason)
    }
}
```

That call site knows nothing about `FragmentActivity`, about the fragment `BiometricPrompt` posts
into it, about `LAContext` and its `NSError**` out-parameter, or about the API level at which
Android stopped being able to express "a fingerprint **or** the device PIN" as one authenticator
mask.

## The problem it solves

Putting a biometric check in front of a screen is a small feature with a long tail:

- **Android's prompt is a fragment.** It needs a resumed `FragmentActivity`, so shared code cannot
  show it without a way to reach one — and the obvious way (a static holder) leaks a destroyed
  activity. This module tracks one internally, weakly held and cleared on every pause or destroy,
  so it cannot be made to hold an activity beyond a callback.
- **Android's failure vocabulary is wide and shaped nothing like iOS's.** Twelve error codes, two
  distinct lockouts, a "security update required" state, and an availability query with its own
  separate set of statuses.
- **iOS has two policies, one reason string, and one lockout**, plus a Face ID usage description in
  the `Info.plist` without which the app is terminated rather than refused.
- **The device-credential fallback is a different API on each platform**, and on Android it is a
  different API on each side of API 30 — where getting it wrong is an `IllegalArgumentException`
  thrown from inside a UI callback, on the devices you tested on least.

The module collapses that into one interface, one prompt-copy type, and a result vocabulary in which
each case means something a caller can act on.

## What this is **not**

- **Not a keystore, and not encryption.** This is the most important line on the page. The gate
  returns "the OS says this is the owner" — a value, in your process, that an attacker with control
  of that process can simply produce. It does not derive a key, does not unlock a
  `CryptoObject`, and does not make anything unreadable. If your threat model includes someone with
  the device and the patience to attach a debugger or patch your APK, a `BiometricResult` is not
  what stands between them and your data: encryption is, and the key must be one the OS releases
  only after an authentication. Use
  [`kmptoolkit-storage`](../kmptoolkit-storage/01-overview.md)'s encrypted store for the secret
  itself, and use this module to decide when to show it. `if (authenticated) showSecret()` is a
  reasonable UX gate and a bypass when treated as security.
- **Not a source of prompt copy.** The OS renders your title, subtitle and cancel label verbatim,
  and this library has none of its own to offer — `BiometricPromptText` takes all three as required
  parameters with no defaults, so there is no English string of ours you can ship by accident. See
  [`03-guide.md`](03-guide.md#the-words-are-yours).
- **Not an enrolment or settings UI.** It reports `BiometricUnavailability.NOT_ENROLLED`; sending
  the user to the right system settings screen is platform UI code in your app, and the intent
  differs by OS version.
- **Not a permission requester.** Biometric access needs no runtime permission on either platform,
  and this module declares none of its own — see [`05-platform-notes.md`](05-platform-notes.md) for
  what `androidx.biometric` merges into your manifest and why it is not removed.
- **Not a session or grace period.** Every `authenticate` asks the OS afresh. "Do not ask again for
  five minutes" is app policy, and it is a few lines around this interface —
  [`03-guide.md`](03-guide.md#a-grace-period) shows it.
- **Not a way to know *how* the user authenticated.** Face, finger, or PIN is not reported. The
  policy you configured already decided what counts, and neither platform reports the distinction
  reliably enough to branch on.
- **Not tied to Compose or any UI framework.** Plain Kotlin, no UI dependency.

## When to use it

Use it when shared Kotlin code is the place that decides an action needs the device owner's
confirmation — opening a locked screen, revealing a stored credential, approving a change — and you
want that decision expressed once instead of twice.

If only your Android UI calls it, `androidx.biometric` directly is less indirection. The module pays
for itself the moment the same decision has to hold on both platforms, or the moment you want to
test the unenrolled and locked-out branches, which no emulator will hand you.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working gate on both platforms in five minutes
- [`03-guide.md`](03-guide.md) — the copy rules, the device-credential decision, grace periods, mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — manifest, `Info.plist`, API levels, error-code tables
- [`06-testing.md`](06-testing.md) — `ScriptedBiometricGate` and what to assert with it

# kmptoolkit-activity — platform notes

## Permissions

**None.** This module declares no permission in its manifest, and needs none:
`registerActivityLifecycleCallbacks` is a method on the consumer's own `Application`. Asserted by
`LibraryManifestTest` against a real `PackageManager`, not against a reading of the manifest.

## Android

### Why lifecycle callbacks rather than a `bind`/`unbind` pair

`Application.ActivityLifecycleCallbacks` sees every activity in the process without anyone
remembering to wire it up, which is the difference between "the tracker is wrong for an activity
someone forgot" and "the tracker is right by construction". The predicate then decides which of
those this instance answers with — an explicit choice, made in one place, rather than an omission
in a lifecycle override.

### When the reference is cleared

On `onActivityPaused` — the earliest point at which this activity is no longer the one the user is
interacting with — and again on `onActivityDestroyed` as belt and braces, for an activity destroyed
without a matching pause.

The comparison is by identity, not equality: during a configuration change the outgoing and incoming
activities both exist at once, and clearing on the old one's pause must not remove the new one.

### The weak reference

The reference the tracker holds is a `WeakReference`, as a second line of defence rather than as the
primary mechanism. The lifecycle callbacks are what clear it; the weak reference means that even if
a callback were somehow missed, the garbage collector can still reclaim the activity and
`withActivity` simply starts answering `null`.

### System screens and tasks

Why the presets use the flags they do, from how Android picks a task:

- **`FLAG_ACTIVITY_NEW_TASK` alone** looks for an existing task whose affinity matches the target's.
  Most Settings screens share the affinity `com.android.settings` (security and biometric screens
  use `com.android.settings.root`), and a Settings task opened earlier from a deep link — a
  quick-settings long-press, a notification, another app — often sits in the background. `NEW_TASK`
  brings that task forward and pushes the screen onto whatever page it held; Back, or the end of a
  wizard, then lands on that stale page rather than in your app. This was the behaviour of biometric
  enrolment, location settings and the special-permission screens up to 1.6.0, and is reproducible on
  a stock Pixel (API 36): open Wi-Fi settings from a deep link, press Home, then open location
  settings with `NEW_TASK` — it joins the Wi-Fi task.
- **`FLAG_ACTIVITY_NEW_DOCUMENT`**, added by `SeparateTask`, skips the affinity lookup. It reuses a
  task only if one is rooted at the same component with the same data — extras are ignored — and
  creates a fresh one otherwise. Consequences:
  - opening the same screen again brings its task forward with the requested screen on top; Back may
    pass through pages the user opened there before (App info → Permissions, say);
  - a task the user opened on that very screen themselves is reused the same way;
  - every different screen gets its own Recents card, where 1.6.0 stacked them into one Settings card.

  The system strips `NEW_DOCUMENT` from a target declared `singleTask`, `singleInstance` or
  `documentLaunchMode="never"`, which then behaves as with `NEW_TASK` alone — no worse than before.
- **`FLAG_ACTIVITY_CLEAR_TASK` is not used**: it would clear the matched task, and the matched task
  can be the user's own Settings session opened from the launcher.
- **No task flag** (`callerTask`) puts the screen in your task, above your activity — unless the
  target is declared `singleTask` or `singleInstance`, which the system always puts in a task of its
  own.

**`callerTask` falls back more often than you might expect.** Besides "no activity resumed", it
falls back when called off the main thread (`Activity.startActivity` touches the window, and an
activity destroyed during a background start makes the system add a bare `NEW_TASK` — the stale-task
problem again), and when called straight from an activity-result or permission-result callback:
Android delivers those before `onResume`, so the activity is not resumed yet. Post the call, or run
it after the activity resumes, if it must land on your task.

**Two-pane Settings.** On large screens AOSP Settings (12L and later) shows pages next to its menu. A
`SettingsActivity` page — app details, location, the special-permission pages, the biometrics
management screen — that is started as the root of a task, or with `NEW_TASK`, hands itself to the
Settings homepage (`DeepLinkHomepageActivity`, `singleTask`), which can be a stale task. So on such a
screen only a caller-task launch reliably avoids it. The biometric enrolment wizard and the
battery-optimisation dialog are not `SettingsActivity` pages and never hand off. OEM Settings apps
(One UI, for example) implement large screens differently; test on the devices you ship to.

**Lock-task mode.** Lock-task allows an activity by the package at the root of its task. A screen in a
separate task needs its package on the lock-task allowlist; a screen in your task does not, which is
exactly why a kiosk should not use `callerTask` — it would let the user roam Settings inside the
locked task. A start that lock-task forbids returns normally, so a launcher still answers `true`.

**Background starts.** Since Android 10, an activity start from an app in the background is blocked
without an exception. A launcher answers `true` because the start was handed to the system.

**Results.** An activity started with `NEW_TASK` for a result gets `RESULT_CANCELED` at once. A
launcher that needs a real result launches without task flags, from your activity — see
[`03-guide.md`](03-guide.md#your-own-launcher).

### Process death and restoration

Nothing here survives process death, and nothing needs to: the `Application` is recreated, your
`createActivityAccess` call runs again, and the first activity to resume is tracked as usual.

## iOS

There is no iOS target. UIKit has no `Activity`, and a view controller is owned by the app's own
hierarchy and reached directly rather than through a process-wide registry — so there is nothing for
an `expect`/`actual` pair to say here that would not be a fiction. Depend on this from `androidMain`
and keep the shared abstraction in your own code, where it can be shaped by what your app actually
needs from each platform.

## Desktop

No `jvm` target either, for the same reason.

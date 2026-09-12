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

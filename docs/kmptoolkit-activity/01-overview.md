# kmptoolkit-activity — overview

Scoped access to the Android activity that is resumed **right now**, for code that is not itself an
activity.

## The problem

Plenty of things a shared-code layer wants to do need an `Activity`, not a `Context`: writing to a
window, launching a permission request, starting an intent for a result. None of that code is an
activity, and the activity it needs does not have a stable identity — a rotation, a theme change or
a font-size change destroys it and builds another, several times in a session.

The usual answer is a static field written from `onResume` and cleared in `onPause`. It works until
someone adds an activity and forgets an override, and then the field pins a destroyed activity for
the life of the process. The failure is invisible until a heap dump.

## What this is

One interface, `ActivityAccess`, created from your `Application`:

- **There is no getter.** You cannot obtain an `Activity` and put it in a field; you run a block
  while the tracker still considers one valid. A leak has to be written on purpose.
- **The reference is weak**, and it is cleared by the framework's own lifecycle callbacks rather
  than by a `bind`/`unbind` pair a caller has to remember. There is no code path in which
  forgetting a call leaks.
- **You say which activities count.** By default every activity in the process is tracked, which is
  right when the thing being driven belongs to whichever activity the user is looking at. When it is
  not — a photo picker, a sign-in flow, or a `ComponentActivity` an SDK declared in its own manifest
  — a predicate narrows it, and an untracked activity resuming does not displace yours.

## What it is not

Not a navigator, not a back-stack, not a lifecycle observer. It answers one question — "which
activity, right now, if any" — and nothing else.

## Android only

This is the one module in the suite with no iOS target, because there is no iOS counterpart to an
`Activity`: UIKit's view controllers are owned by the app's own hierarchy and reached directly.
Depend on it from your `androidMain` source set, not from `commonMain`. See
[`../01-architecture.md`](../01-architecture.md) § "One module is Android-only".

## Who uses it

`kmptoolkit-systembars` and `kmptoolkit-permission` both take an `ActivityAccess`; each used to
carry its own private copy of this code. If you use either, you already have this on your classpath
and can share one instance with them so everything agrees on which window it means.
